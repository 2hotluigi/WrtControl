#!/bin/sh
# Cliente de pruebas dentro del router: un namespace de red conectado a br-lan con una MAC fija,
# para comprobar con tráfico real que las pausas y los horarios bloquean (ver VmIntegrationTest).
# Necesita ip-full y kmod-veth (apk add ip-full kmod-veth). No sobrevive a un reinicio.
#
#   scp tools/vm/netns-client.sh root@192.168.56.2:/tmp/   (o: ssh root@... 'cat > /tmp/netns-client.sh' < ...)
#   ssh root@192.168.56.2 sh /tmp/netns-client.sh up | down | status
NS=wrtc_client
MAC=02:00:00:00:00:99
IP=192.168.56.99
GW=192.168.56.2
# Con setsid u otros PATH, `ip` puede resolver al applet de BusyBox, que no tiene `netns`.
IPF=/usr/libexec/ip-full
[ -x "$IPF" ] || IPF=ip

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
	$IPF netns exec $NS ping -c 2 -W 2 1.1.1.1 >/dev/null && echo "cliente $MAC ($IP) con internet"
	;;
down)
	$IPF link del wrtc_v0 2>/dev/null
	$IPF netns del $NS 2>/dev/null
	echo "cliente eliminado"
	;;
status)
	$IPF netns list
	$IPF -br link show wrtc_v0 2>/dev/null
	;;
*)
	echo "uso: $0 up|down|status"
	exit 1
	;;
esac
