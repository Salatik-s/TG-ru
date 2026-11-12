#pragma once
#include <sys/socket.h>
#include <sys/uio.h>
#include <netdb.h>
#include <stddef.h>

struct FGTransport {
    int (*ready)();
    int (*tcp)(char *, int);
    int (*udp)(char *, int);
    void (*release)(int);
};
bool FGCInitialize(FGTransport api);
// Numeric-only process counters; never endpoint addresses, keys or packet contents.
void FGCCounters(long long *values);
extern "C" {
__attribute__((visibility("default"))) void FGCallsStop();
__attribute__((visibility("default"))) int FGCallsResume();
int FGC_socket(int domain, int type, int protocol);
int FGC_connect(int fd, const sockaddr *address, socklen_t length);
int FGC_bind(int fd, const sockaddr *address, socklen_t length);
int FGC_getsockname(int fd, sockaddr *address, socklen_t *length);
int FGC_getpeername(int fd, sockaddr *address, socklen_t *length);
int FGC_setsockopt(int fd, int level, int option, const void *value, socklen_t length);
int FGC_getsockopt(int fd, int level, int option, void *value, socklen_t *length);
int FGC_listen(int fd, int backlog);
int FGC_accept(int fd, sockaddr *address, socklen_t *length);
int FGC_shutdown(int fd, int how);
int FGC_close(int fd);
ssize_t FGC_send(int fd, const void *data, size_t length, int flags);
ssize_t FGC_sendto(int fd, const void *data, size_t length, int flags, const sockaddr *address, socklen_t addrlen);
ssize_t FGC_recv(int fd, void *data, size_t length, int flags);
ssize_t FGC_recvfrom(int fd, void *data, size_t length, int flags, sockaddr *address, socklen_t *addrlen);
ssize_t FGC_recvmsg(int fd, msghdr *message, int flags);
ssize_t FGC_read(int fd, void *data, size_t length);
ssize_t FGC_write(int fd, const void *data, size_t length);
ssize_t FGC_readv(int fd, const iovec *iov, int count);
ssize_t FGC_writev(int fd, const iovec *iov, int count);
int FGC_getaddrinfo(const char *node, const char *service, const addrinfo *hints, addrinfo **result);
void *FGC_dlsym(void *handle, const char *name);
int FGC_bind_network(unsigned long long network, int fd);
long fg_blocked_syscall();
long fg_syscall_gate(long number, ...);
extern void *fg_real_syscall;
}
