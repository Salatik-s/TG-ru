package tunnel

import (
	"context"
	"errors"
	"net"
	"sync"
	"time"

	"github.com/xtls/xray-core/common/buf"
	"github.com/xtls/xray-core/core"
)

// Pinned Xray XUDP requires 666 bytes of headroom and drops empty packets.
const MaxDatagramSize = buf.Size - 666

// DialUDP returns a connected datagram abstraction for one literal IP:port.
// Each Write is exactly one datagram. Read never returns data from other peers.
// Domain destinations and arbitrary sendto addressing are deliberately absent.
func (e *Engine) DialUDP(host string, port int) (net.Conn, error) {
	ip := net.ParseIP(host)
	if ip == nil || port < 1 || port > 65535 {
		return nil, errors.New("UDP destination must be an IP and valid port")
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.instance == nil || leaseClock() >= e.leaseEnd {
		return nil, errors.New("transport is stopped or lease expired")
	}
	ctx, cancel := context.WithCancel(context.Background())
	c, err := core.DialUDP(ctx, e.instance)
	if err != nil {
		cancel()
		return nil, errors.New("UDP dispatch failed")
	}
	s := &packetStream{conn: c, peer: &net.UDPAddr{IP: ip, Port: port}, owner: e, cancel: cancel}
	e.datagrams[s] = struct{}{}
	return s, nil
}

type packetStream struct {
	conn   net.PacketConn
	peer   *net.UDPAddr
	owner  *Engine
	cancel context.CancelFunc
	once   sync.Once
}

func (s *packetStream) Read(p []byte) (int, error) {
	for {
		if !s.owner.Ready() {
			s.Close()
			return 0, errors.New("transport lease expired")
		}
		n, from, err := s.conn.ReadFrom(p)
		if !s.owner.Ready() {
			s.Close()
			return 0, errors.New("transport lease expired")
		}
		if err != nil {
			return n, err
		}
		addr, ok := from.(*net.UDPAddr)
		if ok && addr.Port == s.peer.Port && addr.IP.Equal(s.peer.IP) {
			return n, nil
		}
	}
}
func (s *packetStream) Write(p []byte) (int, error) {
	if !s.owner.Ready() {
		s.Close()
		return 0, errors.New("transport lease expired")
	}
	// Avoid the pinned core's silent XUDP drops and WriteTo truncation.
	if len(p) == 0 || len(p) > MaxDatagramSize {
		return 0, errors.New("UDP datagram must contain 1..7526 bytes")
	}
	return s.conn.WriteTo(p, s.peer)
}
func (s *packetStream) Close() error {
	var err error
	s.once.Do(func() {
		s.cancel()
		err = s.conn.Close()
		s.owner.mu.Lock()
		delete(s.owner.datagrams, s)
		s.owner.mu.Unlock()
	})
	return err
}
func (s *packetStream) LocalAddr() net.Addr                { return s.conn.LocalAddr() }
func (s *packetStream) RemoteAddr() net.Addr               { return s.peer }
func (s *packetStream) SetDeadline(t time.Time) error      { return s.conn.SetDeadline(t) }
func (s *packetStream) SetReadDeadline(t time.Time) error  { return s.conn.SetReadDeadline(t) }
func (s *packetStream) SetWriteDeadline(t time.Time) error { return s.conn.SetWriteDeadline(t) }
