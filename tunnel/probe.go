package wrtcvpn

import (
	"errors"
	"fmt"
	"net"
	"net/netip"
	"os"
	"sync"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// Resultados de Probe.
const (
	// ProbeNoReply: los saludos salieron y el router no contestó.
	ProbeNoReply = 0
	// ProbeReached: hubo saludo con el router.
	ProbeReached = 1
	// ProbeCannotSend: no salió ningún saludo (esa red no tiene esa familia, IPv4 o IPv6).
	ProbeCannotSend = 2
)

// Probe comprueba si se llega al router desde fuera: un saludo de WireGuard hacia endpoint por el
// socket UDP fd, que la app ya ató a los datos móviles (Network.bindSocket) sin dejar el Wi-Fi.
// Probe se queda con fd y lo cierra. Espera hasta timeoutMs (WireGuard repite el saludo cada 5 s).
//
// Usa la clave del propio teléfono: si contesta, el router además reconoce a este teléfono.
func Probe(privateKey, peerPublicKey, presharedKey, endpoint string, fd int, timeoutMs int) (int, error) {
	f := os.NewFile(uintptr(fd), "probe")
	if f == nil {
		return 0, errors.New("socket no válido")
	}
	pc, err := net.FilePacketConn(f)
	f.Close() // FilePacketConn trabaja con una copia del descriptor
	if err != nil {
		return 0, err
	}
	return probeOver(pc, privateKey, peerPublicKey, presharedKey, endpoint, time.Duration(timeoutMs)*time.Millisecond)
}

func probeOver(pc net.PacketConn, privateKey, peerPublicKey, presharedKey, endpoint string, timeout time.Duration) (int, error) {
	b := &packetBind{pc: pc}
	defer pc.Close()
	priv, err := keyHex("privada", privateKey)
	if err != nil {
		return 0, err
	}
	pub, err := keyHex("del router", peerPublicKey)
	if err != nil {
		return 0, err
	}
	psk, err := keyHex("precompartida", presharedKey)
	if err != nil {
		return 0, err
	}
	ep, err := netip.ParseAddrPort(endpoint)
	if err != nil {
		return 0, fmt.Errorf("dirección del router no válida: %w", err)
	}
	// Una pila de red de juguete: solo hace falta el saludo, no se manda nada por el túnel.
	tun, _, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.255.255.2")}, nil, 1280)
	if err != nil {
		return 0, err
	}
	dev := device.NewDevice(tun, b, device.NewLogger(device.LogLevelSilent, ""))
	defer dev.Close()
	// El aviso de mantener vivo hace que el primer saludo salga nada más levantarlo.
	uapi := fmt.Sprintf(
		"private_key=%s\npublic_key=%s\npreshared_key=%s\nendpoint=%s\nallowed_ip=10.255.255.1/32\npersistent_keepalive_interval=25\n",
		priv, pub, psk, ep.String(),
	)
	if err := dev.IpcSet(uapi); err != nil {
		return 0, err
	}
	if err := dev.Up(); err != nil {
		return 0, err
	}
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if handshakeTime(dev) > 0 {
			return ProbeReached, nil
		}
		time.Sleep(100 * time.Millisecond)
	}
	if handshakeTime(dev) > 0 {
		return ProbeReached, nil
	}
	if !b.sentAny() {
		return ProbeCannotSend, nil
	}
	return ProbeNoReply, nil
}

func handshakeTime(dev *device.Device) int64 {
	t := &Tunnel{dev: dev}
	return t.LastHandshake()
}

// packetBind es un conn.Bind sobre un socket que ya existe. El dispositivo lo cierra y lo abre
// al levantarse: cerrar solo despierta a quien está leyendo; el socket se cierra al acabar Probe.
type packetBind struct {
	pc     net.PacketConn
	mu     sync.Mutex
	open   bool
	sent   bool
	closed chan struct{}
}

func (b *packetBind) Open(uint16) ([]conn.ReceiveFunc, uint16, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.open {
		return nil, 0, conn.ErrBindAlreadyOpen
	}
	b.open = true
	b.closed = make(chan struct{})
	_ = b.pc.SetReadDeadline(time.Time{})
	closed := b.closed
	recv := func(packets [][]byte, sizes []int, eps []conn.Endpoint) (int, error) {
		n, addr, err := b.pc.ReadFrom(packets[0])
		if err != nil {
			select {
			case <-closed:
				return 0, net.ErrClosed
			default:
			}
			var ne net.Error
			if errors.As(err, &ne) && ne.Timeout() {
				return 0, nil
			}
			return 0, err
		}
		ua, ok := addr.(*net.UDPAddr)
		if !ok {
			return 0, nil
		}
		sizes[0] = n
		eps[0] = endpointOf(ua.AddrPort())
		return 1, nil
	}
	port := 0
	if ua, ok := b.pc.LocalAddr().(*net.UDPAddr); ok {
		port = ua.Port
	}
	return []conn.ReceiveFunc{recv}, uint16(port), nil
}

func (b *packetBind) Close() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.open {
		b.open = false
		close(b.closed)
		_ = b.pc.SetReadDeadline(time.Now())
	}
	return nil
}

func (b *packetBind) SetMark(uint32) error { return nil }

func (b *packetBind) BatchSize() int { return 1 }

func (b *packetBind) Send(bufs [][]byte, ep conn.Endpoint) error {
	e, ok := ep.(probeEndpoint)
	if !ok {
		return conn.ErrWrongEndpointType
	}
	for _, buf := range bufs {
		if _, err := b.pc.WriteTo(buf, net.UDPAddrFromAddrPort(e.AddrPort)); err != nil {
			return err
		}
	}
	b.mu.Lock()
	b.sent = true
	b.mu.Unlock()
	return nil
}

func (b *packetBind) sentAny() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.sent
}

func (b *packetBind) ParseEndpoint(s string) (conn.Endpoint, error) {
	ap, err := netip.ParseAddrPort(s)
	if err != nil {
		return nil, err
	}
	return endpointOf(ap), nil
}

// probeEndpoint: una dirección del router. Las IPv4 que llegan por un socket IPv6 (::ffff:a.b.c.d)
// se guardan como IPv4.
type probeEndpoint struct{ netip.AddrPort }

func endpointOf(ap netip.AddrPort) probeEndpoint {
	return probeEndpoint{netip.AddrPortFrom(ap.Addr().Unmap(), ap.Port())}
}

func (probeEndpoint) ClearSrc()             {}
func (probeEndpoint) SrcToString() string   { return "" }
func (e probeEndpoint) DstToString() string { return e.AddrPort.String() }
func (e probeEndpoint) DstToBytes() []byte  { b, _ := e.AddrPort.MarshalBinary(); return b }
func (e probeEndpoint) DstIP() netip.Addr   { return e.Addr() }
func (probeEndpoint) SrcIP() netip.Addr     { return netip.Addr{} }
