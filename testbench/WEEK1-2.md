# Week 1 / Week 2：TimeWindow 与 GuardRuntime

> 构建：**1,007,913 B / SHA `3E09E8DC…7516BD4` / 2026-11-03 22:00**，已安装。
> 单测：**20/20 通过**（`.\gradlew unitTest`；包含 Week 3 的 HealthAuditMath）。

## Week 1：TimeWindow

`util/TimeWindow` 是纯值对象：

```java
record TimeWindow(long until, long cooldown) {
    TimeWindow clamp(long now);
    boolean scheduled();
    boolean active(long now);
    boolean due(long now);
    TimeWindow after(long now);
    void write(CompoundTag tag, String key);
}
```

`GolemsArsenalSetHandler` 的 5 个计时 key 全部收进它：

| key | cooldown |
|---|---|
| `GolemsArsenalSetRefill` | 1200 |
| `GolemsArsenalSetPillarCd` | 600 |
| `GolemsArsenalSetPillarUntil` | `LightPillarEntity.bloomDurationTicks()` |
| `GolemsArsenalSetGenmuCd` | 600 |
| `GolemsArsenalSetGenmuVolley2` | 6 |

`clamp(now)` 的规则：

- `until == Long.MAX_VALUE` → `now + MAX_WINDOW_TICKS`（一年，饱和加法）；
- `cooldown < 0` → `now + MIN_COOLDOWN_TICKS`；
- `until < now` → `0`（未排期/已过期）；
- 其余保持。

单测 `TimeWindowTest`：

- `maxUntilClampsToNowPlusMaxWindow`
- `negativeCooldownClampsToMinCooldown`
- `expiredUntilBecomesZero`
- `futureUntilStays`
- `afterSchedulesFromNowUsingCooldown`

## Week 2：GuardRuntime

删掉旧的 `GuardPolicy` 静态缓存，改为：

```java
GuardMode { OFF, SHADOW, ENFORCE }
GuardRuntime.state() -> GuardState(mode, detectedMods, configLoaded)
```

- `ModConfigEvent.Loading/Reloading` → `refresh(true)`；
- `refresh` 读 `[guard] auto_enable` / `blacklist_mods`，用 `ModList.isLoaded` 记录命中 mod；
- 自动最多到 `SHADOW`，**永不自动 ENFORCE**；
- `/ga guard status` 与 `/ga guard refresh`；
- 状态是不可变 record + `volatile` 发布，没有“第一次算完就永远缓存”的问题。

单测 `GuardRuntimeTest`：

- 配置未加载 → OFF、`configLoaded=false`；
- 已加载但黑名单无命中 → OFF；
- 命中 fantasy_ending → SHADOW，记录 `fantasy_ending`；
- 多个命中时 detected 按字典序输出（`[arcanevortex, fantasy_ending]`），不随 Set 实现漂移；
- `auto_enable=false` → OFF；
- reload 后黑名单清空 → 从 SHADOW 回 OFF。

## 测试命令

```text
.\gradlew unitTest
```

`test` 任务在这台机器上被禁用：Gradle Test worker 会把 classpath 写进 `@argfile`，
中文路径在 worker 里按平台编码解码会变成乱码，导致 `ClassNotFoundException`；
`unitTest` 用 `JavaExec` 直接传 classpath，跑的是同一批 JUnit 类。

游戏内：

```text
/ga guard status
/ga guard refresh
```

期望：

```text
[guard] mode=OFF configLoaded=true detected=[]
```

如果装了 fantasy_ending，且 `[guard] auto_enable=true`、`blacklist_mods` 含它：

```text
[guard] mode=SHADOW configLoaded=true detected=[fantasy_ending]
```
