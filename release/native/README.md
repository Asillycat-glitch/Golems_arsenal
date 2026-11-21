# native/JVMTI 层说明

发行包当前默认**不加载**native。旧的 `sanctuary-win-x86_64.arcane` 是全局 JNI 函数表 hook，
属于已知崩服路径，不能再默认启用。

## 计划中的 JVMTI DLL

文件名：`sanctuary-jvmti.dll`（x64）

导出：

```c
JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM* vm, char* options, void* reserved);
JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char* options, void* reserved);
```

能力：

- `SetFieldModificationWatch`：
  - `net/minecraft/world/entity/Entity` 的 `f_146795_`（removalReason）
  - `f_146801_`（levelCallback）
  - `isAddedToWorld` / `canUpdate`
  - `SynchedEntityData$DataItem.f_135391_`（health value）
- `ThreadStart` / `ThreadEnd`：记录服务器线程和异常线程结束
- `ClassPrepare`：把 mods 类加入候选索引（与 Java agent 的 `isModClass` 对齐）

限制：

- JVMTI 字段 watch 是**通知**，不能直接取消本次 `SetFloatField`；需要回调 Java
  `GuardRuntime` 做恢复，或在 native 侧用 JNI `Set*Field` 写回。
- `Unsafe` 裸内存写不一定触发字段 watch；需要 agent 改写 `Unsafe` 调用点。
- `TerminateProcess`/`TerminateThread` 是进程/线程级，native 也挡不住；必须靠 supervisor/Job Object/服务。

## 构建

需要 MSVC x64（`cl.exe`）和 JDK 头文件：

```bat
cl /LD /O2 /I"%JAVA_HOME%\include" /I"%JAVA_HOME%\include\win32" ^
   sanctuary_jvmti.c /Fe:sanctuary-jvmti.dll
```

构建后放入 `native/win-x64/sanctuary-jvmti.dll`，由 agent 在 attach 后加载。
