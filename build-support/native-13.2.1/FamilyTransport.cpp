#include "FamilyTransport.h"
#include <atomic>
#include <cerrno>
#include <dlfcn.h>
#include <mutex>

namespace {
using Start = int (*)(char *);
using Ready = int (*)();
using Dial = int (*)(char *, int);
using Stop = void (*)();
using Renew = int (*)(int);
using Release = void (*)(int);
Start startFn = nullptr;
Ready readyFn = nullptr;
Dial dialFn = nullptr;
Stop stopFn = nullptr;
Renew renewFn = nullptr;
Release releaseFn = nullptr;
std::once_flag loadOnce;
std::atomic<const char *> errorCode{"native_unavailable"};
bool loaded = false;
}

bool FamilyTransportLoad() {
    std::call_once(loadOnce, [] {
        void *handle = dlopen("libfamilytunnel.so", RTLD_NOW | RTLD_LOCAL);
        if (!handle) return;
        startFn = reinterpret_cast<Start>(dlsym(handle, "FGStart"));
        readyFn = reinterpret_cast<Ready>(dlsym(handle, "FGReady"));
        dialFn = reinterpret_cast<Dial>(dlsym(handle, "FGDial"));
        stopFn = reinterpret_cast<Stop>(dlsym(handle, "FGStop"));
        renewFn = reinterpret_cast<Renew>(dlsym(handle, "FGRefreshLease"));
        releaseFn = reinterpret_cast<Release>(dlsym(handle, "FGRelease"));
        loaded = startFn && readyFn && dialFn && stopFn && renewFn && releaseFn;
        if (loaded) errorCode.store("transport_not_ready");
        // Keep the handle for process lifetime: native network threads retain callbacks.
    });
    return loaded;
}

bool FamilyTransportReady() { return FamilyTransportLoad() && readyFn() == 1; }

int FamilyTransportDial(const char *host, int port) {
    if (!host || !*host || port < 1 || port > 65535 || !FamilyTransportReady()) {
        errorCode.store("transport_not_ready"); errno = EACCES; return -1;
    }
    int fd = dialFn(const_cast<char *>(host), port);
    if (fd < 0) { errorCode.store("dial_failed"); errno = ECONNREFUSED; }
    return fd;
}

int FamilyTransportStart(const char *profile) {
    if (!FamilyTransportLoad()) return -1;
    if (!profile || !*profile) { stopFn(); errorCode.store("profile_rejected"); return -1; }
    int result = startFn(const_cast<char *>(profile));
    if (result != 0) stopFn();
    errorCode.store(result == 0 ? "" : "profile_rejected");
    return result;
}

void FamilyTransportRelease(int fd) {
    if (fd >= 0 && FamilyTransportLoad()) releaseFn(fd);
}

void FamilyTransportStop() {
    if (FamilyTransportLoad()) stopFn();
    errorCode.store("transport_stopped");
}

int FamilyTransportRenew(int seconds) {
    if (!FamilyTransportLoad()) return -1;
    if (seconds < 1 || seconds > 75) { stopFn(); errorCode.store("lease_rejected"); return -1; }
    int result = renewFn(seconds);
    if (result != 0) { stopFn(); errorCode.store("lease_expired"); }
    return result;
}

const char *FamilyTransportError() {
    const char *error = errorCode.load();
    return !*error && !FamilyTransportReady() ? "transport_not_ready" : error;
}
