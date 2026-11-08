#include <dlfcn.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #x); exit(1); } } while (0)
#define LOAD(name, type) type name = (type)dlsym(lib, #name); CHECK(name)

typedef int (*start_fn)(char *);
typedef int (*ready_fn)(void);
typedef int (*renew_fn)(int);
typedef int (*dial_fn)(char *, int);
typedef void (*release_fn)(int);
typedef void (*stop_fn)(void);

int main(int argc, char **argv) {
    CHECK(argc == 2);
    void *lib = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!lib) { fprintf(stderr, "%s\n", dlerror()); return 1; }
    LOAD(FGStart, start_fn);
    LOAD(FGReady, ready_fn);
    LOAD(FGRefreshLease, renew_fn);
    LOAD(FGDial, dial_fn);
    LOAD(FGUDPDial, dial_fn);
    LOAD(FGRelease, release_fn);
    LOAD(FGStop, stop_fn);
    CHECK(FGReady() == 0);
    CHECK(FGStart("{}") == -1);
    CHECK(FGDial("127.0.0.1", 443) == -1);
    /* Deliberately non-production profile: only a closed loopback destination. */
    char profile[] = "{\"protocol\":\"vless\",\"host\":\"127.0.0.1\",\"port\":1,"
        "\"uuid\":\"66d7ff88-566c-4dcc-aea0-95694b03c1ef\",\"security\":\"reality\","
        "\"public_key\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\","
        "\"short_id\":\"0123456789abcdef\",\"server_name\":\"www.microsoft.com\","
        "\"flow\":\"xtls-rprx-vision\",\"fingerprint\":\"chrome\"}";
    CHECK(FGStart(profile) == 0);
    CHECK(FGReady() == 1);
    CHECK(FGRefreshLease(76) == -1);
    for (int udp = 0; udp < 2; udp++) {
        int fd = (udp ? FGUDPDial : FGDial)("127.0.0.1", 12345);
        CHECK(fd >= 0);
        CHECK((fcntl(fd, F_GETFL) & O_NONBLOCK) != 0);
        CHECK((fcntl(fd, F_GETFD) & FD_CLOEXEC) != 0);
        int type = 0;
        socklen_t size = sizeof(type);
        CHECK(getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &size) == 0);
        CHECK(type == (udp ? SOCK_DGRAM : SOCK_STREAM));
        FGRelease(fd);
        CHECK(fcntl(fd, F_GETFD) >= 0); /* Go must not close caller-owned fd. */
        CHECK(close(fd) == 0);
    }
    CHECK(FGRefreshLease(1) == 0);
    sleep(2);
    CHECK(FGReady() == 0);
    CHECK(FGRefreshLease(75) == -1);
    CHECK(FGDial("127.0.0.1", 443) == -1);
    FGStop();
    FGStop();
    puts("PASS Android FG ABI: load, TCP/UDP socket type and flags, ownership, lease expiry");
    /* Go shared runtimes must remain loaded until process termination. */
    return 0;
}
