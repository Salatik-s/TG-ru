#include <dlfcn.h>
#include <link.h>
#include <elf.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <poll.h>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#define CHECK(x) do{if(!(x)){fprintf(stderr,"audit FAIL line %d: %s\n",__LINE__,#x);return 7;}}while(0)
struct Query{const char *symbol;void **slot;};
int find_import(dl_phdr_info *info,size_t,void *opaque){
    const char *base=strrchr(info->dlpi_name,'/');base=base?base+1:info->dlpi_name;if(strcmp(base,"libtmessages.49.so"))return 0;
    const ElfW(Dyn)*dynamic=nullptr;for(unsigned i=0;i<info->dlpi_phnum;i++)if(info->dlpi_phdr[i].p_type==PT_DYNAMIC)dynamic=reinterpret_cast<const ElfW(Dyn)*>(info->dlpi_addr+info->dlpi_phdr[i].p_vaddr);if(!dynamic)return 0;
    const ElfW(Sym)*symbols=nullptr;const char *strings=nullptr;const ElfW(Rela)*plt=nullptr;size_t size=0;
    for(auto d=dynamic;d->d_tag!=DT_NULL;d++){auto p=info->dlpi_addr+d->d_un.d_ptr;switch(d->d_tag){case DT_SYMTAB:symbols=reinterpret_cast<const ElfW(Sym)*>(p);break;case DT_STRTAB:strings=reinterpret_cast<const char*>(p);break;case DT_JMPREL:plt=reinterpret_cast<const ElfW(Rela)*>(p);break;case DT_PLTRELSZ:size=d->d_un.d_val;break;}}
    if(!symbols||!strings||!plt)return 0;auto query=static_cast<Query*>(opaque);
    for(size_t i=0;i<size/sizeof(*plt);i++)if(!strcmp(strings+symbols[ELF64_R_SYM(plt[i].r_info)].st_name,query->symbol)){query->slot=reinterpret_cast<void**>(info->dlpi_addr+plt[i].r_offset);return 1;}
    return 0;
}
void **slot(const char *symbol){Query query{symbol,nullptr};dl_iterate_phdr(find_import,&query);return query.slot;}
int main(int argc,char **argv){
    if(argc!=4&&argc!=5){fprintf(stderr,"usage: audit libfamilytunnel.so libtmessages.49.so libfamilycalls.so [local-test-profile.json]\n");return 2;}
    void *go=nullptr;for(int i=1;i<3;i++){void *loaded=dlopen(argv[i],RTLD_NOW|RTLD_GLOBAL);if(!loaded){fprintf(stderr,"load failed: %s\n",dlerror());return 3;}if(i==1)go=loaded;}
    void **socket_slot=slot("socket");CHECK(socket_slot);void *before=*socket_slot;
    void *adapter=dlopen(argv[3],RTLD_NOW|RTLD_LOCAL);if(!adapter){fprintf(stderr,"adapter load failed: %s\n",dlerror());return 4;}
    auto install=reinterpret_cast<int(*)()>(dlsym(adapter,"FGCallsInstall"));auto installed=reinterpret_cast<int(*)()>(dlsym(adapter,"FGCallsInstalled"));
    if(!install||!installed)return 5;int result=install();printf("install=%d installed=%d\n",result,installed());CHECK(result==0&&installed()==1);CHECK(*socket_slot!=before);
    auto original_socket=reinterpret_cast<int(*)(int,int,int)>(*socket_slot);
    auto original_bind=reinterpret_cast<int(*)(int,const sockaddr*,socklen_t)>(*slot("bind"));
    auto original_name=reinterpret_cast<int(*)(int,sockaddr*,socklen_t*)>(*slot("getsockname"));
    auto original_close=reinterpret_cast<int(*)(int)>(*slot("close"));
    auto start=reinterpret_cast<int(*)(char*)>(dlsym(go,"FGStart"));auto stop=reinterpret_cast<void(*)()>(dlsym(go,"FGStop"));
    auto resume=reinterpret_cast<int(*)()>(dlsym(adapter,"FGCallsResume"));auto revoke=reinterpret_cast<void(*)()>(dlsym(adapter,"FGCallsStop"));CHECK(start&&stop&&resume&&revoke);
    CHECK(original_socket(AF_INET,SOCK_DGRAM,0)==-1);
    char profile[]="{\"protocol\":\"vless\",\"host\":\"127.0.0.1\",\"port\":1,\"uuid\":\"66d7ff88-566c-4dcc-aea0-95694b03c1ef\",\"security\":\"reality\",\"public_key\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\",\"short_id\":\"0123456789abcdef\",\"server_name\":\"www.microsoft.com\",\"flow\":\"xtls-rprx-vision\",\"fingerprint\":\"chrome\"}";
    char loaded_profile[16384];char *selected_profile=profile;
    if(argc==5){FILE *file=fopen(argv[4],"rb");CHECK(file);size_t length=fread(loaded_profile,1,sizeof(loaded_profile)-1,file);CHECK(feof(file));fclose(file);loaded_profile[length]=0;selected_profile=loaded_profile;}
    CHECK(start(selected_profile)==0&&resume()==0);
    int fd=original_socket(AF_INET,SOCK_DGRAM,0);CHECK(fd>=0);
    sockaddr_storage actual{};socklen_t size=sizeof(actual);CHECK(getsockname(fd,reinterpret_cast<sockaddr*>(&actual),&size)==0&&actual.ss_family==AF_UNIX);
    sockaddr_in requested{};requested.sin_family=AF_INET;CHECK(original_bind(fd,reinterpret_cast<sockaddr*>(&requested),sizeof(requested))==0);
    size=sizeof(actual);CHECK(original_name(fd,reinterpret_cast<sockaddr*>(&actual),&size)==0&&actual.ss_family==AF_INET);
    if(argc==5){
        auto port=[&](const char *field){const char *entry=strstr(selected_profile,field);if(!entry)return 0;entry=strchr(entry,':');return entry?atoi(entry+1):0;};
        auto connect_fn=reinterpret_cast<int(*)(int,const sockaddr*,socklen_t)>(*slot("connect"));
        auto send_fn=reinterpret_cast<ssize_t(*)(int,const void*,size_t,int)>(*slot("send"));
        auto recv_fn=reinterpret_cast<ssize_t(*)(int,void*,size_t,int)>(*slot("recv"));
        auto sendto_fn=reinterpret_cast<ssize_t(*)(int,const void*,size_t,int,const sockaddr*,socklen_t)>(*slot("sendto"));
        auto recvfrom_fn=reinterpret_cast<ssize_t(*)(int,void*,size_t,int,sockaddr*,socklen_t*)>(*slot("recvfrom"));
        auto address=[](int p){sockaddr_in a{};a.sin_family=AF_INET;a.sin_addr.s_addr=htonl(INADDR_LOOPBACK);a.sin_port=htons(p);return a;};
        int tcp_port=port("tcp_echo_port");CHECK(tcp_port>0);auto target=address(tcp_port);
        int tcp=original_socket(AF_INET,SOCK_STREAM|SOCK_NONBLOCK,0);CHECK(tcp>=0);CHECK(connect_fn(tcp,reinterpret_cast<sockaddr*>(&target),sizeof(target))==0);
        const char payload[]="actual original GOT through VLESS REALITY";CHECK(send_fn(tcp,payload,sizeof(payload),0)==sizeof(payload));
        pollfd p{tcp,POLLIN,0};CHECK(poll(&p,1,20000)>0);char echo[256];CHECK(recv_fn(tcp,echo,sizeof(echo),0)==sizeof(payload));CHECK(!memcmp(echo,payload,sizeof(payload)));CHECK(original_close(tcp)==0);
        int ports[2]={port("udp_echo_a"),port("udp_echo_b")};CHECK(ports[0]>0&&ports[1]>0);
        for(int i=0;i<2;i++){target=address(ports[i]);char message[2]={static_cast<char>('A'+i),0};CHECK(sendto_fn(fd,message,sizeof(message),0,reinterpret_cast<sockaddr*>(&target),sizeof(target))==sizeof(message));}
        bool seen[2]={false,false};for(int i=0;i<2;i++){p={fd,POLLIN,0};CHECK(poll(&p,1,20000)>0);sockaddr_in source{};socklen_t len=sizeof(source);CHECK(recvfrom_fn(fd,echo,sizeof(echo),0,reinterpret_cast<sockaddr*>(&source),&len)==2);int index=echo[0]-'A';CHECK(index>=0&&index<2&&ntohs(source.sin_port)==ports[index]);seen[index]=true;}CHECK(seen[0]&&seen[1]);
        puts("PASS encrypted integration: actual original GOT -> socket adapter -> Android Go -> VLESS/REALITY -> Windows loopback TCP + two UDP echo destinations");
    }
    CHECK(original_close(fd)==0);revoke();stop();CHECK(original_socket(AF_INET,SOCK_DGRAM,0)==-1);
    usleep(100000);
    puts("PASS actual original-ELF GOT redirection: gate, kernel AF_UNIX / virtual AF_INET, bind/address/close, revoke; Go remains separately loaded");return 0;
}
