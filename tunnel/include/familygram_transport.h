#ifndef FAMILYGRAM_TRANSPORT_H
#define FAMILYGRAM_TRANSPORT_H
#ifdef __cplusplus
extern "C" {
#endif
// Profile is borrowed for the duration of the call. Returns 0 or -1.
int FGStart(char *profile);
// True means configured, not authenticated or reachable.
int FGReady(void);
// Renew only after successful authenticated profile refresh. 1..75 seconds.
// Cannot resurrect an expired lease: FGStart is required after re-enrollment.
int FGRefreshLease(int seconds);
// Returns a connected, nonblocking, close-on-exec AF_UNIX stream fd or -1.
// Caller owns fd; use normal read/write/send/recv/epoll and close exactly once.
// Do NOT call connect(), TCP_NODELAY, or IP-specific setsockopt() on this fd.
// The actual remote handshake is asynchronous; failure is reported by EOF.
int FGDial(char *host, int port);
// Fixed-destination UDP: host MUST be a literal IP. Returns connected
// nonblocking AF_UNIX SOCK_DGRAM fd. Each send/recv is one packet. No sendto()
// with IP sockaddr is supported. No WebRTC integration is implied by this ABI.
// Payload must contain 1..7526 bytes (pinned core's XUDP buffer headroom).
int FGUDPDial(char *host, int port);
// Call BEFORE native close(fd). Required for UDP; recommended for TCP.
// Cancels Go-side resources but does not close the caller-owned fd. Do not call
// after close(fd), because the descriptor number could already be reused.
void FGRelease(int fd);
// Cancels Go-side flows. TCP caller fds become EOF, not closed. UDP has no EOF;
// native owners must also cancel their UDP operations and close their fds.
void FGStop(void);
#ifdef __cplusplus
}
#endif
#endif
