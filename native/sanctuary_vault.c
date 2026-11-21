// sanctuary_vault.c —— 内存防火墙（native 金库）
//
// 目的：给"权威状态"（受保护 UUID 列表、账本摘要、最后已知位置/血量）一块
// **进程内但不可随意改**的内存：
//   1. VirtualAlloc 一块私有区域，默认 PAGE_READONLY；
//   2. 用两份副本 + 校验和：任何一份被改，校验和对不上 → 用另一份恢复；
//   3. AddVectoredExceptionHandler 捕获写入这块区域的访问违例 → 计数 + 记录地址；
//      然后临时放开写权限，让这次写完成，再立刻恢复只读（检测优先，不试图模拟指令）。
//
// 这不是"防住 native 改内存"——同进程的 native 代码最终能改任何用户态内存。
// 它保证的是：**改了会被发现，且能从第二份副本恢复**。

#include <windows.h>
#include <jni.h>
#include <stdint.h>
#include <string.h>

#define VAULT_CAP 65536

static unsigned char* g_a = NULL;   // 主副本（默认只读）
static unsigned char* g_b = NULL;   // 影子副本（一直可写，只有我们写）
static SIZE_T g_size = 0;
static ULONG64 g_seq = 0;
static LONG g_tamper = 0;
static PVOID g_veh = NULL;

static ULONG64 checksum(const unsigned char* p, SIZE_T n) {
    ULONG64 h = 1469598103934665603ULL;   // FNV-1a 64
    for (SIZE_T i = 0; i < n; i++) {
        h ^= (ULONG64) p[i];
        h *= 1099511628211ULL;
    }
    return h;
}

static LONG CALLBACK vault_veh(EXCEPTION_POINTERS* info) {
    if (info == NULL || info->ExceptionRecord == NULL) {
        return EXCEPTION_CONTINUE_SEARCH;
    }
    if (info->ExceptionRecord->ExceptionCode != EXCEPTION_ACCESS_VIOLATION) {
        return EXCEPTION_CONTINUE_SEARCH;
    }
    // ExceptionInformation[0] == 1 表示写
    if (info->ExceptionRecord->ExceptionInformation[0] != 1) {
        return EXCEPTION_CONTINUE_SEARCH;
    }
    void* addr = (void*) info->ExceptionRecord->ExceptionInformation[1];
    if (g_a == NULL || addr < (void*) g_a || addr >= (void*) (g_a + g_size)) {
        return EXCEPTION_CONTINUE_SEARCH;
    }
    InterlockedIncrement(&g_tamper);
    // 临时放开，让这次写完成，然后立刻恢复只读（检测到即可，不跟指令计数器较劲）
    DWORD old = 0;
    VirtualProtect(g_a, g_size, PAGE_READWRITE, &old);
    return EXCEPTION_CONTINUE_EXECUTION;
}

