#!/bin/sh
# Un OpenWrt x86-64 en QEMU con la misma red que la VM de VirtualBox, para pasar VmIntegrationTest y
# AddonsVmTest sin tocarlas (la CI lo usa en .github/workflows/vm.yml; vale en cualquier Linux con KVM):
#   - LAN (eth0): el router en 192.168.56.2 y este equipo en 192.168.56.1, por una interfaz tap.
#   - WAN (eth1): la red de usuario de QEMU, como el NAT de VirtualBox: el router recibe 10.0.3.15
#     y llega a este equipo por 10.0.3.2 (el "proveedor" de DNS dinámico de AddonsVmTest).
#   - ULA fd27:71c3:f0a8::/48, la de la VM de VirtualBox que usan las pruebas.
#
#   sh tools/vm/openwrt-qemu.sh build 25.12 out ~/.ssh/id_ed25519.pub   # 24.10, 25.12 o snapshot
#   sh tools/vm/openwrt-qemu.sh up out/openwrt.img                      # necesita sudo (tap)
#   sh tools/vm/openwrt-qemu.sh down
#
# La imagen se hace con el Image Builder oficial: ext4 (todo escribible, también los paquetes que
# trae la imagen, que las pruebas desinstalan y vuelven a instalar), con la clave SSH ya puesta y los
# paquetes que necesitan las pruebas.
set -eu

ROUTER=192.168.56.2
HOST_LAN=192.168.56.1
TAP=wrtc0
STATE=${WRTC_QEMU_STATE:-/tmp/wrtc-qemu}
# luci no viene en las imágenes snapshot. luci-app-* arrastran ddns-scripts, sqm-scripts y miniupnpd.
PACKAGES="luci ip-full kmod-veth luci-proto-wireguard luci-app-ddns luci-app-sqm luci-app-upnp miniupnpc"

die() { echo "$*" >&2; exit 1; }

# Versión más nueva de una serie (24.10 → 24.10.8), sin candidatas.
latest() {
	curl -fsSL https://downloads.openwrt.org/.versions.json |
		jq -r --arg s "$1." '[.versions_list[] | select(startswith($s)) | select(test("-rc") | not)] | first // empty'
}

build() {
	series=$1 out=$2 pubkey=$3
	[ -f "$pubkey" ] || die "No encuentro la clave pública $pubkey"
	if [ "$series" = snapshot ]; then
		base=https://downloads.openwrt.org/snapshots/targets/x86/64
		version=snapshot
	else
		version=$(latest "$series")
		[ -n "$version" ] || die "No hay versiones de la serie $series"
		base=https://downloads.openwrt.org/releases/$version/targets/x86/64
	fi
	echo "OpenWrt $version ($base)"

	mkdir -p "$out"
	out=$(cd "$out" && pwd)
	work=$out/work
	rm -rf "$work" && mkdir -p "$work"
	curl -fsSL "$base/sha256sums" -o "$work/sha256sums"
	ib=$(sed -n 's/^[0-9a-f]* \*\(openwrt-imagebuilder-.*x86-64\.Linux-x86_64\.tar\.zst\)$/\1/p' "$work/sha256sums")
	[ -n "$ib" ] || die "No encuentro el Image Builder en $base/sha256sums"
	curl -fsSL "$base/$ib" -o "$work/$ib"
	(cd "$work" && grep " \*$ib\$" sha256sums | sha256sum -c -)
	tar --zstd -xf "$work/$ib" -C "$work"
	ibdir=$(find "$work" -maxdepth 1 -type d -name 'openwrt-imagebuilder-*' | head -n 1)

	files=$work/files
	mkdir -p "$files/etc/dropbear" "$files/etc/uci-defaults"
	cp "$pubkey" "$files/etc/dropbear/authorized_keys"
	chmod 600 "$files/etc/dropbear/authorized_keys"
	cat > "$files/etc/uci-defaults/99-wrtcontrol-ci" <<-EOF
		uci -q delete network.lan.ipaddr
		uci set network.lan.ipaddr='$ROUTER'
		uci set network.lan.netmask='255.255.255.0'
		uci set network.globals.ula_prefix='fd27:71c3:f0a8::/48'
		uci commit network
		exit 0
	EOF

	make -C "$ibdir" image PROFILE=generic PACKAGES="$PACKAGES" FILES="$files" ROOTFS_PARTSIZE=512
	img=$(find "$ibdir/bin/targets/x86/64" -name '*-generic-ext4-combined.img.gz' | head -n 1)
	[ -n "$img" ] || die "El Image Builder no generó la imagen ext4"
	# Las imágenes de OpenWrt llevan la firma detrás del gzip: gunzip avisa ("trailing garbage") y sale con 2.
	gunzip -c "$img" > "$out/openwrt.img" || [ -s "$out/openwrt.img" ]
	echo "$version" > "$out/version"
	rm -rf "$work"
	echo "Imagen: $out/openwrt.img ($version)"
}

up() {
	img=$1
	[ -f "$img" ] || die "No encuentro la imagen $img"
	mkdir -p "$STATE"
	# QEMU hace los ping de la WAN con sockets ICMP sin privilegios.
	sudo sysctl -q -w net.ipv4.ping_group_range="0 2147483647"
	sudo ip tuntap add dev $TAP mode tap user "$(id -un)"
	sudo ip addr add $HOST_LAN/24 dev $TAP
	sudo ip link set $TAP up
	kvm=""
	[ -w /dev/kvm ] && kvm="-enable-kvm -cpu host"
	# shellcheck disable=SC2086
	qemu-system-x86_64 $kvm -m 512 -smp 2 \
		-drive file="$img",format=raw,if=virtio \
		-netdev tap,id=lan,ifname=$TAP,script=no,downscript=no \
		-device virtio-net-pci,netdev=lan,mac=52:54:00:56:00:02 \
		-netdev user,id=wan,net=10.0.3.0/24,host=10.0.3.2,dhcpstart=10.0.3.15 \
		-device virtio-net-pci,netdev=wan,mac=52:54:00:03:00:15 \
		-display none -monitor none -serial file:"$STATE/console.log" \
		-pidfile "$STATE/qemu.pid" -daemonize

	echo "Esperando al router en $ROUTER..."
	i=0
	until curl -fs -o /dev/null -m 2 "http://$ROUTER/"; do
		i=$((i + 1))
		[ $i -lt 120 ] || { tail -n 50 "$STATE/console.log"; die "El router no arrancó"; }
		sleep 2
	done
	echo "Router arriba en $((i * 2)) s"
}

down() {
	[ -f "$STATE/qemu.pid" ] && kill "$(cat "$STATE/qemu.pid")" 2>/dev/null || true
	sudo ip link del $TAP 2>/dev/null || true
	rm -f "$STATE/qemu.pid"
}

case "${1:-}" in
build) [ $# -eq 4 ] || die "uso: $0 build <24.10|25.12|snapshot> <dir> <clave.pub>"; build "$2" "$3" "$4" ;;
up) [ $# -eq 2 ] || die "uso: $0 up <imagen>"; up "$2" ;;
down) down ;;
*) die "uso: $0 build|up|down" ;;
esac
