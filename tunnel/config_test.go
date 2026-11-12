package tunnel

import (
	"context"
	"encoding/json"
	"net"
	"testing"
	"time"
)

func testConfig() Config {
	return Config{Protocol: "vless", Host: "127.0.0.1", Port: 18443, UUID: "66d7ff88-566c-4dcc-aea0-95694b03c1ef", Security: "reality", PublicKey: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", ShortID: "0123456789abcdef", ServerName: "www.microsoft.com", Flow: "xtls-rprx-vision", Fingerprint: "chrome"}
}

func TestLeaseExpiresAndClosesExistingStreams(t *testing.T) {
	e, err := Start(testConfig())
	if err != nil {
		t.Fatal(err)
	}
	defer e.Close()
	a, b := net.Pipe()
	defer b.Close()
	_, cancel := context.WithCancel(context.Background())
	s := &stream{Conn: a, owner: e, cancel: cancel}
	e.mu.Lock()
	e.streams[s] = struct{}{}
	e.mu.Unlock()
	if err = e.RefreshLease(1); err != nil {
		t.Fatal(err)
	}
	b.SetReadDeadline(time.Now().Add(4 * time.Second))
	_, err = b.Read(make([]byte, 1))
	if err == nil {
		t.Fatal("expired stream remained open")
	}
	if timeout, ok := err.(net.Error); ok && timeout.Timeout() {
		t.Fatal("lease did not close active streams")
	}
	if e.Ready() {
		t.Fatal("expired lease still ready")
	}
	if e.RefreshLease(75) == nil {
		t.Fatal("renewal resurrected expired lease")
	}
	if _, err = e.DialTCP("127.0.0.1", 443); err == nil {
		t.Fatal("expired lease accepted dial")
	}
}

func TestProfileRejectsUnsafeOrUnsupportedValues(t *testing.T) {
	tests := []struct {
		name string
		edit func(*Config)
	}{
		{"direct", func(c *Config) { c.Protocol = "freedom" }},
		{"plain", func(c *Config) { c.Security = "none" }},
		{"flow", func(c *Config) { c.Flow = "" }},
		{"client_only_flow", func(c *Config) { c.Flow = "xtls-rprx-vision-udp443" }},
		{"port", func(c *Config) { c.Port = 65536 }},
		{"host", func(c *Config) { c.Host = "example.com/path" }},
		{"uuid", func(c *Config) { c.UUID = "not-a-uuid" }},
		{"key", func(c *Config) { c.PublicKey = "short" }},
		{"short_id", func(c *Config) { c.ShortID = "xyz" }},
		{"sni", func(c *Config) { c.ServerName = "evil\nheader" }},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			c := testConfig()
			test.edit(&c)
			if c.Validate() == nil {
				t.Fatal("accepted invalid profile")
			}
		})
	}
}

func TestConfigHasOneEncryptedOutboundAndNoListeners(t *testing.T) {
	b, err := testConfig().XrayJSON()
	if err != nil {
		t.Fatal(err)
	}
	var c struct {
		Inbounds  []any `json:"inbounds"`
		Outbounds []struct {
			Protocol string `json:"protocol"`
			Stream   struct {
				Security string `json:"security"`
			} `json:"streamSettings"`
		} `json:"outbounds"`
	}
	if err = json.Unmarshal(b, &c); err != nil {
		t.Fatal(err)
	}
	if len(c.Inbounds) != 0 || len(c.Outbounds) != 1 || c.Outbounds[0].Protocol != "vless" || c.Outbounds[0].Stream.Security != "reality" {
		t.Fatal("unexpected routing configuration")
	}
}

func TestEngineLifecycle(t *testing.T) {
	e, err := Start(testConfig())
	if err != nil {
		t.Fatal(err)
	}
	if _, err = e.DialTCP("bad/destination", 443); err == nil {
		t.Fatal("accepted invalid destination")
	}
	if err = e.Close(); err != nil {
		t.Fatal(err)
	}
	if err = e.Close(); err != nil {
		t.Fatal(err)
	}
	if _, err = e.DialTCP("127.0.0.1", 443); err == nil {
		t.Fatal("dial after close")
	}
}

func TestStoreWSSProfile(t *testing.T) {
	c := Config{Protocol: "vless", Host: "tg.24projects.ru", Port: 443, UUID: testConfig().UUID,
		Security: "tls", ServerName: "tg.24projects.ru", Transport: "ws", Path: "/v1/store-transport"}
	b, err := c.XrayJSON()
	if err != nil {
		t.Fatal(err)
	}
	if !json.Valid(b) {
		t.Fatal("invalid generated Xray config")
	}
	for _, change := range []func(*Config){
		func(c *Config) { c.Port = 28445 },
		func(c *Config) { c.Path = "/v1/transport" },
		func(c *Config) { c.ServerName = "example.org" },
	} {
		invalid := c
		change(&invalid)
		if invalid.Validate() == nil {
			t.Fatal("accepted an unexpected Store endpoint")
		}
	}
}
