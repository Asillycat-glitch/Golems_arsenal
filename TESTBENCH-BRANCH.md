# testbench 分支

> **测试产物，不保证强度和稳定性。**
> 这个分支包含自附加 Java agent、ASM 类转换、测试 mixin 和攻击面复现代码；
> 多模组环境下的崩溃、卡死、存档异常都属于测试范围，不承诺修复，也不对后果负责。
> **敢用就是测试人员。**

这个分支专门存放 **测试 / 学习代码**，不参与 main 的发布内容。

## 内容

- `testbench/`：私有测试台源码、mixin、资源、测试记录；
- `docs/`：攻击面、威胁模型、防御分层、agent/mod 审计等学习文档；
- 生产代码仍来自 main；本分支只是在 main 之上叠加这些文件。

## Agent（自包含）

`sanctuary-agent/` 里同时放了源码、预构建 `sanctuary-agent.jar`、bootstrap stub、
Windows native 和构建用的 `lib/asm-9.9.1.jar`。`build.gradle` 的 `processResources`
会直接把 `sanctuary-agent/sanctuary-agent.jar` 嵌进测试 jar，不需要手动复制到
`src/main/resources`。

重新构建：

```powershell
$env:JAVA_HOME = '<JDK17>'
.\sanctuary-agent\build.ps1
.\gradlew.bat build -Ptestbench
```

agent 是 self-attach + retransform + 静态字段扫描，**只用于测试环境**；它不保证和
其他 agent/mixin/多模组环境兼容。

## 构建

本 worktree 是完整工程，可以直接构建：

```powershell
.\gradlew.bat build -Ptestbench
```

产物是 `build/libs/golems_arsenal-<version>-testbench.jar`，不会被普通 `build` 打包，
也不应该上传到发布页。

## 约束

- **不要把这个分支 merge 回 main**，也不要把 `testbench/` 直接复制进 main 的工作树再提交；
- 测试代码里可能包含攻击模拟、调试用 `mixin`、对手行为复现，仅在自己的测试环境使用；
- 如果某个测试结论要进入 main，只把结论/接口/防线代码按正常 review 流程带过去，不带测试台；
- 上游工程：[Asillycat-glitch/Golems_arsenal](https://github.com/Asillycat-glitch/Golems_arsenal)。

## 目录

```
testbench/
  java/        测试台 Java 源码（含 mixin）
  resources/   测试台 mixin 配置 / refmap
  *.md         各轮测试记录（P0/P1/P2、Sanctuary、HealthTrace…）
docs/          攻击面 / 威胁模型 / 防御分层 / 审计记录
```
