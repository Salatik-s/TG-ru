package tunnel

import (
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"strings"

	"github.com/xtls/xray-core/common/uuid"
)

// Config is the device-specific profile returned by the private enrollment API.
// It deliberately accepts no inbound listener, alternate outbound or bypass rule.
type Config struct {
	Protocol    string `json:"protocol"`
	Host        string `json:"host"`
	Port        int    `json:"port"`
	UUID        string `json:"uuid"`
	Security    string `json:"security"`
	PublicKey   string `json:"public_key"`
	ShortID     string `json:"short_id"`
	ServerName  string `json:"server_name"`
	Flow        string `json:"flow"`
	Fingerprint string `json:"fingerprint"`
	Transport   string `json:"transport,omitempty"`
	Path        string `json:"path,omitempty"`
}

func ParseConfig(data []byte) (Config, error) {
	var c Config
	if len(data) > 16384 {
		return c, errors.New("profile too large")
	}
	if err := json.Unmarshal(data, &c); err != nil {
		return c, errors.New("invalid profile JSON")
	}
	return c, c.Validate()
}

func (c Config) Validate() error {
	if c.Protocol != "vless" {
		return errors.New("only VLESS is supported")
	}
	if c.Port < 1 || c.Port > 65535 {
		return errors.New("invalid gateway port")
	}
	if !validHost(c.Host) || !validHost(c.ServerName) {
		return errors.New("invalid gateway or server name")
	}
	parsed, err := uuid.ParseString(c.UUID)
	if err != nil || len(c.UUID) != 36 || parsed.String() != strings.ToLower(c.UUID) {
		return errors.New("invalid device UUID")
	}
	if c.Security == "tls" {
		if c.Transport != "ws" || c.Host != "24projects.ru" || c.ServerName != c.Host || c.Port != 8443 || c.Path != "/v1/transport" || c.Flow != "" || c.Fingerprint != "" || c.PublicKey != "" || c.ShortID != "" {
			return errors.New("invalid private WSS profile")
		}
		return nil
	}
	if c.Security != "reality" || c.Flow != "xtls-rprx-vision" || (c.Transport != "" && c.Transport != "tcp") || c.Path != "" {
		return errors.New("invalid REALITY profile")
	}
	key, err := base64.RawURLEncoding.DecodeString(c.PublicKey)
	if err != nil || len(key) != 32 {
		return errors.New("invalid REALITY public key")
	}
	id, err := hex.DecodeString(c.ShortID)
	if err != nil || len(id) < 1 || len(id) > 8 {
		return errors.New("invalid REALITY short ID")
	}
	if c.Fingerprint != "chrome" {
		return errors.New("unsupported TLS fingerprint")
	}
	return nil
}

func validHost(s string) bool {
	if len(s) == 0 || len(s) > 253 || strings.ContainsAny(s, " /\\\t\r\n\x00") {
		return false
	}
	if net.ParseIP(s) != nil {
		return true
	}
	for _, label := range strings.Split(s, ".") {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, r := range label {
			if !(r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '-') {
				return false
			}
		}
	}
	return true
}

func (c Config) XrayJSON() ([]byte, error) {
	if err := c.Validate(); err != nil {
		return nil, err
	}
	// Encoding/json escapes all values; no profile field is interpolated as JSON.
	// Pinned Xray otherwise rejects UDP/443 (including call relay traffic).
	// This is a client-only switch: outbound strips the suffix before sending
	// the Vision flow on the wire. Enrollment and server config stay unchanged.
	clientFlow := c.Flow + "-udp443"
	streamSettings := map[string]any{"network": "tcp", "security": "reality", "realitySettings": map[string]any{"serverName": c.ServerName, "fingerprint": c.Fingerprint, "publicKey": c.PublicKey, "shortId": c.ShortID}}
	if c.Security == "tls" {
		clientFlow = ""
		streamSettings = map[string]any{"network": "ws", "security": "tls", "tlsSettings": map[string]any{"serverName": c.ServerName, "allowInsecure": false, "useSystemCA": true, "alpn": []string{"http/1.1"}, "curvePreferences": []string{"X25519", "CurveP256"}}, "wsSettings": map[string]any{"path": c.Path}}
	}
	config := map[string]any{
		"log":      map[string]any{"loglevel": "none"},
		"inbounds": []any{},
		"outbounds": []any{map[string]any{
			"tag": "familygram-private", "protocol": "vless",
			"settings":       map[string]any{"vnext": []any{map[string]any{"address": c.Host, "port": c.Port, "users": []any{map[string]any{"id": c.UUID, "encryption": "none", "flow": clientFlow}}}}},
			"streamSettings": streamSettings,
		}},
	}
	b, err := json.Marshal(config)
	if err != nil {
		return nil, fmt.Errorf("encode transport config: %w", err)
	}
	return b, nil
}
