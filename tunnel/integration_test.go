package tunnel

import (
	"context"
	"crypto/tls"
	"io"
	"net"
	"net/http"
	"os"
	"testing"
	"time"
)

// Requires a separately provisioned device profile. Never logs that profile.
// This tests encrypted transport plus an HTTPS request through the gateway.
func TestLiveRealityGateway(t *testing.T) {
	path := os.Getenv("FG_TEST_PROFILE")
	if path == "" {
		t.Skip("set FG_TEST_PROFILE to a private device profile JSON")
	}
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	c, err := ParseConfig(b)
	if err != nil {
		t.Fatal(err)
	}
	e, err := Start(c)
	if err != nil {
		t.Fatal(err)
	}
	defer e.Close()
	tr := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			host, p, err := net.SplitHostPort(addr)
			if err != nil {
				return nil, err
			}
			port, err := net.LookupPort("tcp", p)
			if err != nil {
				return nil, err
			}
			return e.DialTCP(host, port)
		},
		TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12},
	}
	defer tr.CloseIdleConnections()
	client := http.Client{Transport: tr, Timeout: 25 * time.Second}
	res, err := client.Get("https://telegram.org/")
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	_, err = io.Copy(io.Discard, io.LimitReader(res.Body, 1<<20))
	if err != nil {
		t.Fatal(err)
	}
	if res.StatusCode != 200 {
		t.Fatalf("unexpected gateway HTTPS response %d", res.StatusCode)
	}
}
