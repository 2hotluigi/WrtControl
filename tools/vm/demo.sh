#!/bin/sh
# Un router OpenWrt de prueba en este PC, para probar WrtControl sin router propio (Linux y macOS;
# en Windows, demo.ps1). Solo necesita QEMU y curl: sin sudo, sin Image Builder.
#
#   sh tools/vm/demo.sh           # descarga la última OpenWrt estable (una vez) y la arranca
#   sh tools/vm/demo.sh --local   # igual, pero solo para el emulador de Android de este PC
#   sh tools/vm/demo.sh reset     # borra el router de prueba para empezar de cero
#
# La imagen es la oficial x86-64 de downloads.openwrt.org, comprobada con su sha256. El router no
# usa la red del PC: QEMU le hace una LAN y una WAN de mentira (red de usuario), y el puerto 8080
# del PC va a su web (la que usan LuCI y la app). La primera vez arranca solo para este PC, le pone
# a root una contraseña aleatoria y solo entonces se deja ver desde la red (el teléfono).
# Ctrl+C lo apaga; lo que se cambie en él se conserva hasta un reset (si se reinicia desde la app,
# vuelve a arrancar solo).
set -eu

DIR=${WRTC_DEMO_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/wrtcontrol-demo}
PORT=${WRTC_DEMO_PORT:-8080}
SSH_PORT=${WRTC_DEMO_SSH_PORT:-2222}
DOWNLOADS=https://downloads.openwrt.org
ROUTER=192.168.1.1
NO_SESSION=00000000000000000000000000000000
QEMU=

die() { echo "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "Falta $1. $2"; }
fetch() { curl -fsSL --retry 5 --retry-all-errors --retry-delay 5 "$1" -o "$2"; }

sha256() {
	if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1
}

# La última versión estable (p. ej. 25.12.6), de la lista que publica OpenWrt.
stable() {
	curl -fsSL --retry 5 --retry-all-errors "$DOWNLOADS/.versions.json" |
		sed -n 's/.*"stable_version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
}

download() {
	[ -f "$DIR/openwrt.img" ] && return
	version=$(stable)
	[ -n "$version" ] || die "No se pudo saber la última versión de OpenWrt"
	base=$DOWNLOADS/releases/$version/targets/x86/64
	file=openwrt-$version-x86-64-generic-ext4-combined.img.gz
	echo "Descargando OpenWrt $version..."
	mkdir -p "$DIR"
	fetch "$base/sha256sums" "$DIR/sha256sums"
	fetch "$base/$file" "$DIR/$file"
	sum=$(grep " \*$file\$" "$DIR/sha256sums" | cut -d' ' -f1)
	[ -n "$sum" ] && [ "$(sha256 "$DIR/$file")" = "$sum" ] || die "La imagen descargada no coincide con su sha256"
	# Las imágenes de OpenWrt llevan la firma detrás del gzip: gunzip avisa ("trailing garbage") y sale con 2.
	gunzip -c "$DIR/$file" > "$DIR/openwrt.img.tmp" 2>/dev/null || [ -s "$DIR/openwrt.img.tmp" ]
	mv "$DIR/openwrt.img.tmp" "$DIR/openwrt.img"
	rm -f "$DIR/$file" "$DIR/sha256sums"
	echo "$version" > "$DIR/version"
}

accel() {
	case "$(uname -s)" in
	Linux) [ -w /dev/kvm ] && echo "-accel kvm -cpu host" ;;
	Darwin) [ "$(uname -m)" = x86_64 ] && echo "-accel hvf -cpu host" ;;
	esac
	return 0
}

# Arranca el router en segundo plano; $1 es dónde escucha el puerto de la web (127.0.0.1 o 0.0.0.0).
start() {
	# shellcheck disable=SC2046
	qemu-system-x86_64 $(accel) -m 256 -smp 2 \
		-drive file="$DIR/openwrt.img",format=raw,if=virtio \
		-netdev user,id=lan,net=192.168.1.0/24,host=192.168.1.2,dhcpstart=192.168.1.100,hostfwd=tcp:$1:$PORT-$ROUTER:80,hostfwd=tcp:127.0.0.1:$SSH_PORT-$ROUTER:22 \
		-device virtio-net-pci,netdev=lan \
		-netdev user,id=wan \
		-device virtio-net-pci,netdev=wan \
		-display none -monitor none -serial file:"$DIR/console.log" &
	QEMU=$!
}

