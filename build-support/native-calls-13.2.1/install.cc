#include "adapter.h"
#include "xhook.h"
#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>
#include <atomic>
#include <cstring>
#include <string>
#include <vector>
#include "xh_util.h"

#define PUBLIC __attribute__((visibility("default")))
namespace {
struct Hook { const char *name; void *replacement; bool required; };
#define H(n) {#n,reinterpret_cast<void *>(FGC_##n),true}
Hook hooks[] = {H(socket),H(connect),H(bind),H(getsockname),H(setsockopt),H(getsockopt),H(listen),H(accept),H(shutdown),H(close),H(send),H(sendto),H(recv),H(recvfrom),H(recvmsg),H(read),H(write),H(writev),H(getaddrinfo),H(dlsym),
#if defined(__aarch64__)
    H(readv),
#else
    {"readv",reinterpret_cast<void *>(FGC_readv),false},
#endif
    {"getpeername",reinterpret_cast<void *>(FGC_getpeername),false},
    {"syscall",reinterpret_cast<void *>(fg_syscall_gate),true}};
#undef H
struct Slot { void **location; Hook *hook; void *original; unsigned int protection; };
struct Target { uintptr_t base=0; std::string path; std::vector<Slot> slots; bool valid=false; unsigned count=0; };
std::atomic<bool> installed{false};
#if defined(__aarch64__)
const unsigned char expected_id[20]={0x1b,0x19,0x90,0x49,0xbc,0x41,0xfb,0x4b,0xb7,0x1a,0x58,0xf1,0xa1,0xa1,0xaa,0x5b,0xb0,0x6a,0x61,0xf6};
#else
const unsigned char expected_id[20]={0xa1,0x96,0xdc,0x55,0x59,0x30,0xd7,0x5a,0xd3,0xeb,0xfd,0xb2,0xc4,0x7c,0x4a,0x23,0xdb,0x20,0x56,0xb2};
#endif
bool build_id(const dl_phdr_info *info) {
    for(unsigned i=0;i<info->dlpi_phnum;i++){
        auto &ph=info->dlpi_phdr[i];if(ph.p_type!=PT_NOTE)continue;
        const unsigned char *p=reinterpret_cast<const unsigned char *>(info->dlpi_addr+ph.p_vaddr),*end=p+ph.p_memsz;
        while(p+sizeof(ElfW(Nhdr))<=end){
            auto n=reinterpret_cast<const ElfW(Nhdr)*>(p);p+=sizeof(*n);
            size_t namesz=(n->n_namesz+3u)&~3u,descsz=(n->n_descsz+3u)&~3u;
            if(namesz>static_cast<size_t>(end-p)||descsz>static_cast<size_t>(end-p)-namesz)return false;
            if(n->n_type==NT_GNU_BUILD_ID && n->n_namesz==4 && !memcmp(p,"GNU",4) && n->n_descsz==sizeof(expected_id))return !memcmp(p+namesz,expected_id,sizeof(expected_id));
            p+=namesz+descsz;
        }
    }
    return false;
}
int locate(dl_phdr_info *info,size_t,void *opaque){
    auto target=static_cast<Target *>(opaque);const char *name=info->dlpi_name;const char *base=strrchr(name,'/');base=base?base+1:name;
    if(strcmp(base,"libtmessages.49.so"))return 0;
    target->count++;
    if(!build_id(info))return 0;
    const ElfW(Dyn) *dynamic=nullptr;
    for(unsigned i=0;i<info->dlpi_phnum;i++)if(info->dlpi_phdr[i].p_type==PT_DYNAMIC)dynamic=reinterpret_cast<const ElfW(Dyn)*>(info->dlpi_addr+info->dlpi_phdr[i].p_vaddr);
    if(!dynamic)return 0;
    const ElfW(Sym) *symbols=nullptr;const char *strings=nullptr;const ElfW(Rela) *plt=nullptr,*rela=nullptr;size_t pltsize=0,relasize=0;long plttype=0;
    for(auto d=dynamic;d->d_tag!=DT_NULL;d++){
        uintptr_t address=info->dlpi_addr+d->d_un.d_ptr;
        switch(d->d_tag){case DT_SYMTAB:symbols=reinterpret_cast<const ElfW(Sym)*>(address);break;case DT_STRTAB:strings=reinterpret_cast<const char *>(address);break;case DT_JMPREL:plt=reinterpret_cast<const ElfW(Rela)*>(address);break;case DT_PLTRELSZ:pltsize=d->d_un.d_val;break;case DT_PLTREL:plttype=d->d_un.d_val;break;case DT_RELA:rela=reinterpret_cast<const ElfW(Rela)*>(address);break;case DT_RELASZ:relasize=d->d_un.d_val;break;}
    }
    if(!symbols||!strings||!plt||plttype!=DT_RELA)return 0;
    auto scan=[&](const ElfW(Rela)*entries,size_t size){
        if(!entries)return;
        for(size_t i=0;i<size/sizeof(ElfW(Rela));i++){
            size_t index=ELF64_R_SYM(entries[i].r_info);if(!index)continue;
            const char *symbol=strings+symbols[index].st_name;
            for(auto &hook:hooks)if(!strcmp(symbol,hook.name))target->slots.push_back({reinterpret_cast<void **>(info->dlpi_addr+entries[i].r_offset),&hook,nullptr,0});
        }
    };
    scan(plt,pltsize);scan(rela,relasize);
    for(auto &hook:hooks){if(!hook.required)continue;bool found=false;for(auto &slot:target->slots)if(slot.hook==&hook)found=true;if(!found)return 0;}
    target->base=info->dlpi_addr;target->path=name;target->valid=true;return 0;
}
void rollback(Target &target){
    for(auto &slot:target.slots){
        uintptr_t address=reinterpret_cast<uintptr_t>(slot.location);
        if(xh_util_set_addr_protect(address,slot.protection|PROT_WRITE))_exit(78);
        __atomic_store_n(slot.location,slot.original,__ATOMIC_RELEASE);
        if(xh_util_set_addr_protect(address,slot.protection))_exit(78);
    }
    xhook_clear();
}
}

