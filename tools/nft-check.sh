#!/bin/sh
# Comprueba los ejemplos de NftSamplesTest con el nftables y el fw4 de una imagen de OpenWrt.
# Dentro del contenedor (ver .github/workflows/ci.yml):
#   docker run --rm --cap-add NET_ADMIN -v "$PWD/tools:/tools:ro" -v "$SAMPLES:/samples:ro" \
#     openwrt/rootfs:x86-64-24.10.8 /bin/sh /tools/nft-check.sh
# Para cada ejemplo:
#   1. nft -c del fichero de reglas, dentro de una tabla como lo incluye fw4 (table-append).
#   2. fw4 check con las secciones wrtc_ en /etc/config/firewall: todo lo que genera fw4 con
#      ellas (reglas MARK, redirecciones del DNS, include) tiene que analizar.
set -u

fail=0
. /etc/openwrt_release 2>/dev/null
echo "OpenWrt ${DISTRIB_RELEASE:-?} · $(nft --version)"
mkdir -p /etc/luci-uploads /var/lock /var/run /tmp/run
cp /etc/config/firewall /tmp/firewall.orig

# Control: un fichero roto tiene que fallar en las dos comprobaciones (si no, un "ok" no vale nada).
printf 'chain wrtc_roto {\n\tmeta nada que analice\n}\n' > /etc/luci-uploads/roto.nft
{ echo 'table inet wrtc_chk {'; cat /etc/luci-uploads/roto.nft; echo '}'; } > /tmp/chk.nft
if nft -c -f /tmp/chk.nft > /dev/null 2>&1; then echo "FALLO control: nft -c acepta un fichero roto"; fail=1; fi
uci -q batch <<-EOF
	set firewall.wrtc_roto=include
	set firewall.wrtc_roto.type='nftables'
	set firewall.wrtc_roto.path='/etc/luci-uploads/roto.nft'
	set firewall.wrtc_roto.position='table-append'
EOF
uci commit firewall
if fw4 check > /dev/null 2>&1; then echo "FALLO control: fw4 check acepta un include roto"; fail=1; else echo "ok   control: lo roto falla"; fi
cp /tmp/firewall.orig /etc/config/firewall
rm -f /etc/luci-uploads/roto.nft

for dir in /samples/*/; do
	name=$(basename "$dir")
	for f in "$dir"wrtcontrol-*.nft; do
		{ echo 'table inet wrtc_chk {'; cat "$f"; echo '}'; } > /tmp/chk.nft
		if nft -c -f /tmp/chk.nft > /tmp/chk.log 2>&1; then
			echo "ok   $name: nft -c"
		else
			echo "FALLO $name: nft -c"; cat /tmp/chk.log; fail=1
		fi
		cp "$f" /etc/luci-uploads/
	done

	cp /tmp/firewall.orig /etc/config/firewall
	uci -q batch < "${dir}firewall.batch" && uci commit firewall
	if fw4 check > /tmp/fw4.log 2>&1; then
		echo "ok   $name: fw4 check"
	else
		echo "FALLO $name: fw4 check"; cat /tmp/fw4.log; fail=1
	fi
	rm -f /etc/luci-uploads/wrtcontrol-*.nft
done

exit $fail
