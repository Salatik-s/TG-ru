package tunnel

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"encoding/binary"
	"encoding/json"
	"encoding/pem"
	"io"
	"math/big"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/xtls/xray-core/core"
)

func wssConfig() Config {
	return Config{Protocol: "vless", Security: "tls", Transport: "ws", Host: "24projects.ru", ServerName: "24projects.ru", Port: 8443, Path: "/v1/transport", UUID: testConfig().UUID}
}

func TestWSSProfileConstraints(t *testing.T) {
	c := wssConfig()
	if err := c.Validate(); err != nil {
		t.Fatal(err)
	}
	for name, edit := range map[string]func(*Config){
		"host": func(c *Config) { c.Host = "localhost" }, "sni": func(c *Config) { c.ServerName = "localhost" },
		"port": func(c *Config) { c.Port = 443 }, "path": func(c *Config) { c.Path = "/other" },
		"flow": func(c *Config) { c.Flow = "xtls-rprx-vision" }, "fingerprint": func(c *Config) { c.Fingerprint = "chrome" },
		"key": func(c *Config) { c.PublicKey = testConfig().PublicKey }, "shortid": func(c *Config) { c.ShortID = "01" },
		"transport": func(c *Config) { c.Transport = "tcp" }, "uuid": func(c *Config) { c.UUID = "bad" },
	} {
		t.Run(name, func(t *testing.T) {
			v := c
			edit(&v)
			if v.Validate() == nil {
				t.Fatal("accepted unsafe WSS profile")
			}
		})
	}
	b, err := c.XrayJSON()
	if err != nil {
		t.Fatal(err)
	}
	var parsed map[string]any
	json.Unmarshal(b, &parsed)
	out := parsed["outbounds"].([]any)[0].(map[string]any)
	stream := out["streamSettings"].(map[string]any)
	tls := stream["tlsSettings"].(map[string]any)
	if tls["allowInsecure"] != false || tls["useSystemCA"] != true || stream["network"] != "ws" {
		t.Fatal("TLS trust/transport regression")
	}
	if len(parsed["inbounds"].([]any)) != 0 || len(parsed["outbounds"].([]any)) != 1 {
		t.Fatal("unexpected listeners or fallback")
	}
	e, err := Start(c)
	if err != nil {
		t.Fatal(err)
	}
	e.Close()
}

