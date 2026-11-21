# 匠战妖域防御发行包（2026-11-19）

## 包内容

- `golems_arsenal-0.3.jar`：主 mod，已内嵌 `sanctuary-agent.jar` 与 `sanctuary-supervisor.jar`
- `sanctuary-agent.jar`：独立 Java agent（备用/调试）
- `sanctuary-supervisor.jar`：独立进程 supervisor
- `install-to-testpack.ps1`：备份旧 jar 并安装到测试实例
- `start-supervisor.ps1`：手动启动 supervisor（模组也会在 ServerStartedEvent 自动启动）
- `native/README.md`：native/JVMTI 层说明

## 已激活的防御

- Java agent：所有 `/mods/` 类加载时过字段门/方法调用门（不按对手类名）
- 通用终态门：`setRemoved` / `onRemovedFromWorld` / `invalidateCaps` / 容器索引 / 客户端 entity storage
- 独立 `SanctuaryGuardThread`：100ms 把对账任务压回服务器线程
- 独立 supervisor：监控游戏 PID，进程结束写 journal；可选重启命令
- 客户端 `ClientIndexEvents`：登录/Level Load 安装客户端索引门

## native 层状态

当前发行包**没有**新的 JVMTI DLL 二进制。原因：当前构建机没有 MSVC/MinGW，旧 `sanctuary-win-x86_64.arcane`
是全局 JNI 表 hook，已确认会崩服，不能作为默认加载项。native 层设计见 `native/README.md`；
有编译环境后可把 `sanctuary-jvmti.dll` 放进 `native/win-x64/` 并接入 `SupervisorBridge`/agent 加载链。

## 安装

```powershell
powershell -ExecutionPolicy Bypass -File .\install-to-testpack.ps1
```

旧 jar 会自动备份为 `*.bak-yyyyMMdd-HHmmss`。

## 启动后确认

```
[Sanctuary] guard thread started interval=100ms
[Sanctuary] supervisor started from ...\config\golems_arsenal\supervisor\sanctuary-supervisor.jar
[IndexGuard] installed on server start: index.wrapped=...
```

supervisor journal：

```
config/golems_arsenal/supervisor/supervisor.log
config/golems_arsenal/supervisor/supervisor.out
```

## 测试口径

- `/ga guard dump 32` 看 `visible/noPhysics/bb/pickable/collidable/added/canUpdate/removed`
- AV `van_sh_radiant_sun` 左/右/Shift+右，观察是否还出现 `client_remove_blocked` / `forcefully removed`
- 若游戏进程被杀，supervisor 应留下 `game process ended pid=...`；重启命令默认空，可在手动启动时传 `-Restart`
