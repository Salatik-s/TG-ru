//go:build !linux && !android

package tunnel

import "time"

var leaseEpoch = time.Now()

func leaseClock() time.Duration { return time.Since(leaseEpoch) }
