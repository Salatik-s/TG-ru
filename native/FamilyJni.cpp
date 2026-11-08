#include <jni.h>
#include <cstdint>
#include <type_traits>
#include "FamilyTransport.h"
#include "tgnet/NativeByteBuffer.h"
#include "tgnet/ConnectionsManager.h"

extern "C" int registerNativeTgNetFunctions(JavaVM *, JNIEnv *);
extern "C" jboolean Java_org_telegram_tgnet_ConnectionsManager_native_1test_1AuthAuthorization(JNIEnv *, jclass, jlong);

static_assert(std::is_standard_layout<NativeByteBuffer>::value, "Native buffer must remain standard-layout");
static_assert(!std::is_polymorphic<NativeByteBuffer>::value, "Cross-library buffer cannot carry a vtable");
static_assert(sizeof(NativeByteBuffer) == (sizeof(void *) == 8 ? 40 : 28), "Pinned NativeByteBuffer ABI changed");

namespace {
bool verifyOriginalBufferAbi(JNIEnv *env) {
    // Original SQLite JNI also passes NativeByteBuffer pointers to Java, so validate
    // the boundary before replacing native registrations. No network manager starts.
    jclass manager = env->FindClass("org/telegram/tgnet/ConnectionsManager");
    jclass buffers = env->FindClass("org/telegram/tgnet/NativeByteBuffer");
    if (!manager || !buffers || env->ExceptionCheck()) return false;
    jmethodID setJava = env->GetStaticMethodID(manager, "native_setJava", "(Z)V");
    jmethodID allocate = env->GetStaticMethodID(buffers, "native_getFreeBuffer", "(I)J");
    jmethodID getJava = env->GetStaticMethodID(buffers, "native_getJavaByteBuffer", "(J)Ljava/nio/ByteBuffer;");
    jmethodID limit = env->GetStaticMethodID(buffers, "native_limit", "(J)I");
    jmethodID position = env->GetStaticMethodID(buffers, "native_position", "(J)I");
    jmethodID reuse = env->GetStaticMethodID(buffers, "native_reuse", "(J)V");
    if (!setJava || !allocate || !getJava || !limit || !position || !reuse || env->ExceptionCheck()) return false;
    env->CallStaticVoidMethod(manager, setJava, JNI_FALSE);
    if (env->ExceptionCheck()) return false;
    jlong address = env->CallStaticLongMethod(buffers, allocate, 113);
    if (!address || env->ExceptionCheck()) return false;
    jobject javaBuffer = env->CallStaticObjectMethod(buffers, getJava, address);
    if (!javaBuffer || env->ExceptionCheck()) return false;
    auto *buffer = reinterpret_cast<NativeByteBuffer *>(static_cast<intptr_t>(address));
    bool valid = buffer->bytes() == env->GetDirectBufferAddress(javaBuffer)
        && buffer->capacity() == env->GetDirectBufferCapacity(javaBuffer)
        && buffer->capacity() >= 113
        && buffer->limit() == static_cast<uint32_t>(env->CallStaticIntMethod(buffers, limit, address))
        && buffer->limit() == 113
        && buffer->position() == static_cast<uint32_t>(env->CallStaticIntMethod(buffers, position, address))
        && buffer->position() == 0;
    if (valid) {
        buffer->position(7);
        buffer->limit(109);
        valid = env->CallStaticIntMethod(buffers, position, address) == 7
            && env->CallStaticIntMethod(buffers, limit, address) == 109;
        buffer->position(0);
        buffer->limit(113);
    }
    env->CallStaticVoidMethod(buffers, reuse, address);
    env->DeleteLocalRef(javaBuffer);
    env->DeleteLocalRef(buffers);
    env->DeleteLocalRef(manager);
    return valid && !env->ExceptionCheck();
}

jint start(JNIEnv *env, jclass, jstring profile) {
    if (!profile || env->GetStringUTFLength(profile) > 4096) { FamilyTransportStop(); return -1; }
    const char *text = env->GetStringUTFChars(profile, nullptr);
    if (!text) { FamilyTransportStop(); return -1; }
    int result = FamilyTransportStart(text);
    env->ReleaseStringUTFChars(profile, text);
    return result;
}
void stop(JNIEnv *, jclass) { FamilyTransportStop(); }
jboolean ready(JNIEnv *, jclass) { return FamilyTransportReady() ? JNI_TRUE : JNI_FALSE; }
jstring lastError(JNIEnv *env, jclass) { return env->NewStringUTF(FamilyTransportError()); }
jint renew(JNIEnv *, jclass, jint seconds) { return FamilyTransportRenew(seconds); }

bool registerFamily(JNIEnv *env) {
    jclass family = env->FindClass("org/telegram/messenger/family/FamilyNative");
    if (!family) return false;
    JNINativeMethod methods[] = {
        {const_cast<char *>("nativeStart"), const_cast<char *>("(Ljava/lang/String;)I"), reinterpret_cast<void *>(start)},
        {const_cast<char *>("nativeStop"), const_cast<char *>("()V"), reinterpret_cast<void *>(stop)},
        {const_cast<char *>("nativeReady"), const_cast<char *>("()Z"), reinterpret_cast<void *>(ready)},
        {const_cast<char *>("nativeLastError"), const_cast<char *>("()Ljava/lang/String;"), reinterpret_cast<void *>(lastError)},
        {const_cast<char *>("nativeRenewLease"), const_cast<char *>("(I)I"), reinterpret_cast<void *>(renew)}
    };
    bool ok = env->RegisterNatives(family, methods, sizeof(methods) / sizeof(methods[0])) == 0;
    env->DeleteLocalRef(family);
    return ok;
}
}

#ifdef FAMILYGRAM_FULL_SOURCE
extern "C" int registerFamilyNativeFunctions(JavaVM *vm, JNIEnv *env) {
    if (!FamilyTransportLoad() || !registerFamily(env) || env->ExceptionCheck()) return JNI_FALSE;
    ConnectionsManager::useJavaVM(vm, false);
    return JNI_TRUE;
}
#else
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    if (!FamilyTransportLoad() || !verifyOriginalBufferAbi(env)) return JNI_ERR;
    if (registerNativeTgNetFunctions(vm, env) != JNI_TRUE) return JNI_ERR;
    // This upstream method uses name-based binding and is absent from the usual
    // table. Explicitly override it to keep all tgnet deserialization in one copy.
    jclass manager = env->FindClass("org/telegram/tgnet/ConnectionsManager");
    if (!manager) return JNI_ERR;
    JNINativeMethod extra[] = {{const_cast<char *>("native_test_AuthAuthorization"), const_cast<char *>("(J)Z"), reinterpret_cast<void *>(Java_org_telegram_tgnet_ConnectionsManager_native_1test_1AuthAuthorization)}};
    int registered = env->RegisterNatives(manager, extra, 1);
    env->DeleteLocalRef(manager);
    if (registered != 0 || !registerFamily(env) || env->ExceptionCheck()) return JNI_ERR;
    ConnectionsManager::useJavaVM(vm, false);
    return JNI_VERSION_1_6;
}
#endif
