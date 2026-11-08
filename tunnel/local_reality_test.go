package tunnel

import (
	"bytes"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"io"
	"math/big"
	"net"
	"testing"
	"time"

	"github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/proxy/freedom"
	_ "github.com/xtls/xray-core/proxy/vless/inbound"
)

// A real VLESS/REALITY client and server exchange bytes over loopback. The
// TLS camouflage target is local too; this test needs no external network.
func TestLocalRealityEncryptedRoundTrip(t *testing.T) {
	private, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{SerialNumber: big.NewInt(1), DNSNames: []string{"localhost"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &private.PublicKey, private)
	if err != nil {
		t.Fatal(err)
	}
	target, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: private}}, MinVersion: tls.VersionTLS13, NextProtos: []string{"h2", "http/1.1"}})
	if err != nil {
		t.Fatal(err)
	}
	defer target.Close()
	go func() {
		for {
			c, err := target.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				c.SetDeadline(time.Now().Add(10 * time.Second))
				c.(*tls.Conn).Handshake()
				io.Copy(io.Discard, c)
			}()
		}
	}()
	echo, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()
	go func() {
		for {
			c, err := echo.Accept()
			if err != nil {
				return
			}
			go func() { defer c.Close(); c.SetDeadline(time.Now().Add(10 * time.Second)); io.Copy(c, c) }()
		}
	}()
	reservation, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := reservation.Addr().(*net.TCPAddr).Port
	reservation.Close()
	key, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	c := testConfig()
	c.Port = port
	c.ServerName = "localhost"
	c.PublicKey = base64.RawURLEncoding.EncodeToString(key.PublicKey().Bytes())
	serverConfig := map[string]any{
		"log":      map[string]any{"loglevel": "none"},
		"inbounds": []any{map[string]any{"listen": "127.0.0.1", "port": port, "protocol": "vless", "settings": map[string]any{"clients": []any{map[string]any{"id": c.UUID, "flow": c.Flow}}, "decryption": "none"}, "streamSettings": map[string]any{"network": "tcp", "security": "reality", "realitySettings": map[string]any{"dest": target.Addr().String(), "serverNames": []string{"localhost"}, "privateKey": base64.RawURLEncoding.EncodeToString(key.Bytes()), "shortIds": []string{c.ShortID}}}}},
		// Test-only loopback allowance: modern Xray blocks private destinations
		// by default. Production never receives this server-side test config.
		"outbounds": []any{map[string]any{"protocol": "freedom", "settings": map[string]any{"finalRules": []any{map[string]any{"action": "allow", "ip": []string{"127.0.0.1/32"}}}}}},
	}
	b, err := json.Marshal(serverConfig)
	if err != nil {
		t.Fatal(err)
	}
	server, err := core.StartInstance("json", b)
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	client, err := Start(c)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	conn, err := client.DialTCP("127.0.0.1", echo.Addr().(*net.TCPAddr).Port)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	// Xray's virtual connections do not implement useful socket deadlines.
	timer := time.AfterFunc(15*time.Second, func() { conn.Close() })
	defer timer.Stop()
	payload := bytes.Repeat([]byte("Familygram encrypted transport\n"), 4096)
	writeDone := make(chan error, 1)
	go func() { _, err := conn.Write(payload); writeDone <- err }()
	reply := make([]byte, len(payload))
	if _, err = io.ReadFull(conn, reply); err != nil {
		t.Fatalf("encrypted round trip: %v", err)
	}
	if err = <-writeDone; err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(payload, reply) {
		t.Fatal("transport corrupted payload")
	}
	for _, listenAddress := range []string{"127.0.0.1:0", "127.0.0.1:443"} {
		t.Run("UDP/"+listenAddress, func(t *testing.T) {
			udpEcho, err := net.ListenPacket("udp", listenAddress)
			if err != nil {
				t.Fatal(err)
			}
			defer udpEcho.Close()
			go func() {
				buf := make([]byte, 65536)
				for {
					n, addr, err := udpEcho.ReadFrom(buf)
					if err != nil {
						return
					}
					if _, err = udpEcho.WriteTo(buf[:n], addr); err != nil {
						return
					}
				}
			}()
			udp, err := client.DialUDP("127.0.0.1", udpEcho.LocalAddr().(*net.UDPAddr).Port)
			if err != nil {
				t.Fatal(err)
			}
			defer udp.Close()
			udpTimer := time.AfterFunc(15*time.Second, func() { udp.Close() })
			defer udpTimer.Stop()
			for _, size := range []int{1, 53, 1200, MaxDatagramSize} {
				packet := bytes.Repeat([]byte{byte(size % 251)}, size)
				if _, err = udp.Write(packet); err != nil {
					t.Fatal(err)
				}
				buf := make([]byte, 65536)
				n, err := udp.Read(buf)
				if err != nil {
					t.Fatalf("encrypted UDP size %d: %v", size, err)
				}
				if !bytes.Equal(packet, buf[:n]) {
					t.Fatalf("UDP size %d: packet boundary or content corrupted", size)
				}
			}
			if _, err = udp.Write(nil); err == nil {
				t.Fatal("accepted empty UDP packet")
			}
			if _, err = udp.Write(make([]byte, MaxDatagramSize+1)); err == nil {
				t.Fatal("accepted oversized UDP packet")
			}
		})
	}
	if err = client.Close(); err != nil {
		t.Fatal(err)
	}
	client.mu.Lock()
	remaining := len(client.streams) + len(client.datagrams)
	client.mu.Unlock()
	if remaining != 0 {
		t.Fatal("close leaked tracked streams")
	}
}