# Si algo falla por el camino, que no se quede QEMU en marcha.
trap '[ -z "$QEMU" ] || kill "$QEMU" 2>/dev/null || true' EXIT

ubus() {
	curl -fs -m 5 -H 'Content-Type: application/json' \
		-d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"call\",\"params\":[\"$1\",\"$2\",\"$3\",$4]}" \
		"http://127.0.0.1:$PORT/ubus"
}

# Espera a que el router deje entrar con la contraseña $1 e imprime la sesión.
login() {
	i=0
	while :; do
		kill -0 "$QEMU" 2>/dev/null || { tail -n 30 "$DIR/console.log" >&2; die "QEMU se cerró"; }
		session=$(ubus $NO_SESSION session login "{\"username\":\"root\",\"password\":\"$1\"}" 2>/dev/null |
			sed -n 's/.*"ubus_rpc_session"[[:space:]]*:[[:space:]]*"\([0-9a-f]*\)".*/\1/p') || true
		[ -n "$session" ] && { echo "$session"; return; }
		i=$((i + 1))
		[ $i -lt 90 ] || { tail -n 30 "$DIR/console.log" >&2; die "El router no arrancó"; }
		sleep 2
	done
}

# Apaga el router: escribe en el disco lo pendiente y cierra QEMU. $1 es la contraseña.
shutdown() {
	session=$(login "$1" 2>/dev/null) &&
		ubus "$session" file exec '{"command":"/bin/sync"}' >/dev/null 2>&1 || true
	kill "$QEMU" 2>/dev/null || true
	wait "$QEMU" 2>/dev/null || true
	QEMU=
}

# La primera vez: solo para este PC, contraseña aleatoria, y apagar.
first_boot() {
	[ -f "$DIR/password" ] && return
	echo "Primer arranque: poniendo una contraseña al router..."
	start 127.0.0.1
	session=$(login "")
	password=$(LC_ALL=C tr -dc 'a-km-zA-HJ-NP-Z2-9' < /dev/urandom | head -c 12)
	ubus "$session" luci setPassword "{\"username\":\"root\",\"password\":\"$password\"}" | grep -q '"result":\[0' ||
		die "No se pudo poner la contraseña"
	shutdown "$password"
	echo "$password" > "$DIR/password"
}

# Las IPv4 de este PC en su red, para escribirlas en el teléfono.
addresses() {
	if command -v ip >/dev/null 2>&1; then
		ip -4 -o addr show scope global | awk '{split($4, a, "/"); print a[1]}'
	else
		ifconfig 2>/dev/null | awk '/inet / && $2 != "127.0.0.1" {print $2}'
	fi
}

run() {
	bind=0.0.0.0
	[ "${1:-}" = --local ] && bind=127.0.0.1
	need qemu-system-x86_64 "Instálalo: apt install qemu-system-x86 (Debian, Ubuntu), dnf install qemu-system-x86 (Fedora) o brew install qemu (macOS)."
	need curl "Instálalo con el gestor de paquetes del sistema."
	download
	first_boot
	password=$(cat "$DIR/password")
	start "$bind"
	login "$password" >/dev/null
	trap 'echo; echo "Apagando el router..."; shutdown "$password"; exit 0' INT TERM
	echo
	echo "Router de prueba listo: OpenWrt $(cat "$DIR/version")"
	echo
	echo "  En WrtControl, dirección:"
	echo "    10.0.2.2:$PORT          desde el emulador de Android de este PC"
	if [ "$bind" = 0.0.0.0 ]; then
		for a in $(addresses); do echo "    $a:$PORT   desde un teléfono en la misma red que este PC"; done
	fi
	echo "  Usuario root, contraseña $password"
	echo
	echo "  LuCI: http://127.0.0.1:$PORT/   SSH: ssh -p $SSH_PORT root@127.0.0.1"
	echo "  Ctrl+C para apagarlo. Lo que cambies se queda hasta 'sh $0 reset'."
	wait "$QEMU" || true
}

case "${1:-}" in
'' | --local) run "${1:-}" ;;
reset) rm -rf "$DIR" && echo "Router de prueba borrado" ;;
*) die "uso: $0 [--local | reset]" ;;
esac
