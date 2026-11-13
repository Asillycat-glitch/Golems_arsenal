# Sanctuary Agent（仅测试）

> **测试产物，不保证强度，不保证稳定性。**
> 这个 agent 会 self-attach 到 JVM、做 retransform、扫描外部模组类，并可能和别的
> mixin/agent 抢类加载。多模组环境下崩溃、卡死、行为异常都属于测试范围，不承诺修复，
> 也不对存档/客户端/服务端损坏负责。敢用就是测试人员。

## 自包含

本目录包含：

- `src/`：agent 源码；
- `sanctuary-agent.jar`：预构建 agent（mod 会把它嵌进 jar）；
- `sanctuary-bootstrap.jar`：bootstrap stub；
- `sanctuary-win-x86_64.arcane`：Windows native 辅助库；
- `lib/asm-9.9.1.jar`：构建 agent 用的 ASM，避免依赖本机 Gradle cache；
- `build.ps1`：重新构建 agent。

`build.gradle` 里的 `processResources` 会把 `sanctuary-agent.jar` 打进 mod jar，
所以 clone 后不需要手动复制 `src/main/resources/sanctuary-agent.jar`。

## 重新构建

需要 Java 17：

```powershell
$env:JAVA_HOME = 'D:\Java\zulu17.58.21-ca-jdk17.0.15-win_x64'
.\sanctuary-agent\build.ps1
```

然后重新构建 mod：

```powershell
.\gradlew.bat build -Ptestbench
```

产物：`build/libs/golems_arsenal-*-testbench.jar`。

## 注意

- agent 只用于测试环境，不要放进发布版；
- 不要和正式存档一起用；
- 多模组环境下的稳定性不在承诺范围内；
- 如果 agent 构建失败，mod 仍能构建，但启动时会跳过 self-attach。
