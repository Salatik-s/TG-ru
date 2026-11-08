//go:build linux || android

package tunnel

import (
	"golang.org/x/sys/unix"
	"time"
)

// CLOCK_BOOTTIME includes Android deep sleep but is not affected by wall-clock
// edits. CLOCK_MONOTONIC (and Go timers) alone would pause during suspend.
func leaseClock() time.Duration {
	var ts unix.Timespec
	if unix.ClockGettime(unix.CLOCK_BOOTTIME, &ts) != nil {
		// Fail closed instead of granting a lease on an untrusted clock.
		return time.Duration(1<<63 - 1)
	}
	return time.Duration(ts.Sec)*time.Second + time.Duration(ts.Nsec)
}
