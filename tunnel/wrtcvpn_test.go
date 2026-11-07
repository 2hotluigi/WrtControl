package wrtcvpn

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"testing"
	"time"

	"golang.org/x/crypto/curve25519"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

func newKey(t *testing.T) (priv, pub []byte) {
	priv = make([]byte, 32)
	if _, err := rand.Read(priv); err != nil {
		t.Fatal(err)
	}
	priv[0] &= 248
	priv[31] = (priv[31] & 127) | 64
	pub, err := curve25519.X25519(priv, curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}
	return priv, pub
}

// Un "router" WireGuard en memoria con una web en 10.77.0.1:80, como el de OpenWrt.
func fakeRouter(t *testing.T, routerPriv, phonePub, psk []byte) (port int, stop func()) {
	tun, tnet, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.77.0.1")}, nil, 1280)
	if err != nil {
		t.Fatal(err)
	}
	dev := device.NewDevice(tun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "router: "))
	cfg := fmt.Sprintf("private_key=%s\nlisten_port=0\npublic_key=%s\npreshared_key=%s\nallowed_ip=10.77.0.2/32\n",
		hex.EncodeToString(routerPriv), hex.EncodeToString(phonePub), hex.EncodeToString(psk))
	if err := dev.IpcSet(cfg); err != nil {
		t.Fatal(err)
	}
	if err := dev.Up(); err != nil {
		t.Fatal(err)
	}
	state, _ := dev.IpcGet()
	for _, line := range strings.Split(state, "\n") {
		if v, ok := strings.CutPrefix(line, "listen_port="); ok {
			fmt.Sscan(v, &port)
		}
	}
	ln, err := tnet.ListenTCPAddrPort(netip.MustParseAddrPort("10.77.0.1:80"))
	if err != nil {
		t.Fatal(err)
	}
	srv := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprintf(w, "router ok %s", r.URL.Path)
	})}
	go srv.Serve(ln)
	return port, func() { srv.Close(); dev.Close() }
}

func TestHTTPAtraviesaElTunel(t *testing.T) {
	routerPriv, routerPub := newKey(t)
	phonePriv, phonePub := newKey(t)
	psk := make([]byte, 32)
	rand.Read(psk)
	port, stop := fakeRouter(t, routerPriv, phonePub, psk)
	defer stop()

	b64 := base64.StdEncoding.EncodeToString
	tun, err := Start(b64(phonePriv), b64(routerPub), b64(psk), fmt.Sprintf("127.0.0.1:%d", port), "10.77.0.2", "10.77.0.1:80", 0)
	if err != nil {
		t.Fatal(err)
	}
	defer tun.Stop()

	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Get(fmt.Sprintf("http://127.0.0.1:%d/ubus", tun.LocalPort()))
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if string(body) != "router ok /ubus" {
		t.Fatalf("respuesta inesperada: %q", body)
	}
	if tun.LastHandshake() == 0 {
		t.Fatal("no hubo saludo")
	}
}

// Un cliente que cierra su envío al terminar la petición (como `nc` o HTTP/1.0) sigue
// recibiendo la respuesta.
func TestElCierreAMediasNoCortaLaRespuesta(t *testing.T) {
	routerPriv, routerPub := newKey(t)
	phonePriv, phonePub := newKey(t)
	psk := make([]byte, 32)
	port, stop := fakeRouter(t, routerPriv, phonePub, psk)
	defer stop()

	b64 := base64.StdEncoding.EncodeToString
	tun, err := Start(b64(phonePriv), b64(routerPub), b64(psk), fmt.Sprintf("127.0.0.1:%d", port), "10.77.0.2", "10.77.0.1:80", 0)
	if err != nil {
		t.Fatal(err)
	}
	defer tun.Stop()
	c, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", tun.LocalPort()))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(10 * time.Second))
	fmt.Fprint(c, "GET /hola HTTP/1.0\r\n\r\n")
	c.(*net.TCPConn).CloseWrite()
	body, _ := io.ReadAll(c)
	if !strings.Contains(string(body), "router ok /hola") {
		t.Fatalf("respuesta inesperada: %q", body)
	}
}

func TestOtraClaveDelRouterNoConecta(t *testing.T) {
	routerPriv, _ := newKey(t)
	_, otherPub := newKey(t)
	phonePriv, phonePub := newKey(t)
	psk := make([]byte, 32)
	port, stop := fakeRouter(t, routerPriv, phonePub, psk)
	defer stop()

	b64 := base64.StdEncoding.EncodeToString
	tun, err := Start(b64(phonePriv), b64(otherPub), b64(psk), fmt.Sprintf("127.0.0.1:%d", port), "10.77.0.2", "10.77.0.1:80", 0)
	if err != nil {
		t.Fatal(err)
	}
	defer tun.Stop()
	client := &http.Client{Timeout: 3 * time.Second}
	if _, err := client.Get(fmt.Sprintf("http://127.0.0.1:%d/", tun.LocalPort())); err == nil {
		t.Fatal("con otra clave de router no debería llegar")
	}
	if tun.LastHandshake() != 0 {
		t.Fatal("no debería haber saludo")
	}
}

func TestClavesNoValidas(t *testing.T) {
	if _, err := Start("x", "y", "z", "127.0.0.1:1", "10.77.0.2", "10.77.0.1:80", 0); err == nil {
		t.Fatal("tenía que fallar")
	}
}
