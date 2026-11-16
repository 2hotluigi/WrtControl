#!/bin/sh
# Cliente de pruebas dentro del router: un namespace de red conectado a br-lan con una MAC fija,
# para comprobar con tráfico real que las pausas y los horarios bloquean (ver VmIntegrationTest).
# Necesita ip-full y kmod-veth (apk add ip-full kmod-veth). No sobrevive a un reinicio.
#
#   scp tools/vm/netns-client.sh root@192.168.56.2:/tmp/   (o: ssh root@... 'cat > /tmp/netns-client.sh' < ...)
#   ssh root@192.168.56.2 sh /tmp/netns-client.sh up | down | status
#
# Con un router de verdad, el PC no siempre queda al otro lado de la WAN (en la VM, la NAT de
# VirtualBox lo pone en 10.0.3.2). `inet-up` crea ahí un "internet" de pruebas: otro namespace en
# la zona wan del router (198.51.100.2) con un eco TCP en el puerto 47101 que responde
# "OK <línea>", para las conexiones largas de VmIntegrationTest (WRTCONTROL_ECHO_TARGET), y un
# "proveedor" de DNS dinámico en el 47102 que apunta cada petición en /tmp/wrtc_ddns_requests y
# contesta "good" (AddonsVmTest, WRTCONTROL_DDNS_TARGET): así no hay que abrir el firewall del PC.
# Necesita socat (apk add socat). `inet-down` lo quita, también de la zona wan.
NS=wrtc_client
MAC=02:00:00:00:00:99
# La LAN del router (192.168.56.2 en la VM, 192.168.8.1 en un GL.iNet...): el cliente es la .99.
GW=${GW:-$(uci -q get network.lan.ipaddr | cut -d/ -f1)}
IP=${IP:-${GW%.*}.99}
# Para comprobar la salida: en la CI, el PC (los runners de GitHub no dejan salir ICMP a internet).
PING_TARGET=${PING_TARGET:-1.1.1.1}
# Con setsid u otros PATH, `ip` puede resolver al applet de BusyBox, que no tiene `netns`.
IPF=/usr/libexec/ip-full
[ -x "$IPF" ] || IPF=ip
INET_NS=wrtc_inet
# Sin "wrtc_": las pruebas comprueban que no queda nada de WrtControl en /etc/config/firewall.
INET_DEV=labinet0

wan_zone() { uci show firewall | sed -n "s/^firewall\.\(@zone\[[0-9]*\]\)\.name='wan'$/\1/p" | head -1; }

case "$1" in
up)
	$IPF netns list | grep -q "^$NS" && { echo "ya existe"; exit 0; }
	$IPF netns add $NS
	$IPF link add wrtc_v0 type veth peer name wrtc_v1
	$IPF link set wrtc_v1 netns $NS
	$IPF link set wrtc_v0 master br-lan up
	$IPF netns exec $NS $IPF link set lo up
	$IPF netns exec $NS $IPF link set wrtc_v1 address $MAC up
	$IPF netns exec $NS $IPF addr add $IP/24 dev wrtc_v1
	$IPF netns exec $NS $IPF route add default via $GW
	$IPF netns exec $NS ping -c 2 -W 2 "$PING_TARGET" >/dev/null && echo "cliente $MAC ($IP) con salida a $PING_TARGET"
	;;
down)
	$IPF link del wrtc_v0 2>/dev/null
	$IPF netns del $NS 2>/dev/null
	echo "cliente eliminado"
	;;
inet-up)
	$IPF netns list | grep -q "^$INET_NS" && { echo "ya existe"; exit 0; }
	command -v socat >/dev/null || { echo "falta socat (apk add socat)"; exit 1; }
	$IPF netns add $INET_NS
	$IPF link add $INET_DEV type veth peer name labinet1
	$IPF link set labinet1 netns $INET_NS
	$IPF addr add 198.51.100.1/24 dev $INET_DEV
	$IPF link set $INET_DEV up
	$IPF netns exec $INET_NS $IPF link set lo up
	$IPF netns exec $INET_NS $IPF link set labinet1 up
	$IPF netns exec $INET_NS $IPF addr add 198.51.100.2/24 dev labinet1
	$IPF netns exec $INET_NS $IPF route add default via 198.51.100.1
	zone=$(wan_zone)
	uci -q del_list firewall.$zone.device=$INET_DEV
	uci add_list firewall.$zone.device=$INET_DEV && uci commit firewall && fw4 -q reload
	(setsid $IPF netns exec $INET_NS socat TCP-LISTEN:47101,fork,reuseaddr SYSTEM:'while read l; do echo OK $l; done' \
		>/dev/null 2>&1 &)
	# En un script aparte: socat trata comillas, barras y ":" del comando como sintaxis propia.
	# Lee también las cabeceras: si cierra antes, uclient-fetch recibe un reset y lo da por fallido.
	cat > /tmp/wrtc_ddns_provider.sh <<-'PROVIDER'
	read l
	echo "$l" >> /tmp/wrtc_ddns_requests
	while read h && [ ${#h} -gt 1 ]; do true; done
	printf 'HTTP/1.0 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\ngood'
	PROVIDER
	: > /tmp/wrtc_ddns_requests
	(setsid $IPF netns exec $INET_NS socat TCP-LISTEN:47102,fork,reuseaddr EXEC:'sh /tmp/wrtc_ddns_provider.sh' \
		>/dev/null 2>&1 &)
	echo "eco en 198.51.100.2 47101 y proveedor DDNS en 198.51.100.2:47102 (zona wan)"
	;;
inet-down)
	for pid in $(pgrep -f "socat TCP-LISTEN:4710[12]"); do kill "$pid"; done
	rm -f /tmp/wrtc_ddns_requests /tmp/wrtc_ddns_provider.sh
	$IPF link del $INET_DEV 2>/dev/null
	$IPF netns del $INET_NS 2>/dev/null
	zone=$(wan_zone)
	uci -q del_list firewall.$zone.device=$INET_DEV && uci commit firewall && fw4 -q reload
	echo "internet de pruebas eliminado"
	;;
status)
	$IPF netns list
	$IPF -br link show wrtc_v0 2>/dev/null
	;;
*)
	echo "uso: $0 up|down|inet-up|inet-down|status"
	exit 1
	;;
esac
