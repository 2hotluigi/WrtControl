// Package wrtcvpn es el acceso remoto de WrtControl: WireGuard en espacio de usuario (wireguard-go
// con la pila TCP/IP de gVisor, "netstack") dentro de la propia app. No usa la VPN de Android ni
// una interfaz TUN, así que no choca con otra VPN del teléfono: solo la app pasa por el túnel.
//
// El túnel abre un puerto en 127.0.0.1 y cada conexión TCP que llega ahí sale por el túnel hacia
// la web del router (target). La app conecta sus peticiones HTTP a ese puerto.
//
// Se compila para Android con gomobile (ver tools/build-tunnel.sh).
package wrtcvpn

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// Tunnel es un túnel en marcha.
type Tunnel struct {
	dev       *device.Device
	tnet      *netstack.Net
	ln        net.Listener
	target    netip.AddrPort
	peer      string
	closeOnce sync.Once
}

func keyHex(name, b64 string) (string, error) {
	b, err := base64.StdEncoding.DecodeString(strings.TrimSpace(b64))
	if err != nil || len(b) != 32 {
		return "", fmt.Errorf("clave %s no válida", name)
	}
	return hex.EncodeToString(b), nil
}

// Start levanta el túnel. Las claves van en base64 (como las escribe wg); endpoint es
// "ip:puerto" o "[ipv6]:puerto"; address, la IPv4 del teléfono en el túnel; target, la web del
// router por el túnel ("10.77.0.1:80"). mtu 0: 1280, que cabe también sobre IPv6.
func Start(privateKey, peerPublicKey, presharedKey, endpoint, address, target string, mtu int) (*Tunnel, error) {
	priv, err := keyHex("privada", privateKey)
	if err != nil {
		return nil, err
	}
	pub, err := keyHex("del router", peerPublicKey)
	if err != nil {
		return nil, err
	}
	psk, err := keyHex("precompartida", presharedKey)
	if err != nil {
		return nil, err
	}
	ep, err := netip.ParseAddrPort(endpoint)
	if err != nil {
		return nil, fmt.Errorf("dirección del router no válida: %w", err)
	}
	addr, err := netip.ParseAddr(address)
	if err != nil {
		return nil, fmt.Errorf("dirección del túnel no válida: %w", err)
	}
	tgt, err := netip.ParseAddrPort(target)
	if err != nil {
		return nil, fmt.Errorf("destino no válido: %w", err)
	}
	if mtu <= 0 {
		mtu = 1280
	}

	tun, tnet, err := netstack.CreateNetTUN([]netip.Addr{addr}, nil, mtu)
	if err != nil {
		return nil, err
	}
	dev := device.NewDevice(tun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "wrtcvpn: "))
	// Mantener vivo el túnel cada 25 s: además hace el primer saludo nada más levantarlo.
	uapi := fmt.Sprintf(
		"private_key=%s\npublic_key=%s\npreshared_key=%s\nendpoint=%s\nallowed_ip=%s/32\npersistent_keepalive_interval=25\n",
		priv, pub, psk, ep.String(), tgt.Addr().String(),
	)
	if err := dev.IpcSet(uapi); err != nil {
		dev.Close()
		return nil, err
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return nil, err
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		dev.Close()
		return nil, err
	}
	t := &Tunnel{dev: dev, tnet: tnet, ln: ln, target: tgt, peer: pub}
	go t.serve()
	return t, nil
}

// LocalPort es el puerto de 127.0.0.1 que lleva a la web del router.
func (t *Tunnel) LocalPort() int {
	return t.ln.Addr().(*net.TCPAddr).Port
}

// SetEndpoint cambia la dirección del router (p. ej. si la primera no responde).
func (t *Tunnel) SetEndpoint(endpoint string) error {
	ep, err := netip.ParseAddrPort(endpoint)
	if err != nil {
		return err
	}
	return t.dev.IpcSet(fmt.Sprintf("public_key=%s\nupdate_only=true\nendpoint=%s\n", t.peer, ep.String()))
}

// LastHandshake es la hora (segundos Unix) del último saludo con el router; 0 si aún no hubo.
func (t *Tunnel) LastHandshake() int64 {
	state, err := t.dev.IpcGet()
	if err != nil {
		return 0
	}
	for _, line := range strings.Split(state, "\n") {
		if v, ok := strings.CutPrefix(line, "last_handshake_time_sec="); ok {
			n, _ := strconv.ParseInt(v, 10, 64)
			return n
		}
	}
	return 0
}

// Stop cierra el túnel y el puerto local.
func (t *Tunnel) Stop() {
	t.closeOnce.Do(func() {
		t.ln.Close()
		t.dev.Close()
	})
}

func (t *Tunnel) serve() {
	for {
		c, err := t.ln.Accept()
		if err != nil {
			return
		}
		go t.forward(c)
	}
}

func (t *Tunnel) forward(c net.Conn) {
	defer c.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	r, err := t.tnet.DialContextTCPAddrPort(ctx, t.target)
	cancel()
	if err != nil {
		return
	}
	defer r.Close()
	// Cada sentido por su lado: cuando uno acaba (p. ej. el cliente terminó de enviar), se cierra
	// solo la escritura hacia el otro y se sigue esperando su respuesta.
	done := make(chan struct{}, 2)
	pipe := func(dst, src net.Conn) {
		_, _ = io.Copy(dst, src)
		if cw, ok := dst.(interface{ CloseWrite() error }); ok {
			_ = cw.CloseWrite()
		} else {
			_ = dst.Close()
		}
		done <- struct{}{}
	}
	go pipe(r, c)
	go pipe(c, r)
	<-done
	<-done
}
