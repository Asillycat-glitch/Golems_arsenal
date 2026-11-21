// sanctuary_jvmhook.c —— 我们自己的 JVM 原生 hook（对应 AV 的 jvmHook-*.arcane）
//
// v2（2026-11-10）：v1 改的是"当前线程的 JNIEnv 表指针"，而 install() 是在
// Attach Listener 线程上跑的 —— 对服务端主线程毫无作用。v2 改成**原地覆盖全局共享表**：
//   HotSpot 所有线程的 *env 指向同一张 JNINativeInterface_（只读常量表），
//   用 VirtualProtect 把那一页改成可写，直接替换 SetObjectField/SetFloatField/SetDoubleField
//   三个入口。这样任何线程的 JNI 写都会进我们的桩。
//
// 注意：覆盖前必须把原始函数指针单独存下来（不能再从表里取，表已经被改过了）。
//
// 构建：
//   zig cc -shared -O2 -o sanctuary-win-x86_64.arcane sanctuary_jvmhook.c ^
//     -I<JDK>\include -I<JDK>\include\win32

#include <windows.h>
#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <stdio.h>

static JavaVM* g_vm = NULL;
static jclass g_runtime_class = NULL;
static jmethodID g_native_field_write = NULL;
static int g_installed = 0;

// 原始函数指针（覆盖前抓下来，之后所有转发都用它们）
static jclass (JNICALL *o_FindClass)(JNIEnv*, const char*);
static jclass (JNICALL *o_NewGlobalRef)(JNIEnv*, jobject);
static jmethodID (JNICALL *o_GetStaticMethodID)(JNIEnv*, jclass, const char*, const char*);
static jstring (JNICALL *o_NewStringUTF)(JNIEnv*, const char*);
static jboolean (JNICALL *o_CallStaticBooleanMethod)(JNIEnv*, jclass, jmethodID, ...);
static void (JNICALL *o_DeleteLocalRef)(JNIEnv*, jobject);
static void (JNICALL *o_SetObjectField)(JNIEnv*, jobject, jfieldID, jobject);
static void (JNICALL *o_SetFloatField)(JNIEnv*, jobject, jfieldID, jfloat);
static void (JNICALL *o_SetDoubleField)(JNIEnv*, jobject, jfieldID, jdouble);

static long g_nativeBlocked = 0;

// 让 Java 侧决定这次字段写要不要丢
static int java_allows(JNIEnv* env, jobject obj, const char* site) {
    if (env == NULL || obj == NULL || g_runtime_class == NULL || g_native_field_write == NULL
            || o_NewStringUTF == NULL || o_CallStaticBooleanMethod == NULL) {
        return 1; // 拿不到策略时放行，避免把游戏写坏
    }
    jstring jsite = o_NewStringUTF(env, site);
    if (jsite == NULL) {
        return 1;
    }
    jboolean allowed = o_CallStaticBooleanMethod(env, g_runtime_class, g_native_field_write, obj, jsite);
    if (o_DeleteLocalRef != NULL) {
        o_DeleteLocalRef(env, jsite);
    }
    return allowed == JNI_TRUE;
}

// ---- 桩 ----
static void JNICALL hook_SetObjectField(JNIEnv* env, jobject obj, jfieldID field, jobject value) {
    if (java_allows(env, obj, "jni.SetObjectField")) {
        o_SetObjectField(env, obj, field, value);
    } else {
        g_nativeBlocked++;
    }
}

static void JNICALL hook_SetFloatField(JNIEnv* env, jobject obj, jfieldID field, jfloat value) {
    if (java_allows(env, obj, "jni.SetFloatField")) {
        o_SetFloatField(env, obj, field, value);
    } else {
        g_nativeBlocked++;
    }
}

static void JNICALL hook_SetDoubleField(JNIEnv* env, jobject obj, jfieldID field, jdouble value) {
    if (java_allows(env, obj, "jni.SetDoubleField")) {
        o_SetDoubleField(env, obj, field, value);
    } else {
        g_nativeBlocked++;
    }
}