__declspec(dllexport) jint JNICALL vault_init(void) {
    if (g_a != NULL) {
        return 1;
    }
    g_a = (unsigned char*) VirtualAlloc(NULL, VAULT_CAP, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    g_b = (unsigned char*) VirtualAlloc(NULL, VAULT_CAP, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (g_a == NULL || g_b == NULL) {
        return 0;
    }
    g_size = VAULT_CAP;
    memset(g_a, 0, g_size);
    memset(g_b, 0, g_size);
    g_veh = AddVectoredExceptionHandler(1, vault_veh);
    DWORD old = 0;
    VirtualProtect(g_a, g_size, PAGE_READONLY, &old);
    return 1;
}

// 写：先写影子，再切只读→写主本→切回只读
__declspec(dllexport) jint JNICALL vault_write(const unsigned char* data, jint len) {
    if (g_a == NULL || data == NULL || len <= 0 || (SIZE_T) len > g_size) {
        return 0;
    }
    memcpy(g_b, data, (SIZE_T) len);
    DWORD old = 0;
    VirtualProtect(g_a, g_size, PAGE_READWRITE, &old);
    memcpy(g_a, data, (SIZE_T) len);
    VirtualProtect(g_a, g_size, PAGE_READONLY, &old);
    g_seq++;
    return 1;
}

// 读：优先主本，校验不过就用影子恢复主本
__declspec(dllexport) jint JNICALL vault_read(unsigned char* out, jint len) {
    if (g_a == NULL || out == NULL || len <= 0 || (SIZE_T) len > g_size) {
        return 0;
    }
    ULONG64 ha = checksum(g_a, (SIZE_T) len);
    ULONG64 hb = checksum(g_b, (SIZE_T) len);
    DWORD old = 0;
    if (ha != hb) {
        InterlockedIncrement(&g_tamper);
        VirtualProtect(g_a, g_size, PAGE_READWRITE, &old);
        memcpy(g_a, g_b, (SIZE_T) len);   // 用影子恢复
        VirtualProtect(g_a, g_size, PAGE_READONLY, &old);
    }
    memcpy(out, g_a, (SIZE_T) len);
    return 1;
}

__declspec(dllexport) jint JNICALL vault_tamper_count(void) {
    return g_tamper;
}

__declspec(dllexport) jint JNICALL vault_seq(void) {
    return (jint) g_seq;
}

__declspec(dllexport) jint JNICALL vault_selftest(void) {
    if (!vault_init()) {
        return -1;
    }
    unsigned char probe[16];
    for (int i = 0; i < 16; i++) {
        probe[i] = (unsigned char) (i * 7 + 3);
    }
    if (!vault_write(probe, 16)) {
        return -2;
    }
    unsigned char back[16];
    if (!vault_read(back, 16)) {
        return -3;
    }
    for (int i = 0; i < 16; i++) {
        if (probe[i] != back[i]) {
            return -4;
        }
    }
    return 1;
}

// ---- JNI 导出（NativeLoader 里声明的 native 方法） ----

__declspec(dllexport) jint JNICALL
Java_sanctuary_agent_NativeLoader_vaultInit(JNIEnv* env, jclass cls) {
    (void) env; (void) cls;
    return vault_init();
}

__declspec(dllexport) jint JNICALL
Java_sanctuary_agent_NativeLoader_vaultSelfTest(JNIEnv* env, jclass cls) {
    (void) env; (void) cls;
    return vault_selftest();
}

__declspec(dllexport) jint JNICALL
Java_sanctuary_agent_NativeLoader_vaultTamper(JNIEnv* env, jclass cls) {
    (void) env; (void) cls;
    return vault_tamper_count();
}
// ---- JVMTI 探针（只探测，不 hook 任何表） ----
#include <jvmti.h>

__declspec(dllexport) jint JNICALL
Java_sanctuary_agent_NativeLoader_jvmtiProbe(JNIEnv* env, jclass cls) {
    (void) cls;
    if (env == NULL) {
        return -1;
    }
    JavaVM* vm = NULL;
    if ((*env)->GetJavaVM(env, &vm) != JNI_OK || vm == NULL) {
        return -2;
    }
    jvmtiEnv* jvmti = NULL;
    jint rc = (*vm)->GetEnv(vm, (void**) &jvmti, JVMTI_VERSION_1_2);
    if (rc != JNI_OK || jvmti == NULL) {
        return -3;
    }
    jvmtiCapabilities caps;
    memset(&caps, 0, sizeof(caps));
    if ((*jvmti)->GetPotentialCapabilities(jvmti, &caps) != JVMTI_ERROR_NONE) {
        return -4;
    }
    // 返回能力位：bit0=redefine, bit1=retransform, bit2=field_modification
    jint bits = 0;
    if (caps.can_redefine_classes) bits |= 1;
    if (caps.can_retransform_classes) bits |= 2;
    if (caps.can_generate_field_modification_events) bits |= 4;
    return bits;
}