#include "../adapter.h"
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <thread>
#include <vector>

#define CHECK(x) do { if (!(x)) { fprintf(stderr,"FAIL line %d: %s errno=%d\n",__LINE__,#x,errno); std::abort(); } } while(0)
namespace {
constexpr size_t size = 1024 * 1024 + 197;
std::atomic<int> ready{1}, releases{0};
int scenario = 0;
std::vector<std::thread> servers;
std::vector<unsigned char> payload(unsigned char salt) {
    std::vector<unsigned char> bytes(size);
    for (size_t i=0;i<bytes.size();++i) bytes[i]=static_cast<unsigned char>(i*37+salt);
    return bytes;
}
void write_all(int fd,const std::vector<unsigned char> &bytes,bool application) {
    size_t offset=0;
    while(offset<bytes.size()) {
        ssize_t n=application ? FGC_send(fd,bytes.data()+offset,bytes.size()-offset,0)
                              : ::send(fd,bytes.data()+offset,bytes.size()-offset,MSG_NOSIGNAL);
        if(n<0 && errno==EINTR)continue;
        CHECK(n>0);offset+=static_cast<size_t>(n);
    }
}
void read_all_until_eof(int fd,const std::vector<unsigned char> &expected,bool application,bool slow) {
    size_t offset=0;
    unsigned char buffer[997];
    for(;;) {
        ssize_t n=application ? FGC_recv(fd,buffer,sizeof(buffer),0) : ::recv(fd,buffer,sizeof(buffer),0);
        if(n<0 && errno==EINTR)continue;
        CHECK(n>=0);
        if(!n)break;
        CHECK(offset+static_cast<size_t>(n)<=expected.size());
        for(ssize_t i=0;i<n;++i)CHECK(buffer[i]==expected[offset+static_cast<size_t>(i)]);
        offset+=static_cast<size_t>(n);
        if(slow)usleep(1000);
    }
    CHECK(offset==expected.size());
}
int mock_ready(){return ready.load();}
int mock_udp(char *,int){errno=EPROTONOSUPPORT;return -1;}
void mock_release(int fd){++releases;::shutdown(fd,SHUT_RDWR);}
int mock_tcp(char *,int) {
    int pair[2];CHECK(::socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,pair)==0);
    int small=4096;
    CHECK(setsockopt(pair[0],SOL_SOCKET,SO_SNDBUF,&small,sizeof(small))==0);
    CHECK(setsockopt(pair[1],SOL_SOCKET,SO_SNDBUF,&small,sizeof(small))==0);
    CHECK(fcntl(pair[0],F_SETFL,O_NONBLOCK)==0);
    int mode=scenario;
    servers.emplace_back([fd=pair[1],mode] {
        auto upload=payload(17),download=payload(91);
        if(mode==1) {
            // Client half-closes only after >1 MiB upload. Reply starts after EOF.
            read_all_until_eof(fd,upload,false,true);
            write_all(fd,download,false);
            CHECK(::shutdown(fd,SHUT_WR)==0);
        } else {
            write_all(fd,download,false);
            if(mode==2) {
                // Remote half-close must leave the opposite direction usable.
                CHECK(::shutdown(fd,SHUT_WR)==0);
                read_all_until_eof(fd,upload,false,true);
            }
        }
        ::close(fd);
    });
    return pair[0];
}
}
int main() {
    alarm(30); // A half-close/backpressure deadlock fails, rather than hanging CI.
    CHECK(FGCInitialize({mock_ready,mock_tcp,mock_udp,mock_release}));
    sockaddr_in destination{};destination.sin_family=AF_INET;destination.sin_port=htons(443);
    CHECK(inet_pton(AF_INET,"192.0.2.10",&destination.sin_addr)==1);
    auto upload=payload(17),download=payload(91);
    for(scenario=0;scenario<3;++scenario) {
        int fd=FGC_socket(AF_INET,SOCK_STREAM,IPPROTO_TCP);CHECK(fd>=0);
        int small=4096;CHECK(FGC_setsockopt(fd,SOL_SOCKET,SO_SNDBUF,&small,sizeof(small))==0);
        CHECK(FGC_connect(fd,reinterpret_cast<sockaddr *>(&destination),sizeof(destination))==0);
        if(scenario==1){write_all(fd,upload,true);CHECK(FGC_shutdown(fd,SHUT_WR)==0);}
        // Let server/adapter queues fill before the intentionally slow consumer.
        usleep(150000);
        read_all_until_eof(fd,download,true,true);
        if(scenario==2){write_all(fd,upload,true);CHECK(FGC_shutdown(fd,SHUT_WR)==0);}
        else if(scenario==0)CHECK(FGC_shutdown(fd,SHUT_WR)==0);
        servers.back().join();
        usleep(100000);
        // Normal EOF stays EOF and SO_ERROR stays zero after worker completion.
        char byte;CHECK(FGC_recv(fd,&byte,1,0)==0);
        int error=-1;socklen_t length=sizeof(error);CHECK(FGC_getsockopt(fd,SOL_SOCKET,SO_ERROR,&error,&length)==0 && error==0);
        CHECK(FGC_close(fd)==0);
    }
    usleep(150000);CHECK(releases==3);
    // Revocation must still supersede graceful EOF, rather than exposing data.
    FGCallsStop();CHECK(FGC_socket(AF_INET,SOCK_STREAM,0)==-1);
    puts("PASS TCP drain: >1MiB tail, slow readers, 4KiB write buffers, both half-close directions, EOF persistence, release exactly once");
}
