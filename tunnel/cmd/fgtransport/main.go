//go:build linux || android

package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"familygram/tunnel"
	"io"
	"net"
	"os"
	"sync"
	"syscall"
)

var state struct {
	sync.Mutex
	engine *tunnel.Engine
}

type bridgeEntry struct{ close func() }

var bridges = struct {
	sync.Mutex
	items map[int]*bridgeEntry
}{items: make(map[int]*bridgeEntry)}

//export FGRelease
func FGRelease(fd C.int) {
	bridges.Lock()
	entry := bridges.items[int(fd)]
	delete(bridges.items, int(fd))
	bridges.Unlock()
	if entry != nil {
		entry.close()
	}
}

//export FGStart
func FGStart(profile *C.char) C.int {
	if profile == nil {
		return -1
	}
	c, err := tunnel.ParseConfig([]byte(C.GoString(profile)))
	if err != nil {
		return -1
	}
	state.Lock()
	defer state.Unlock()
	e, err := tunnel.Start(c)
	if err != nil {
		return -1
	}
	old := state.engine
	state.engine = e
	if old != nil {
		old.Close()
	}
	return 0
}

//export FGReady
func FGReady() C.int {
	state.Lock()
	defer state.Unlock()
	if state.engine == nil || !state.engine.Ready() {
		return 0
	}
	return 1
}

//export FGRefreshLease
func FGRefreshLease(seconds C.int) C.int {
	state.Lock()
	defer state.Unlock()
	if state.engine == nil || state.engine.RefreshLease(int(seconds)) != nil {
		return -1
	}
	return 0
}

//export FGStop
func FGStop() {
	state.Lock()
	defer state.Unlock()
	if state.engine != nil {
		state.engine.Close()
		state.engine = nil
	}
}

//export FGDial
func FGDial(host *C.char, port C.int) C.int {
	if host == nil {
		return -1
	}
	state.Lock()
	defer state.Unlock()
	if state.engine == nil {
		return -1
	}
	remote, err := state.engine.DialTCP(C.GoString(host), int(port))
	if err != nil {
		return -1
	}
	return bridge(remote, false)
}

//export FGUDPDial
func FGUDPDial(host *C.char, port C.int) C.int {
	if host == nil {
		return -1
	}
	state.Lock()
	defer state.Unlock()
	if state.engine == nil {
		return -1
	}
	remote, err := state.engine.DialUDP(C.GoString(host), int(port))
	if err != nil {
		return -1
	}
	return bridge(remote, true)
}

func bridge(remote net.Conn, datagrams bool) C.int {
	typeID := syscall.SOCK_STREAM
	if datagrams {
		typeID = syscall.SOCK_DGRAM
	}
	pair, err := syscall.Socketpair(syscall.AF_UNIX, typeID|syscall.SOCK_CLOEXEC, 0)
	if err != nil {
		remote.Close()
		return -1
	}
	f := os.NewFile(uintptr(pair[1]), "familygram-transport")
	local, err := net.FileConn(f)
	f.Close() // FileConn duplicates the descriptor. Go now owns only its duplicate.
	if err != nil {
		syscall.Close(pair[0])
		remote.Close()
		return -1
	}
	if err = syscall.SetNonblock(pair[0], true); err != nil {
		syscall.Close(pair[0])
		local.Close()
		remote.Close()
		return -1
	}
	var once sync.Once
	entry := &bridgeEntry{}
	closeBoth := func() {
		once.Do(func() {
			local.Close()
			remote.Close()
			bridges.Lock()
			if bridges.items[pair[0]] == entry {
				delete(bridges.items, pair[0])
			}
			bridges.Unlock()
		})
	}
	entry.close = closeBoth
	bridges.Lock()
	bridges.items[pair[0]] = entry
	bridges.Unlock()
	pump := func(dst, src net.Conn) {
		defer closeBoth()
		if !datagrams {
			io.Copy(dst, src)
			return
		}
		buf := make([]byte, 65536)
		for {
			n, err := src.Read(buf)
			if err != nil {
				return
			}
			// One read and one write per datagram, including empty datagrams.
			written, err := dst.Write(buf[:n])
			if err != nil || written != n {
				return
			}
		}
	}
	go pump(remote, local)
	go pump(local, remote)
	// C++ exclusively owns pair[0]. Call FGRelease before closing it exactly
	// once. A TCP close also wakes the first pump; UDP has no equivalent EOF.
	return C.int(pair[0])
}

func main() {}
