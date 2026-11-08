package tunnel

import (
	"context"
	"errors"
	"io"
	"net"
	"sync"
	"time"

	_ "github.com/xtls/xray-core/app/dispatcher"
	_ "github.com/xtls/xray-core/app/log"
	_ "github.com/xtls/xray-core/app/policy"
	_ "github.com/xtls/xray-core/app/proxyman/inbound"
	_ "github.com/xtls/xray-core/app/proxyman/outbound"
	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/json"
	_ "github.com/xtls/xray-core/proxy/vless/outbound"
	_ "github.com/xtls/xray-core/transport/internet/reality"
	_ "github.com/xtls/xray-core/transport/internet/tagged/taggedimpl"
	_ "github.com/xtls/xray-core/transport/internet/tcp"
	_ "github.com/xtls/xray-core/transport/internet/tls"
	_ "github.com/xtls/xray-core/transport/internet/websocket"
)

type Engine struct {
	mu         sync.Mutex
	instance   *core.Instance
	streams    map[*stream]struct{}
	datagrams  map[*packetStream]struct{}
	leaseEnd   time.Duration
	leaseTimer *time.Timer
}

func Start(c Config) (*Engine, error) {
	b, err := c.XrayJSON()
	if err != nil {
		return nil, err
	}
	i, err := core.StartInstance("json", b)
	if err != nil {
		return nil, errors.New("could not start private transport")
	}
	now := leaseClock()
	if now > time.Duration(1<<63-1)-75*time.Second {
		i.Close()
		return nil, errors.New("lease clock unavailable")
	}
	e := &Engine{instance: i, streams: make(map[*stream]struct{}), datagrams: make(map[*packetStream]struct{}), leaseEnd: now + 75*time.Second}
	e.leaseTimer = time.AfterFunc(time.Second, e.expire)
	return e, nil
}

// RefreshLease is called only after successful authenticated backend refresh.
// Android uses CLOCK_BOOTTIME; wall-clock edits and sleep cannot renew it.
func (e *Engine) RefreshLease(seconds int) error {
	if seconds < 1 || seconds > 75 {
		return errors.New("invalid lease duration")
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.instance == nil || leaseClock() >= e.leaseEnd {
		return errors.New("transport lease expired")
	}
	e.leaseEnd = leaseClock() + time.Duration(seconds)*time.Second
	e.leaseTimer.Reset(time.Second)
	return nil
}

func (e *Engine) Ready() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.instance != nil && leaseClock() < e.leaseEnd
}

func (e *Engine) expire() {
	e.mu.Lock()
	if e.instance == nil {
		e.mu.Unlock()
		return
	}
	if leaseClock() < e.leaseEnd {
		e.leaseTimer.Reset(time.Second)
		e.mu.Unlock()
		return
	}
	i, list := e.detachLocked()
	e.mu.Unlock()
	for _, s := range list {
		s.Close()
	}
	if i != nil {
		i.Close()
	}
}

// DialTCP dispatches asynchronously. Success means the stream was created, not
// that the gateway authenticated: handshake failures reach the caller as EOF.
func (e *Engine) DialTCP(host string, port int) (net.Conn, error) {
	if !validHost(host) || port < 1 || port > 65535 {
		return nil, errors.New("invalid TCP destination")
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.instance == nil || leaseClock() >= e.leaseEnd {
		return nil, errors.New("transport is stopped or lease expired")
	}
	ctx, cancel := context.WithCancel(context.Background())
	c, err := core.Dial(ctx, e.instance, xnet.TCPDestination(xnet.ParseAddress(host), xnet.Port(port)))
	if err != nil {
		cancel()
		return nil, errors.New("transport dispatch failed")
	}
	s := &stream{Conn: c, owner: e, cancel: cancel}
	e.streams[s] = struct{}{}
	return s, nil
}

func (e *Engine) Close() error {
	e.mu.Lock()
	i, list := e.detachLocked()
	e.mu.Unlock()
	for _, s := range list {
		s.Close()
	}
	if i != nil {
		return i.Close()
	}
	return nil
}

func (e *Engine) detachLocked() (*core.Instance, []io.Closer) {
	i := e.instance
	e.instance = nil
	if e.leaseTimer != nil {
		e.leaseTimer.Stop()
	}
	list := make([]io.Closer, 0, len(e.streams)+len(e.datagrams))
	for s := range e.streams {
		list = append(list, s)
	}
	for s := range e.datagrams {
		list = append(list, s)
	}
	return i, list
}

type stream struct {
	net.Conn
	owner  *Engine
	cancel context.CancelFunc
	once   sync.Once
}

func (s *stream) Read(p []byte) (int, error) {
	if !s.owner.Ready() {
		s.Close()
		return 0, errors.New("transport lease expired")
	}
	n, err := s.Conn.Read(p)
	if !s.owner.Ready() {
		s.Close()
		return 0, errors.New("transport lease expired")
	}
	return n, err
}

func (s *stream) Write(p []byte) (int, error) {
	if !s.owner.Ready() {
		s.Close()
		return 0, errors.New("transport lease expired")
	}
	return s.Conn.Write(p)
}

func (s *stream) Close() error {
	var err error
	s.once.Do(func() {
		s.cancel()
		err = s.Conn.Close()
		s.owner.mu.Lock()
		delete(s.owner.streams, s)
		s.owner.mu.Unlock()
	})
	return err
}
