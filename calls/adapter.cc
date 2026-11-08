#include "adapter.h"
#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sys/time.h>
#include <unistd.h>
#include <algorithm>
#include <atomic>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {
constexpr size_t kMaxPacket = 7526, kMaxSockets = 64, kMaxPeers = 32;
constexpr size_t kBuffer = 65536;
FGTransport transport{};
std::atomic<bool> initialized{false};
std::atomic<bool> allowed{false};
std::atomic<unsigned> next_port{49152};
struct Address {
    sockaddr_storage value{};
    socklen_t length = 0;
    std::string host;
    int port = 0;
    std::string key() const { return host + ":" + std::to_string(port); }
};
bool parse(const sockaddr *sa, socklen_t length, Address &out) {
    if (!sa) { errno = EFAULT; return false; }
    char host[INET6_ADDRSTRLEN];
    if (sa->sa_family == AF_INET && length >= sizeof(sockaddr_in)) {
        auto in = reinterpret_cast<const sockaddr_in *>(sa);
        inet_ntop(AF_INET, &in->sin_addr, host, sizeof(host));
        out.length = sizeof(*in); out.port = ntohs(in->sin_port);
    } else if (sa->sa_family == AF_INET6 && length >= sizeof(sockaddr_in6)) {
        auto in = reinterpret_cast<const sockaddr_in6 *>(sa);
        if (IN6_IS_ADDR_V4MAPPED(&in->sin6_addr))
            inet_ntop(AF_INET, in->sin6_addr.s6_addr + 12, host, sizeof(host));
        else inet_ntop(AF_INET6, &in->sin6_addr, host, sizeof(host));
        out.length = sizeof(*in); out.port = ntohs(in->sin6_port);
    } else { errno = EAFNOSUPPORT; return false; }
    memcpy(&out.value, sa, out.length); out.host = host; return true;
}
void assign_port(Address &address) {
    if (address.port) return;
    address.port = 49152 + next_port.fetch_add(1) % 16000;
    if (address.value.ss_family == AF_INET)
        reinterpret_cast<sockaddr_in *>(&address.value)->sin_port = htons(address.port);
    else reinterpret_cast<sockaddr_in6 *>(&address.value)->sin6_port = htons(address.port);
}
int output_address(const Address &in, sockaddr *out, socklen_t *length) {
    if (!length || !out) { errno = EFAULT; return -1; }
    memcpy(out, &in.value, std::min<size_t>(*length, in.length));
    *length = in.length; return 0;
}
struct Route { int fd; Address address; };
struct Socket {
    int domain, kind, peer;
    std::atomic<bool> dead{false}, connected{false}, application_closed{false};
    std::mutex mutex, fd_mutex;
    Address local, remote;
    std::map<std::string, Route> routes;
    int stream = -1;
    std::map<std::pair<int,int>, int> options;
    ~Socket() { if (peer >= 0) ::close(peer); }
};
std::mutex sockets_mutex;
std::map<int, std::shared_ptr<Socket>> sockets;
std::shared_ptr<Socket> find(int fd) {
    std::lock_guard<std::mutex> lock(sockets_mutex);
    auto it = sockets.find(fd); return it == sockets.end() ? nullptr : it->second;
}
bool internet_fd(int fd) {
    sockaddr_storage addr{}; socklen_t size = sizeof(addr);
    return ::getsockname(fd, reinterpret_cast<sockaddr *>(&addr), &size) == 0 &&
           (addr.ss_family == AF_INET || addr.ss_family == AF_INET6);
}
bool live(const std::shared_ptr<Socket> &socket) {
    if (socket->dead.load() || !allowed.load() || !initialized.load() || !transport.ready()) {
        socket->dead = true; errno = ENETDOWN; return false;
    }
    return true;
}
void release(int fd) { transport.release(fd); ::close(fd); }
struct Frame { uint32_t magic; uint16_t address_length; uint16_t reserved; sockaddr_storage address; };
constexpr uint32_t kMagic = 0x46474331;
void finish(const std::shared_ptr<Socket> &socket) {
    socket->dead = true;
    std::lock_guard<std::mutex> lock(socket->mutex);
    if (socket->stream >= 0) { release(socket->stream); socket->stream = -1; }
    for (auto &entry : socket->routes) release(entry.second.fd);
    socket->routes.clear();
    if (socket->kind == SOCK_STREAM) ::shutdown(socket->peer, SHUT_RDWR);
    else {
        // Wake epoll readers. Datagram sockets do not produce EOF on peer close.
        Frame frame{};
        ::send(socket->peer, &frame, sizeof(frame), MSG_DONTWAIT | MSG_NOSIGNAL);
    }
}
void udp_worker(std::shared_ptr<Socket> socket) {
    std::vector<unsigned char> packet(sizeof(Frame) + kMaxPacket);
    while (live(socket)) {
        std::vector<pollfd> pollers{{socket->peer,POLLIN,0}};
        {
            std::lock_guard<std::mutex> lock(socket->mutex);
            for (const auto &entry : socket->routes) pollers.push_back({entry.second.fd, POLLIN, 0});
        }
        int result = ::poll(pollers.data(), pollers.size(), 50);
        if (result < 0 && errno != EINTR) break;
        if (result <= 0) continue;
        std::lock_guard<std::mutex> lock(socket->mutex);
        for (const auto &poller : pollers) {
            if (!poller.revents) continue;
            if (!live(socket)) break;
            if (poller.fd == socket->peer) {
                ssize_t size=::recv(socket->peer,packet.data(),packet.size(),MSG_DONTWAIT);
                if(size<=static_cast<ssize_t>(sizeof(Frame)))continue;
                Frame frame;memcpy(&frame,packet.data(),sizeof(frame));
                if(frame.magic!=kMagic || frame.address_length>sizeof(sockaddr_storage))continue;
                Address remote;if(!parse(reinterpret_cast<const sockaddr*>(&frame.address),frame.address_length,remote)||!remote.port)continue;
                auto route=socket->routes.find(remote.key());
                if(route==socket->routes.end()){
                    if(socket->routes.size()>=kMaxPeers)continue;
                    int fd=transport.udp(remote.host.data(),remote.port);if(fd<0)continue;
                    route=socket->routes.emplace(remote.key(),Route{fd,remote}).first;
                }
                ssize_t sent=::send(route->second.fd,packet.data()+sizeof(Frame),size-sizeof(Frame),MSG_DONTWAIT|MSG_NOSIGNAL);
                // UDP is lossy. Drop on downstream backpressure without falsely
                // presenting an always-writable virtual fd as backend readiness.
                if(sent<0 && errno!=EAGAIN && errno!=EWOULDBLOCK){release(route->second.fd);socket->routes.erase(route);}
                continue;
            }
            auto route = std::find_if(socket->routes.begin(), socket->routes.end(), [&](const auto &item) { return item.second.fd == poller.fd; });
            if (route == socket->routes.end()) continue;
            if (poller.revents & (POLLERR | POLLHUP | POLLNVAL)) {
                release(route->second.fd); socket->routes.erase(route); continue;
            }
            ssize_t size = ::recv(poller.fd, packet.data() + sizeof(Frame), kMaxPacket, MSG_DONTWAIT);
            if (size <= 0) continue;
            if (!live(socket)) break;
            Frame frame{kMagic, static_cast<uint16_t>(route->second.address.length), 0, route->second.address.value};
            memcpy(packet.data(), &frame, sizeof(frame));
            // Bounded, lossy UDP queue: never block the network worker on a slow reader.
            ::send(socket->peer, packet.data(), sizeof(Frame) + size, MSG_DONTWAIT | MSG_NOSIGNAL);
        }
    }
    finish(socket);
}
struct Buffer { unsigned char bytes[kBuffer]; size_t begin = 0, end = 0; bool empty() const { return begin == end; } };
bool read_buffer(int fd, Buffer &buffer) {
    if (!buffer.empty()) return true;
    ssize_t n = ::recv(fd, buffer.bytes, sizeof(buffer.bytes), MSG_DONTWAIT);
    if (n == 0) return false;
    if (n < 0) return errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR;
    buffer.begin = 0; buffer.end = n; return true;
}
bool write_buffer(int fd, Buffer &buffer) {
    if (buffer.empty()) return true;
    ssize_t n = ::send(fd, buffer.bytes + buffer.begin, buffer.end - buffer.begin, MSG_DONTWAIT | MSG_NOSIGNAL);
    if (n < 0) return errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR;
    buffer.begin += n; return n != 0;
}
void tcp_worker(std::shared_ptr<Socket> socket) {
    Buffer outbound{}, inbound{};
    while (live(socket)) {
        int stream;
        { std::lock_guard<std::mutex> lock(socket->mutex); stream = socket->stream; }
        if (stream < 0) { ::poll(nullptr, 0, 25); continue; }
        pollfd fds[2] = {{socket->peer, static_cast<short>((outbound.empty() ? POLLIN : 0) | (!inbound.empty() ? POLLOUT : 0)), 0},
                         {stream, static_cast<short>((inbound.empty() ? POLLIN : 0) | (!outbound.empty() ? POLLOUT : 0)), 0}};
        if (::poll(fds, 2, 50) < 0) { if (errno == EINTR) continue; break; }
        if (!live(socket)) break;
        if ((fds[0].revents | fds[1].revents) & (POLLERR | POLLNVAL)) break;
        if ((fds[0].revents & POLLIN) && !read_buffer(socket->peer, outbound)) break;
        if ((fds[1].revents & POLLIN) && !read_buffer(stream, inbound)) break;
        if (!write_buffer(stream, outbound) || !write_buffer(socket->peer, inbound)) break;
        if ((fds[0].revents | fds[1].revents) & POLLHUP) break;
    }
    finish(socket);
}
int fail() { errno = ENETDOWN; return -1; }
}