// The profile validator remains production-strict. Only this test rewrites the
// emitted JSON to loopback and adds an ephemeral CA, keeping verification on.
func TestLocalWSSTrustedTCPAndUDP(t *testing.T) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	ca := &x509.Certificate{SerialNumber: big.NewInt(71), IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour)}
	caDER, err := x509.CreateCertificate(rand.Reader, ca, ca, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	leaf := &x509.Certificate{SerialNumber: big.NewInt(72), DNSNames: []string{"24projects.ru"}, KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, NotBefore: ca.NotBefore, NotAfter: ca.NotAfter}
	leafDER, err := x509.CreateCertificate(rand.Reader, leaf, ca, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	keyDER, _ := x509.MarshalECPrivateKey(key)
	pemLines := func(kind string, b []byte) []string {
		return strings.Split(strings.TrimSpace(string(pem.EncodeToMemory(&pem.Block{Type: kind, Bytes: b}))), "\n")
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	listener.Close()
	c := wssConfig()
	serverJSON := map[string]any{"log": map[string]any{"loglevel": "none"}, "inbounds": []any{map[string]any{
		"listen": "127.0.0.1", "port": port, "protocol": "vless", "settings": map[string]any{"clients": []any{map[string]any{"id": c.UUID}}, "decryption": "none"},
		"streamSettings": map[string]any{"network": "ws", "security": "tls", "wsSettings": map[string]any{"path": c.Path}, "tlsSettings": map[string]any{"alpn": []string{"http/1.1"}, "certificates": []any{map[string]any{"certificate": pemLines("CERTIFICATE", leafDER), "key": pemLines("EC PRIVATE KEY", keyDER)}}}},
	}}, "outbounds": []any{map[string]any{"protocol": "freedom", "settings": map[string]any{"finalRules": []any{map[string]any{"action": "allow", "ip": []string{"127.0.0.1/32"}}}}}}}
	b, _ := json.Marshal(serverJSON)
	server, err := core.StartInstance("json", b)
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	echo, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()
	go func() {
		for {
			conn, err := echo.Accept()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				conn.SetDeadline(time.Now().Add(10 * time.Second))
				req := make([]byte, 40)
				if _, err := io.ReadFull(conn, req); err != nil {
					return
				}
				if binary.LittleEndian.Uint32(req[20:24]) != 0xbe7e8ef1 {
					return
				}
				response := make([]byte, 84)
				binary.LittleEndian.PutUint64(response[8:16], uint64(time.Now().Unix())<<32|1)
				binary.LittleEndian.PutUint32(response[16:20], 64)
				binary.LittleEndian.PutUint32(response[20:24], 0x05162463)
				copy(response[24:40], req[24:40])
				rand.Read(response[40:56])
				response[56] = 8
				binary.BigEndian.PutUint64(response[57:65], 323)
				binary.LittleEndian.PutUint32(response[68:72], 0x1cb5c415)
				binary.LittleEndian.PutUint32(response[72:76], 1)
				binary.LittleEndian.PutUint64(response[76:84], 0xd09d1d85de64fd85)
				conn.Write(response)
				io.Copy(conn, conn)
			}()
		}
	}()
	makeClient := func(trust bool, wrongSNI bool) *Engine {
		b, _ := c.XrayJSON()
		var cfg map[string]any
		json.Unmarshal(b, &cfg)
		out := cfg["outbounds"].([]any)[0].(map[string]any)
		next := out["settings"].(map[string]any)["vnext"].([]any)[0].(map[string]any)
		next["address"] = "127.0.0.1"
		next["port"] = port
		tc := out["streamSettings"].(map[string]any)["tlsSettings"].(map[string]any)
		if trust {
			tc["disableSystemRoot"] = true
			tc["certificates"] = []any{map[string]any{"usage": "verify", "certificate": pemLines("CERTIFICATE", caDER)}}
		}
		if wrongSNI {
			tc["serverName"] = "wrong.invalid"
		}
		b, _ = json.Marshal(cfg)
		instance, err := core.StartInstance("json", b)
		if err != nil {
			t.Fatal(err)
		}
		e := &Engine{instance: instance, streams: make(map[*stream]struct{}), datagrams: make(map[*packetStream]struct{}), leaseEnd: leaseClock() + 75*time.Second}
		e.leaseTimer = time.AfterFunc(time.Second, e.expire)
		return e
	}
	for _, invalid := range []struct {
		name          string
		trust, badSNI bool
	}{{"untrusted_ca", false, false}, {"wrong_sni", true, true}} {
		t.Run(invalid.name, func(t *testing.T) {
			e := makeClient(invalid.trust, invalid.badSNI)
			defer e.Close()
			conn, err := e.DialTCP("127.0.0.1", echo.Addr().(*net.TCPAddr).Port)
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			timer := time.AfterFunc(5*time.Second, func() { conn.Close() })
			defer timer.Stop()
			conn.Write(make([]byte, 40))
			if n, err := conn.Read(make([]byte, 84)); err == nil || n != 0 {
				t.Fatal("accepted untrusted TLS connection")
			}
		})
	}
	client := makeClient(true, false)
	defer client.Close()
	conn, err := client.DialTCP("127.0.0.1", echo.Addr().(*net.TCPAddr).Port)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	timer := time.AfterFunc(15*time.Second, func() { client.Close() })
	defer timer.Stop()
	req := make([]byte, 40)
	binary.LittleEndian.PutUint32(req[16:20], 20)
	binary.LittleEndian.PutUint32(req[20:24], 0xbe7e8ef1)
	rand.Read(req[24:40])
	if _, err = conn.Write(req); err != nil {
		t.Fatal(err)
	}
	res := make([]byte, 84)
	if _, err = io.ReadFull(conn, res); err != nil {
		t.Fatal(err)
	}
	if binary.LittleEndian.Uint32(res[20:24]) != 0x05162463 || !bytes.Equal(req[24:40], res[24:40]) {
		t.Fatal("reqPQ nonce/response corrupted")
	}
	payload := bytes.Repeat([]byte("verified WSS transport"), 8192)
	written := make(chan error, 1)
	go func() { _, err := conn.Write(payload); written <- err }()
	reply := make([]byte, len(payload))
	if _, err = io.ReadFull(conn, reply); err != nil {
		t.Fatal(err)
	}
	if err = <-written; err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(payload, reply) {
		t.Fatal("TCP stream corrupted")
	}
	for _, address := range []string{"127.0.0.1:0", "127.0.0.1:443"} {
		t.Run("UDP/"+address, func(t *testing.T) {
			udp, err := net.ListenPacket("udp", address)
			if err != nil {
				t.Fatal(err)
			}
			defer udp.Close()
			go func() {
				buf := make([]byte, 65536)
				for {
					n, peer, err := udp.ReadFrom(buf)
					if err != nil {
						return
					}
					udp.WriteTo(buf[:n], peer)
				}
			}()
			flow, err := client.DialUDP("127.0.0.1", udp.LocalAddr().(*net.UDPAddr).Port)
			if err != nil {
				t.Fatal(err)
			}
			defer flow.Close()
			for _, size := range []int{1, 53, 1200, MaxDatagramSize} {
				packet := bytes.Repeat([]byte{byte(size % 251)}, size)
				if _, err = flow.Write(packet); err != nil {
					t.Fatal(err)
				}
				buf := make([]byte, 65536)
				n, err := flow.Read(buf)
				if err != nil {
					t.Fatal(err)
				}
				if !bytes.Equal(packet, buf[:n]) {
					t.Fatal("datagram corrupted")
				}
			}
		})
	}
	client.Close()
	client.mu.Lock()
	remaining := len(client.streams) + len(client.datagrams)
	client.mu.Unlock()
	if remaining != 0 {
		t.Fatal("leaked flows")
	}
}
