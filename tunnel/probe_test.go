package wrtcvpn

import (
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"net"
	"testing"
	"time"
)

func listenUDP4(t *testing.T) net.PacketConn {
	pc, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	return pc
}

func TestLaSondaSaludaAlRouter(t *testing.T) {
	routerPriv, routerPub := newKey(t)
	phonePriv, phonePub := newKey(t)
	psk := make([]byte, 32)
	rand.Read(psk)
	port, stop := fakeRouter(t, routerPriv, phonePub, psk)
	defer stop()

	b64 := base64.StdEncoding.EncodeToString
	got, err := probeOver(listenUDP4(t), b64(phonePriv), b64(routerPub), b64(psk), fmt.Sprintf("127.0.0.1:%d", port), 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if got != ProbeReached {
		t.Fatalf("esperaba saludo, salió %d", got)
	}
}

// Con un teléfono que el router no conoce, el router calla: igual que si algo lo filtrara.
func TestSinRespuestaDelRouter(t *testing.T) {
	routerPriv, routerPub := newKey(t)
	phonePriv, _ := newKey(t)
	_, otherPub := newKey(t)
	psk := make([]byte, 32)
	port, stop := fakeRouter(t, routerPriv, otherPub, psk)
	defer stop()

	b64 := base64.StdEncoding.EncodeToString
	got, err := probeOver(listenUDP4(t), b64(phonePriv), b64(routerPub), b64(psk), fmt.Sprintf("127.0.0.1:%d", port), 1500*time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	if got != ProbeNoReply {
		t.Fatalf("esperaba sin respuesta, salió %d", got)
	}
}

// Un socket de IPv4 no puede mandar a una IPv6: como unos datos móviles sin IPv6.
func TestSinEsaFamiliaNoSaleNada(t *testing.T) {
	_, routerPub := newKey(t)
	phonePriv, _ := newKey(t)
	psk := make([]byte, 32)

	b64 := base64.StdEncoding.EncodeToString
	got, err := probeOver(listenUDP4(t), b64(phonePriv), b64(routerPub), b64(psk), "[2001:db8::1]:51820", 1500*time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	if got != ProbeCannotSend {
		t.Fatalf("esperaba que no saliera nada, salió %d", got)
	}
}

func TestLaSondaRechazaClavesMalas(t *testing.T) {
	if _, err := probeOver(listenUDP4(t), "x", "y", "z", "127.0.0.1:1", time.Second); err == nil {
		t.Fatal("esperaba error")
	}
}
