// Local-only encrypted integration fixture. Never used by the application.
package main

import (
 "crypto/ecdh"
 "crypto/ecdsa"
 "crypto/elliptic"
 "crypto/rand"
 "crypto/tls"
 "crypto/x509"
 "encoding/base64"
 "encoding/json"
 "fmt"
 "io"
 "math/big"
 "net"
 "os"
 "time"

 "github.com/xtls/xray-core/core"
 _ "github.com/xtls/xray-core/app/dispatcher"
 _ "github.com/xtls/xray-core/app/log"
 _ "github.com/xtls/xray-core/app/proxyman/inbound"
 _ "github.com/xtls/xray-core/app/proxyman/outbound"
 _ "github.com/xtls/xray-core/main/json"
 _ "github.com/xtls/xray-core/proxy/freedom"
 _ "github.com/xtls/xray-core/proxy/vless/inbound"
 _ "github.com/xtls/xray-core/transport/internet/reality"
 _ "github.com/xtls/xray-core/transport/internet/tcp"
 _ "github.com/xtls/xray-core/transport/internet/tagged/taggedimpl"
)

func must(err error) { if err != nil { panic(err) } }
func main(){
 if len(os.Args)!=2 { panic("provide local test profile output path") }
 private,err:=ecdsa.GenerateKey(elliptic.P256(),rand.Reader);must(err)
 template:=&x509.Certificate{SerialNumber:big.NewInt(1),DNSNames:[]string{"localhost"},NotBefore:time.Now().Add(-time.Hour),NotAfter:time.Now().Add(time.Hour),KeyUsage:x509.KeyUsageDigitalSignature,ExtKeyUsage:[]x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
 der,err:=x509.CreateCertificate(rand.Reader,template,template,&private.PublicKey,private);must(err)
 camouflage,err:=tls.Listen("tcp","127.0.0.1:0",&tls.Config{Certificates:[]tls.Certificate{{Certificate:[][]byte{der},PrivateKey:private}},MinVersion:tls.VersionTLS13,NextProtos:[]string{"h2","http/1.1"}});must(err);defer camouflage.Close()
 go func(){for{c,err:=camouflage.Accept();if err!=nil{return};go func(){defer c.Close();c.SetDeadline(time.Now().Add(30*time.Second));c.(*tls.Conn).Handshake();io.Copy(io.Discard,c)}()}}()
 echo,err:=net.Listen("tcp","127.0.0.1:0");must(err);defer echo.Close()
 go func(){for{c,err:=echo.Accept();if err!=nil{return};go func(){defer c.Close();c.SetDeadline(time.Now().Add(30*time.Second));io.Copy(c,c)}()}}()
 ports:=[]int{}
 for _,address:=range []string{"127.0.0.1:0","127.0.0.1:443"} {p,err:=net.ListenPacket("udp",address);must(err);defer p.Close();ports=append(ports,p.LocalAddr().(*net.UDPAddr).Port);go func(){b:=make([]byte,65536);for{n,addr,err:=p.ReadFrom(b);if err!=nil{return};p.WriteTo(b[:n],addr)}}()}
 key,err:=ecdh.X25519().GenerateKey(rand.Reader);must(err)
 id:="66d7ff88-566c-4dcc-aea0-95694b03c1ef";short:="0123456789abcdef"
 profile:=map[string]any{"protocol":"vless","host":"127.0.0.1","port":31888,"uuid":id,"security":"reality","public_key":base64.RawURLEncoding.EncodeToString(key.PublicKey().Bytes()),"short_id":short,"server_name":"localhost","flow":"xtls-rprx-vision","fingerprint":"chrome","tcp_echo_port":echo.Addr().(*net.TCPAddr).Port,"udp_echo_a":ports[0],"udp_echo_b":ports[1]}
 config:=map[string]any{"log":map[string]any{"loglevel":"none"},"inbounds":[]any{map[string]any{"listen":"127.0.0.1","port":31888,"protocol":"vless","settings":map[string]any{"decryption":"none","clients":[]any{map[string]any{"id":id,"flow":"xtls-rprx-vision"}}},"streamSettings":map[string]any{"network":"tcp","security":"reality","realitySettings":map[string]any{"dest":camouflage.Addr().String(),"serverNames":[]string{"localhost"},"privateKey":base64.RawURLEncoding.EncodeToString(key.Bytes()),"shortIds":[]string{short}}}}},"outbounds":[]any{map[string]any{"protocol":"freedom","settings":map[string]any{"finalRules":[]any{map[string]any{"action":"allow","ip":[]string{"127.0.0.1/32"}}}}}}}
 raw,err:=json.Marshal(config);must(err);server,err:=core.StartInstance("json",raw);must(err);defer server.Close()
 raw,err=json.Marshal(profile);must(err);must(os.WriteFile(os.Args[1],raw,0600))
 fmt.Println("READY local-only REALITY fixture at 127.0.0.1:31888; expires after 10 minutes")
 time.Sleep(10*time.Minute)
}
