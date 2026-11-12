#include "../adapter.h"
#include <arpa/inet.h>
#include <dlfcn.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/epoll.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <thread>
#include <vector>
#include <mutex>
#include <set>

#define CHECK(x) do{if(!(x)){fprintf(stderr,"FAIL line %d: %s errno=%d\n",__LINE__,#x,errno);exit(1);}}while(0)
std::atomic<int> ready{1}, routes{0}, released{0};
std::mutex mock_mutex;std::set<int> mock_fds;std::atomic<bool> bad_release{false};
int mock_ready(){return ready.load();}
int mock_dial(int kind){
    int pair[2];if(socketpair(AF_UNIX,kind|SOCK_CLOEXEC,0,pair))return -1;
    fcntl(pair[0],F_SETFL,O_NONBLOCK);routes++;{std::lock_guard<std::mutex> lock(mock_mutex);mock_fds.insert(pair[0]);}
    std::thread([fd=pair[1]](){unsigned char b[8192];for(;;){ssize_t n=recv(fd,b,sizeof(b),0);if(n<=0)break;ssize_t sent=send(fd,b,n,MSG_NOSIGNAL);if(sent!=n)break;}close(fd);}).detach();
    return pair[0];
}
int mock_tcp(char*,int){return mock_dial(SOCK_STREAM);}
int mock_udp(char*,int){return mock_dial(SOCK_DGRAM);}
void mock_release(int fd){released++;{std::lock_guard<std::mutex> lock(mock_mutex);if(mock_fds.erase(fd)!=1)bad_release=true;}send(fd,"",0,MSG_NOSIGNAL);shutdown(fd,SHUT_RDWR);}
int fd_count(){DIR *d=opendir("/proc/self/fd");CHECK(d);int count=0;while(auto e=readdir(d))if(e->d_name[0]!='.')count++;closedir(d);return count;}
sockaddr_in address(const char *ip,int port){sockaddr_in a{};a.sin_family=AF_INET;a.sin_port=htons(port);CHECK(inet_pton(AF_INET,ip,&a.sin_addr)==1);return a;}
void await(int epoll,int fd){epoll_event events[8];for(int i=0;i<30;i++){int n=epoll_wait(epoll,events,8,100);CHECK(n>=0);for(int j=0;j<n;j++)if(events[j].data.fd==fd && (events[j].events&EPOLLIN))return;}CHECK(false);}
int main(){
    int initial_fds=fd_count();
    CHECK(FGCInitialize({mock_ready,mock_tcp,mock_udp,mock_release}));
    fg_real_syscall=dlsym(RTLD_DEFAULT,"syscall");CHECK(fg_real_syscall);
    CHECK(fg_syscall_gate(SYS_getpid)==getpid());
    CHECK(fg_syscall_gate(SYS_socket,AF_INET,SOCK_DGRAM,0)==-1 && errno==EPERM);
    int ep=epoll_create1(EPOLL_CLOEXEC);CHECK(ep>=0);
    int fd=FGC_socket(AF_INET,SOCK_DGRAM,IPPROTO_UDP);CHECK(fd>=0);
    // Pinned WebRTC Create -> Initialize -> AddEpoll occurs before Bind/SendTo.
    CHECK(fcntl(fd,F_SETFL,fcntl(fd,F_GETFL)|O_NONBLOCK)==0);
    int timestamp=1;CHECK(FGC_setsockopt(fd,SOL_SOCKET,SO_TIMESTAMP,&timestamp,sizeof(timestamp))==0);
    epoll_event event{};event.events=EPOLLIN;event.data.fd=fd;CHECK(epoll_ctl(ep,EPOLL_CTL_ADD,fd,&event)==0);
    auto local=address("0.0.0.0",0);CHECK(FGC_bind(fd,reinterpret_cast<sockaddr*>(&local),sizeof(local))==0);
    socklen_t len=sizeof(local);CHECK(FGC_getsockname(fd,reinterpret_cast<sockaddr*>(&local),&len)==0);CHECK(ntohs(local.sin_port)>=49152);
    auto binder=reinterpret_cast<int(*)(unsigned long long,int)>(FGC_dlsym(RTLD_DEFAULT,"android_setsocknetwork"));CHECK(binder && binder(123,fd)==0);
    auto a=address("192.0.2.1",9001),b=address("198.51.100.2",9002);
    CHECK(FGC_sendto(fd,"first",5,0,reinterpret_cast<sockaddr*>(&a),sizeof(a))==5);
    CHECK(FGC_sendto(fd,"second",6,0,reinterpret_cast<sockaddr*>(&b),sizeof(b))==6);
    bool got_a=false,got_b=false;
    for(int i=0;i<2;i++){
        await(ep,fd);unsigned char packet[32];sockaddr_in source{};len=sizeof(source);
        ssize_t n=FGC_recvfrom(fd,packet,sizeof(packet),MSG_PEEK,reinterpret_cast<sockaddr*>(&source),&len);CHECK(n>0);
        len=sizeof(source);CHECK(FGC_recvfrom(fd,packet,sizeof(packet),0,reinterpret_cast<sockaddr*>(&source),&len)==n);
        if(source.sin_port==a.sin_port){CHECK(n==5&&!memcmp(packet,"first",5));got_a=true;}else{CHECK(source.sin_port==b.sin_port&&n==6&&!memcmp(packet,"second",6));got_b=true;}
    }
    CHECK(got_a&&got_b&&routes==2);
    CHECK(FGC_sendto(fd,"abcdefgh",8,0,reinterpret_cast<sockaddr*>(&a),sizeof(a))==8);await(ep,fd);
    char first[2],second[2],control[CMSG_SPACE(sizeof(timeval))];iovec iov[2]={{first,2},{second,2}};sockaddr_in source{};
    msghdr m{};m.msg_iov=iov;m.msg_iovlen=2;m.msg_name=&source;m.msg_namelen=sizeof(source);m.msg_control=control;m.msg_controllen=sizeof(control);
    CHECK(FGC_recvmsg(fd,&m,0)==4);CHECK(m.msg_flags&MSG_TRUNC);CHECK(!memcmp(first,"ab",2)&&!memcmp(second,"cd",2));CHECK(CMSG_FIRSTHDR(&m)->cmsg_type==SCM_TIMESTAMP);
    addrinfo *resolved=nullptr;CHECK(FGC_getaddrinfo("example.com","443",nullptr,&resolved)!=0);CHECK(!resolved);
    // TCP also retains its original descriptor even when registered before connect.
    int tcp=FGC_socket(AF_INET,SOCK_STREAM|SOCK_NONBLOCK,IPPROTO_TCP);CHECK(tcp>=0);
    event.data.fd=tcp;CHECK(epoll_ctl(ep,EPOLL_CTL_ADD,tcp,&event)==0);
    CHECK(FGC_connect(tcp,reinterpret_cast<sockaddr*>(&a),sizeof(a))==0);
    CHECK(FGC_send(tcp,"tcp",3,0)==3);await(ep,tcp);char reply[8];CHECK(FGC_recv(tcp,reply,sizeof(reply),0)==3&&!memcmp(reply,"tcp",3));
    CHECK(FGC_close(tcp)==0);
    // IPv4-mapped IPv6 used by the pinned legacy tgvoip implementation.
    int v6=FGC_socket(AF_INET6,SOCK_DGRAM|SOCK_NONBLOCK,IPPROTO_UDP);CHECK(v6>=0);
    sockaddr_in6 mapped{};mapped.sin6_family=AF_INET6;mapped.sin6_port=htons(9003);CHECK(inet_pton(AF_INET6,"::ffff:203.0.113.3",&mapped.sin6_addr)==1);
    event.data.fd=v6;CHECK(epoll_ctl(ep,EPOLL_CTL_ADD,v6,&event)==0);
    CHECK(FGC_sendto(v6,"v6",2,0,reinterpret_cast<sockaddr*>(&mapped),sizeof(mapped))==2);await(ep,v6);
    sockaddr_in6 from{};len=sizeof(from);CHECK(FGC_recvfrom(v6,reply,sizeof(reply),0,reinterpret_cast<sockaddr*>(&from),&len)==2);CHECK(from.sin6_family==AF_INET6&&IN6_IS_ADDR_V4MAPPED(&from.sin6_addr));
    ready=0;usleep(150000);CHECK(FGC_sendto(fd,"x",1,0,reinterpret_cast<sockaddr*>(&a),sizeof(a))==-1);CHECK(FGC_socket(AF_INET,SOCK_DGRAM,0)==-1);
    FGCallsStop();ready=1;CHECK(FGC_socket(AF_INET,SOCK_DGRAM,0)==-1);CHECK(FGCallsResume()==0);
    CHECK(FGC_close(fd)==0);CHECK(FGC_close(v6)==0);close(ep);usleep(150000);
    CHECK(released==routes&&!bad_release);
    int raw=socket(AF_INET,SOCK_DGRAM,0);CHECK(raw>=0);CHECK(FGC_connect(raw,reinterpret_cast<sockaddr*>(&a),sizeof(a))==-1);CHECK(FGC_sendto(raw,"x",1,0,reinterpret_cast<sockaddr*>(&a),sizeof(a))==-1);CHECK(binder(123,raw)==EPERM);close(raw);
    for(int iteration=0;iteration<64;iteration++){
        int current=FGC_socket(AF_INET,SOCK_DGRAM,0);CHECK(current>=0);
        CHECK(FGC_sendto(current,"stress",6,0,reinterpret_cast<sockaddr*>(&a),sizeof(a))==6);
        char bytes[16];CHECK(FGC_recv(current,bytes,sizeof(bytes),0)==6);
        std::atomic<bool> reading{false};std::atomic<int> received{0};
        std::thread reader([&](){reading=true;received=static_cast<int>(FGC_recv(current,bytes,sizeof(bytes),0));});
        while(!reading.load())std::this_thread::yield();usleep(1000);
        CHECK(FGC_close(current)==0);
        // Force descriptor recycling while the old worker completes cancellation.
        int pipes[2];CHECK(pipe(pipes)==0);CHECK(FGC_write(pipes[1],"p",1)==1);char result;CHECK(FGC_read(pipes[0],&result,1)==1&&result=='p');
        reader.join();CHECK(received==-1);CHECK(fcntl(pipes[0],F_GETFD)>=0&&fcntl(pipes[1],F_GETFD)>=0);close(pipes[0]);close(pipes[1]);
    }
    usleep(150000);CHECK(released==routes&&!bad_release);CHECK(fd_count()==initial_fds);
    long long stats[10]{}; FGCCounters(stats);
    CHECK(stats[0]>=67 && stats[1]==0 && stats[2]>=68 && stats[3]>=68);
    CHECK(stats[5]==0 && stats[6]>=3 && stats[7]>=3 && stats[8]>=1 && stats[9]==0);
    puts("PASS adapter: stable epoll fd, TCP, multi-peer UDP, PEEK, scatter/truncation/timestamp, mapped IPv6, no DNS/raw fallback, syscall gate, lease/stop cleanup, 64 concurrent-close/reuse cycles");
}