extern "C" PUBLIC int FGCallsInstall(){
    if(installed.load())return 0;
    Target target;dl_iterate_phdr(locate,&target);
    if(target.count!=1 || !target.valid)return -1;
    for(auto &slot:target.slots){
        if(xh_util_get_addr_protect(reinterpret_cast<uintptr_t>(slot.location),target.path.c_str(),&slot.protection))return -8;
        slot.original=__atomic_load_n(slot.location,__ATOMIC_ACQUIRE);
    }
    void *lib=dlopen("libfamilytunnel.so",RTLD_NOW|RTLD_NOLOAD);
    if(!lib)return -2;
    FGTransport api{reinterpret_cast<int(*)()>(dlsym(lib,"FGReady")),reinterpret_cast<int(*)(char*,int)>(dlsym(lib,"FGDial")),reinterpret_cast<int(*)(char*,int)>(dlsym(lib,"FGUDPDial")),reinterpret_cast<void(*)(int)>(dlsym(lib,"FGRelease"))};
    if(!FGCInitialize(api))return -3;
    fg_real_syscall=dlsym(RTLD_DEFAULT,"syscall");if(!fg_real_syscall)return -4;
    xhook_enable_debug(0);
    // SIGSEGV swallowing could hide partial installation; this candidate fails hard.
    xhook_enable_sigsegv_protection(0);
    for(auto &hook:hooks)if(xhook_register(".*/libtmessages\\.49\\.so$",hook.name,hook.replacement,nullptr)){xhook_clear();return -5;}
    if(xhook_refresh(0)){rollback(target);return -6;}
    for(auto &slot:target.slots)if(__atomic_load_n(slot.location,__ATOMIC_ACQUIRE)!=slot.hook->replacement){rollback(target);return -7;}
    xhook_clear();
    installed=true;return 0;
}
extern "C" PUBLIC int FGCallsInstalled(){return installed.load()?1:0;}

extern "C" PUBLIC jint JNI_OnLoad(JavaVM*,void*){return FGCallsInstall()==0?JNI_VERSION_1_6:JNI_ERR;}
extern "C" PUBLIC jboolean Java_org_telegram_messenger_family_FamilyCalls_nativeInstalled(JNIEnv*,jclass){return installed.load()?JNI_TRUE:JNI_FALSE;}
extern "C" PUBLIC jint Java_org_telegram_messenger_family_FamilyCalls_nativeResume(JNIEnv*,jclass){return FGCallsResume();}
extern "C" PUBLIC void Java_org_telegram_messenger_family_FamilyCalls_nativeStop(JNIEnv*,jclass){FGCallsStop();}

extern "C" PUBLIC jlongArray Java_org_telegram_messenger_family_FamilyCalls_nativeCounters(JNIEnv *env,jclass){
    long long snapshot[10]{}; FGCCounters(snapshot); jlong values[10];
    for(int i=0;i<10;i++)values[i]=static_cast<jlong>(snapshot[i]);
    jlongArray result=env->NewLongArray(10);
    if(result)env->SetLongArrayRegion(result,0,10,values);
    return result;
}