// 找到并缓存 GuardRuntime + 回调方法（必须在覆盖表之前做）
// 只抓原始函数指针，不改表
static void capture_table(JNIEnv* env) {
    if (env == NULL || o_FindClass != NULL) {
        return;
    }
    const struct JNINativeInterface_** tablePtr = (const struct JNINativeInterface_**) env;
    struct JNINativeInterface_* table = (struct JNINativeInterface_*) *tablePtr;
    if (table == NULL) {
        return;
    }
    o_FindClass = table->FindClass;
    o_NewGlobalRef = table->NewGlobalRef;
    o_GetStaticMethodID = table->GetStaticMethodID;
    o_NewStringUTF = table->NewStringUTF;
    o_CallStaticBooleanMethod = table->CallStaticBooleanMethod;
    o_DeleteLocalRef = table->DeleteLocalRef;
    o_SetObjectField = table->SetObjectField;
    o_SetFloatField = table->SetFloatField;
    o_SetDoubleField = table->SetDoubleField;
}

static int bind_java(JNIEnv* env) {
    if (g_runtime_class != NULL) {
        return 1;
    }
    if (env == NULL) return -11; if (o_FindClass == NULL) return -12;
    jclass local = o_FindClass(env, "sanctuary/agent/GuardRuntime");
    if (local == NULL) return -13;
    g_runtime_class = (jclass) o_NewGlobalRef(env, local);
    o_DeleteLocalRef(env, local);
    if (g_runtime_class == NULL) return -14;
    g_native_field_write = o_GetStaticMethodID(env, g_runtime_class, "nativeFieldWrite",
            "(Ljava/lang/Object;Ljava/lang/String;)Z");
    return g_native_field_write != NULL ? 1 : -15;
}

// 原地覆盖全局共享的 JNINativeInterface_ 表
static int install_hook(JNIEnv* env) {
    if (g_installed) {
        return 1;
    }
    if (env == NULL) {
        return 0;
    }
    const struct JNINativeInterface_** tablePtr = (const struct JNINativeInterface_**) env;
    struct JNINativeInterface_* table = (struct JNINativeInterface_*) *tablePtr;
    if (table == NULL) return -22;

    // 1) 先抓原始函数指针
    o_FindClass = table->FindClass;
    o_NewGlobalRef = table->NewGlobalRef;
    o_GetStaticMethodID = table->GetStaticMethodID;
    o_NewStringUTF = table->NewStringUTF;
    o_CallStaticBooleanMethod = table->CallStaticBooleanMethod;
    o_DeleteLocalRef = table->DeleteLocalRef;
    o_SetObjectField = table->SetObjectField;
    o_SetFloatField = table->SetFloatField;
    o_SetDoubleField = table->SetDoubleField;

    // 2) 表在只读页里：改页属性后原地写
    DWORD oldProtect = 0;
    if (!VirtualProtect((LPVOID) table, sizeof(*table), PAGE_READWRITE, &oldProtect)) return -23;
    table->SetObjectField = hook_SetObjectField;
    table->SetFloatField = hook_SetFloatField;
    table->SetDoubleField = hook_SetDoubleField;
    VirtualProtect((LPVOID) table, sizeof(*table), oldProtect, &oldProtect);

    g_installed = 1;
    return 1;
}

// 导出：System.load 之后由 NativeLoader 调用
__declspec(dllexport) jint JNICALL Java_sanctuary_agent_NativeLoader_install(JNIEnv* env, jclass cls) {
    (void) cls;
    capture_table(env);
    { int b = bind_java(env); if (b != 1) return b; int h = install_hook(env); return h; }
}

__declspec(dllexport) jint JNICALL Java_sanctuary_agent_NativeLoader_blocked(JNIEnv* env, jclass cls) {
    (void) env; (void) cls;
    return (jint) g_nativeBlocked;
}

// 兼容 -agentpath 路径（可选）
__declspec(dllexport) jint JNICALL Agent_OnAttach(JavaVM* vm, char* options, void* reserved) {
    JNIEnv* env = NULL;
    (void) options; (void) reserved;
    g_vm = vm;
    if ((*vm)->GetEnv(vm, (void**) &env, JNI_VERSION_1_8) != JNI_OK) {
        return JNI_ERR;
    }
    capture_table(env);
    if (!bind_java(env)) {
        return JNI_ERR;
    }
    return install_hook(env) ? JNI_OK : JNI_ERR;
}

BOOL APIENTRY DllMain(HMODULE module, DWORD reason, LPVOID reserved) {
    (void) module; (void) reserved;
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(module);
    }
    return TRUE;
}