bool FGCInitialize(FGTransport api) {
    if (!api.ready || !api.tcp || !api.udp || !api.release) return false;
    if (initialized.load()) return false;
    transport = api; initialized = true; allowed = true; return true;
}
extern "C" void FGCallsStop() {
    allowed = false;
    std::lock_guard<std::mutex> lock(sockets_mutex);
    for (const auto &entry : sockets) entry.second->dead = true;
}
extern "C" int FGCallsResume(){if(!initialized.load()||!transport.ready())return fail();allowed=true;return 0;}
extern "C" int FGC_socket(int domain, int type, int protocol) {
    if (domain != AF_INET && domain != AF_INET6) {
        if(domain==AF_UNIX || domain==AF_NETLINK)return ::socket(domain,type,protocol);
        errno=EAFNOSUPPORT;return -1;
    }
    if (!allowed.load() || !initialized.load() || !transport.ready()) return fail();
    int kind = type & 0xf;
    if ((kind != SOCK_STREAM && kind != SOCK_DGRAM) || (protocol && protocol != (kind==SOCK_STREAM?IPPROTO_TCP:IPPROTO_UDP))) { errno = EPROTONOSUPPORT; return -1; }
    std::lock_guard<std::mutex> lock(sockets_mutex);
    if (sockets.size() >= kMaxSockets) { errno = EMFILE; return -1; }
    int pair[2];
    if (::socketpair(AF_UNIX, kind | (type & (SOCK_NONBLOCK | SOCK_CLOEXEC)), 0, pair)) return -1;
    ::fcntl(pair[1], F_SETFL, ::fcntl(pair[1], F_GETFL) | O_NONBLOCK);
    ::fcntl(pair[1], F_SETFD, FD_CLOEXEC);
    auto socket = std::make_shared<Socket>();
    socket->domain = domain; socket->kind = kind; socket->peer = pair[1];
    socket->local.value.ss_family = domain;
    socket->local.length = domain == AF_INET ? sizeof(sockaddr_in) : sizeof(sockaddr_in6);
    assign_port(socket->local);
    sockets[pair[0]] = socket;
    try { std::thread(kind == SOCK_DGRAM ? udp_worker : tcp_worker, socket).detach(); }
    catch (...) { sockets.erase(pair[0]); ::close(pair[0]); errno = EAGAIN; return -1; }
    return pair[0];
}
extern "C" int FGC_connect(int fd, const sockaddr *address, socklen_t length) {
    auto socket = find(fd);
    if (!socket) { if (internet_fd(fd) || (address && (address->sa_family == AF_INET || address->sa_family == AF_INET6))) return fail(); return ::connect(fd,address,length); }
    if (!live(socket)) return -1;
    Address remote; if (!parse(address,length,remote) || !remote.port) { errno = EINVAL; return -1; }
    std::lock_guard<std::mutex> lock(socket->mutex);
    if (socket->kind == SOCK_STREAM) {
        if (socket->connected) { errno = EISCONN; return -1; }
        socket->stream = transport.tcp(remote.host.data(),remote.port);
        if (socket->stream < 0) return fail();
    }
    socket->remote = remote; socket->connected = true; return 0;
}
extern "C" int FGC_bind(int fd, const sockaddr *address, socklen_t length) {
    auto socket = find(fd); if (!socket) return internet_fd(fd) ? fail() : ::bind(fd,address,length);
    if (!live(socket)) return -1;
    Address local; if (!parse(address,length,local)) return -1;
    if (local.value.ss_family != socket->domain) { errno = EAFNOSUPPORT; return -1; }
    assign_port(local); std::lock_guard<std::mutex> lock(socket->mutex); socket->local = local; return 0;
}
extern "C" int FGC_getsockname(int fd, sockaddr *address, socklen_t *length) {
    auto socket = find(fd); if (!socket) return ::getsockname(fd,address,length);
    std::lock_guard<std::mutex> lock(socket->mutex); return output_address(socket->local,address,length);
}
extern "C" int FGC_getpeername(int fd, sockaddr *address, socklen_t *length) {
    auto socket = find(fd); if (!socket) return ::getpeername(fd,address,length);
    if (!socket->connected) { errno = ENOTCONN; return -1; }
    std::lock_guard<std::mutex> lock(socket->mutex); return output_address(socket->remote,address,length);
}
extern "C" int FGC_setsockopt(int fd,int level,int option,const void *value,socklen_t length) {
    auto socket = find(fd); if (!socket) return internet_fd(fd) ? fail() : ::setsockopt(fd,level,option,value,length);
    std::lock_guard<std::mutex> operation(socket->fd_mutex);if(socket->application_closed){errno=EBADF;return -1;}
    if (!live(socket)) return -1;
    if (level == SOL_SOCKET && (option == SO_SNDBUF || option == SO_RCVBUF || option == SO_SNDTIMEO || option == SO_RCVTIMEO || option == SO_TIMESTAMP || option == SO_REUSEADDR))
        return ::setsockopt(fd,level,option,value,length);
    if ((level == IPPROTO_IP || level == IPPROTO_IPV6 || level == IPPROTO_TCP || level == SOL_SOCKET) && value && length == sizeof(int)) {
        std::lock_guard<std::mutex> lock(socket->mutex); socket->options[{level,option}] = *static_cast<const int *>(value); return 0;
    }
    errno = ENOPROTOOPT; return -1;
}
extern "C" int FGC_getsockopt(int fd,int level,int option,void *value,socklen_t *length) {
    auto socket = find(fd); if (!socket) return ::getsockopt(fd,level,option,value,length);
    std::lock_guard<std::mutex> operation(socket->fd_mutex);if(socket->application_closed){errno=EBADF;return -1;}
    if (!value || !length || *length < sizeof(int)) { errno = EINVAL; return -1; }
    int result;
    if (level == SOL_SOCKET && option == SO_ERROR) result = live(socket) ? 0 : ENETDOWN;
    else if (level == SOL_SOCKET && option == SO_TYPE) result = socket->kind;
    else if (level == SOL_SOCKET && option == SO_DOMAIN) result = socket->domain;
    else if (level == SOL_SOCKET && option == SO_PROTOCOL) result = socket->kind == SOCK_STREAM ? IPPROTO_TCP : IPPROTO_UDP;
    else {
        std::lock_guard<std::mutex> lock(socket->mutex);
        auto it = socket->options.find({level,option});
        if (it == socket->options.end()) return ::getsockopt(fd,level,option,value,length);
        result = it->second;
    }
    memcpy(value,&result,sizeof(result)); *length = sizeof(result); return 0;
}
extern "C" int FGC_listen(int fd,int backlog) { if (find(fd) || internet_fd(fd)) { errno=EOPNOTSUPP; return -1; } return ::listen(fd,backlog); }
extern "C" int FGC_accept(int fd,sockaddr *address,socklen_t *length) { if (find(fd) || internet_fd(fd)) return fail(); return ::accept(fd,address,length); }
extern "C" int FGC_shutdown(int fd,int how) { auto socket=find(fd);if(!socket)return internet_fd(fd) ? fail() : ::shutdown(fd,how);std::lock_guard<std::mutex> operation(socket->fd_mutex);if(socket->application_closed){errno=EBADF;return -1;}socket->dead=true;return ::shutdown(fd,how); }
extern "C" int FGC_close(int fd) {
    std::shared_ptr<Socket> socket;
    { std::lock_guard<std::mutex> lock(sockets_mutex); auto it=sockets.find(fd); if(it!=sockets.end()){socket=it->second;socket->dead=true;sockets.erase(it);} }
    if(socket){
        // Serialize kernel operations against close/reuse of this descriptor.
        // Worker cancellation wakes blocking reads before we take fd_mutex.
        if(socket->kind==SOCK_STREAM)::shutdown(socket->peer,SHUT_RDWR);
        std::lock_guard<std::mutex> operation(socket->fd_mutex);socket->application_closed=true;return ::close(fd);
    }
    return ::close(fd);
}
static ssize_t send_virtual(const std::shared_ptr<Socket>&socket,int fd,const void *data,size_t length,int flags,const sockaddr *address,socklen_t addrlen) {
    std::lock_guard<std::mutex> operation(socket->fd_mutex);if(socket->application_closed){errno=EBADF;return -1;}
    if(!live(socket)) return -1;
    if(socket->kind==SOCK_STREAM){if(!socket->connected){errno=ENOTCONN;return -1;} return ::send(fd,data,length,flags|MSG_NOSIGNAL);}
    if(length==0 || length>kMaxPacket){errno=EMSGSIZE;return -1;}
    if(flags & ~(MSG_DONTWAIT|MSG_NOSIGNAL|MSG_CONFIRM)){errno=EOPNOTSUPP;return -1;}
    Address remote;
    if(address){if(!parse(address,addrlen,remote))return -1;} else {if(!socket->connected){errno=EDESTADDRREQ;return -1;}std::lock_guard<std::mutex> lock(socket->mutex);remote=socket->remote;}
    if(!remote.port){errno=EINVAL;return -1;}
    unsigned char packet[sizeof(Frame)+kMaxPacket];Frame frame{kMagic,static_cast<uint16_t>(remote.length),0,remote.value};
    memcpy(packet,&frame,sizeof(frame));memcpy(packet+sizeof(frame),data,length);
    ssize_t result=::send(fd,packet,sizeof(frame)+length,(flags&MSG_DONTWAIT)|MSG_NOSIGNAL);
    return result<0?result:static_cast<ssize_t>(length);
}
extern "C" ssize_t FGC_sendto(int fd,const void *data,size_t length,int flags,const sockaddr *address,socklen_t addrlen){auto socket=find(fd);if(socket)return send_virtual(socket,fd,data,length,flags,address,addrlen);return internet_fd(fd) ? fail() : ::sendto(fd,data,length,flags,address,addrlen);}
extern "C" ssize_t FGC_send(int fd,const void *data,size_t length,int flags){return FGC_sendto(fd,data,length,flags,nullptr,0);}
static ssize_t recv_virtual(const std::shared_ptr<Socket>&socket,int fd,void *data,size_t length,int flags,sockaddr *address,socklen_t *addrlen){
    std::lock_guard<std::mutex> operation(socket->fd_mutex);if(socket->application_closed){errno=EBADF;return -1;}
    if(!live(socket)){unsigned char drain[1024];while(::recv(fd,drain,sizeof(drain),MSG_DONTWAIT)>0){}return fail();}
    if(socket->kind==SOCK_STREAM){if(!socket->connected){errno=ENOTCONN;return -1;}if(address && output_address(socket->remote,address,addrlen))return -1;return ::recv(fd,data,length,flags);}
    unsigned char packet[sizeof(Frame)+kMaxPacket];
    ssize_t received=::recv(fd,packet,sizeof(packet),flags);
    if(received<0)return -1;
    if(!live(socket))return -1;
    if(received<static_cast<ssize_t>(sizeof(Frame))){errno=EPROTO;return -1;}
    Frame frame;memcpy(&frame,packet,sizeof(frame));
    if(frame.magic!=kMagic || frame.address_length>sizeof(sockaddr_storage)){errno=ENETDOWN;return -1;}
    if(address){Address source;source.value=frame.address;source.length=frame.address_length;if(output_address(source,address,addrlen))return -1;}
    size_t payload=received-sizeof(Frame),copied=std::min(length,payload);
    memcpy(data,packet+sizeof(Frame),copied);
    return flags&MSG_TRUNC?payload:copied;
}
extern "C" ssize_t FGC_recvfrom(int fd,void *data,size_t length,int flags,sockaddr *address,socklen_t *addrlen){auto socket=find(fd);if(socket)return recv_virtual(socket,fd,data,length,flags,address,addrlen);return internet_fd(fd) ? fail() : ::recvfrom(fd,data,length,flags,address,addrlen);}
extern "C" ssize_t FGC_recv(int fd,void *data,size_t length,int flags){return FGC_recvfrom(fd,data,length,flags,nullptr,nullptr);}
extern "C" ssize_t FGC_read(int fd,void *data,size_t length){auto socket=find(fd);if(socket)return recv_virtual(socket,fd,data,length,0,nullptr,nullptr);return internet_fd(fd) ? fail() : ::read(fd,data,length);}
extern "C" ssize_t FGC_write(int fd,const void *data,size_t length){auto socket=find(fd);if(socket)return send_virtual(socket,fd,data,length,0,nullptr,0);return internet_fd(fd) ? fail() : ::write(fd,data,length);}
static ssize_t recvmsg_virtual(const std::shared_ptr<Socket>&socket,int fd,msghdr *message,int flags){
    if(!message || message->msg_iovlen>1024){errno=EINVAL;return -1;}
    size_t capacity=0;for(size_t i=0;i<message->msg_iovlen;i++){if(message->msg_iov[i].iov_len>kBuffer-capacity){capacity=kBuffer;break;}capacity+=message->msg_iov[i].iov_len;}
    unsigned char bytes[kBuffer];socklen_t addrlen=message->msg_namelen;
    ssize_t n=recv_virtual(socket,fd,bytes,capacity,flags|(socket->kind==SOCK_DGRAM?MSG_TRUNC:0),static_cast<sockaddr *>(message->msg_name),&addrlen);
    if(n<0)return n;
    size_t available=std::min<size_t>(n,capacity),offset=0;
    for(size_t i=0;i<message->msg_iovlen && offset<available;i++){size_t count=std::min(available-offset,message->msg_iov[i].iov_len);memcpy(message->msg_iov[i].iov_base,bytes+offset,count);offset+=count;}
    message->msg_namelen=addrlen;message->msg_flags=n>static_cast<ssize_t>(capacity)?MSG_TRUNC:0;
    // Arrival timestamp is sampled locally after receipt; never invent network RTT.
    if(message->msg_control && message->msg_controllen>=CMSG_SPACE(sizeof(timeval))){
        message->msg_controllen=CMSG_SPACE(sizeof(timeval));auto c=CMSG_FIRSTHDR(message);c->cmsg_level=SOL_SOCKET;c->cmsg_type=SCM_TIMESTAMP;c->cmsg_len=CMSG_LEN(sizeof(timeval));timeval now;gettimeofday(&now,nullptr);memcpy(CMSG_DATA(c),&now,sizeof(now));
    }else message->msg_controllen=0;
    return flags&MSG_TRUNC?n:available;
}
extern "C" ssize_t FGC_recvmsg(int fd,msghdr *message,int flags){auto socket=find(fd);if(socket)return recvmsg_virtual(socket,fd,message,flags);return internet_fd(fd) ? fail() : ::recvmsg(fd,message,flags);}
extern "C" ssize_t FGC_readv(int fd,const iovec *iov,int count){auto socket=find(fd);if(!socket)return internet_fd(fd) ? fail() : ::readv(fd,iov,count);if(count<0){errno=EINVAL;return -1;}msghdr m{};m.msg_iov=const_cast<iovec *>(iov);m.msg_iovlen=count;return recvmsg_virtual(socket,fd,&m,0);}
extern "C" ssize_t FGC_writev(int fd,const iovec *iov,int count){
    auto socket=find(fd);if(!socket)return internet_fd(fd) ? fail() : ::writev(fd,iov,count);
    if(count<0 || count>1024){errno=EINVAL;return -1;}
    if(socket->kind==SOCK_STREAM){unsigned char bytes[kBuffer];size_t total=0;for(int i=0;i<count && total<kBuffer;i++){size_t n=std::min(iov[i].iov_len,kBuffer-total);memcpy(bytes+total,iov[i].iov_base,n);total+=n;}return send_virtual(socket,fd,bytes,total,0,nullptr,0);}
    unsigned char bytes[kMaxPacket];size_t total=0;for(int i=0;i<count;i++){if(iov[i].iov_len>kMaxPacket-total){errno=EMSGSIZE;return -1;}memcpy(bytes+total,iov[i].iov_base,iov[i].iov_len);total+=iov[i].iov_len;}return send_virtual(socket,fd,bytes,total,0,nullptr,0);
}
extern "C" int FGC_getaddrinfo(const char *node,const char *service,const addrinfo *hints,addrinfo **result){
    // Calls currently use Telegram's literal endpoint IPs. No direct DNS fallback.
    addrinfo numeric{};if(hints)numeric=*hints;numeric.ai_flags|=AI_NUMERICHOST|AI_NUMERICSERV;
    return ::getaddrinfo(node,service,&numeric,result);
}
extern "C" int FGC_bind_network(unsigned long long,int fd){if(find(fd))return 0;return internet_fd(fd)?EPERM:0;}
extern "C" void *FGC_dlsym(void *handle,const char *name){
    if(name && (!strcmp(name,"android_setsocknetwork")||!strcmp(name,"setNetworkForSocket")))return reinterpret_cast<void *>(FGC_bind_network);
    // Known dynamic networking lookup must resolve to the same wrappers.
#define LOOKUP(n) if(name && !strcmp(name,#n)) return reinterpret_cast<void *>(FGC_##n)
    LOOKUP(socket);LOOKUP(connect);LOOKUP(bind);LOOKUP(send);LOOKUP(sendto);LOOKUP(recv);LOOKUP(recvfrom);LOOKUP(recvmsg);LOOKUP(read);LOOKUP(write);LOOKUP(readv);LOOKUP(writev);LOOKUP(close);LOOKUP(getaddrinfo);LOOKUP(getsockname);LOOKUP(getpeername);LOOKUP(getsockopt);LOOKUP(setsockopt);LOOKUP(listen);LOOKUP(accept);LOOKUP(shutdown);
#undef LOOKUP
    if(name && !strcmp(name,"syscall"))return reinterpret_cast<void *>(fg_syscall_gate);
    return ::dlsym(handle,name);
}
extern "C" { void *fg_real_syscall = nullptr; }
extern "C" long fg_blocked_syscall(){errno=EPERM;return -1;}
