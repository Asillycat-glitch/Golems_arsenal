# 通用防御的强度与性能设计（2026-11-15）

> 参考 12:02 的针对判断法（规则 01–07）：只有“身份标识 + 预先规则 + 用于选择对抗策略”三要件同时成立，才构成针对。
> 本文件的目标是：不靠对手类名/包名，仍然把防御做得更强。

## 1. 结论

全量搜索本身不会自动变强。它只是覆盖面变大；真正的强度来自：

1. **检查点位置**：写入口、读入口、移除、索引、客户端 tracking、墓碑；
2. **不变量**：受保护实体在生命周期窗口外，不允许任何外部写入/删除；
3. **代价控制**：类加载期建索引，运行期只扫候选，不做周期性全 JVM 反射。

“扫更多类”不如“在正确的位置拒绝一次”。

## 2. 当前全量扫描的缺口

日志已经给出一个具体例子：

- 服务端 `RemovalGuard` 拒绝移除，`/ga dump` 仍能看到傀儡；
- 客户端碰撞箱消失；
- 继续攻击仍能锁定原位置。

说明服务端对象保住了，但**客户端 tracking / 移除包**没保住。这个缺口不在“扫描范围”，而在“检查层”：

```text
服务端实体状态  ✔
客户端实体 tracking  ✘
```

只扩大 Scrubber 扫描范围，无法补这一层。

## 3. 技术条件（不写对手名字）

以下条件都对所有对象一视同仁：

| 层 | 技术条件 | 处理 |
|---|---|---|
| 写 | `setHealth` / `DataItem` 血量槽 / maxHealth / `setRemoved` / `remove` / `kill` / `die` | 非 admitted 窗口拒绝 |
| 读 | `isRemoved` / `getRemovalReason` / `getHealth` / `isAlive` | 受保护实体回答“仍存在”，并清 raw 字段 |
| 索引 | `ClassInstanceMultiMap` / `EntitySection` / `ChunkMap` 移除 | 受保护 ID 拒绝 |
| 位置 | `setPosRaw`、直接写 x/y/z、NaN/Infinity、单步位移过大、delta 过大 | 拒绝并回退最后正常位置 |
| 客户端 | `ClientboundRemoveEntitiesPacket` / tracking 移除 | 受保护 ID 不删；必要时 remove+add 重建 tracking |
| 墓碑 | 静态 `Map`/`Collection` 中含受保护 UUID 或 UUID 字符串 | 清除条目，不删实体 |
| 危险机制 | `ClassFileTransformer` / `Instrumentation` / `Unsafe` / `MethodHandle` / `ClassRestore` | 记录并纳入通用恢复防护 |

这些是**技术行为条件**，不是“某个 mod 的身份指纹”。

## 4. 性能：把全量扫描改成类加载索引

参照 AV 的 `MasterTransformer`：

- `ClassFileTransformer` 在类加载时读取字节码；
- ASM 只记录：
  - static `Map`/`Collection` 字段名；
  - 对 `setHealth`/`setRemoved`/`remove`/`kill`/`die` 的调用点；
  - 对 `Entity.x/y/z`、`DataItem.value`、`removalReason` 的字段写入；
  - 反射/MethodHandle/Unsafe/Instrumentation 的使用；
- 结果放进 `ClassIndex`，按类名缓存；
- 运行期只处理 **候选字段/候选调用点**，不再 `getAllLoadedClasses + getDeclaredFields`。

这样覆盖仍接近全量，但成本接近“零扫描”。

### 禁止项

- 不在 Scrubber 线程对任意对象 `getClass().getMethod("getUUID")`；
- 不对所有类 `getDeclaredFields()`；
- 不因为某个类“来自对手包”而特殊处理。

## 5. 提高强度的顺序

1. **客户端 tracking 守卫**
   - 受保护实体被服务端保留时，不允许 `ClientboundRemoveEntitiesPacket` 把它从客户端删掉；
   - 如果已经删掉，按 UUID 做 remove + add 重建 tracking；
   - 这是当前“碰撞箱消失但服务端还在”的直接修复层。
2. **读侧字段清理**
   - `isRemoved`/`getRemovalReason` 被读到时清 raw `removalReason`；
   - 避免 `ServerEntity` 按 raw 字段继续发移除包。
3. **写侧通用门**
   - 所有 mod 类的 `setHealth`/`setRemoved`/`remove`/`kill`/`die` 调用重写到统一 `GuardRuntime`；
   - 反射/句柄写由宿主 API 栈条件拦截。
4. **提交/移除索引守卫**
   - `ClassInstanceMultiMap`、`EntitySection`、`ChunkMap` 的移除按“受保护 ID + removal reason”统一判定。
5. **墓碑索引**
   - 类加载期收集静态集合字段；
   - 周期只扫候选字段，清 UUID/String 墓碑。
6. **重复实体清理**
   - UUID 冲突/同 UUID 多实体时，保留一个有 tracking 的，另一个走正常 discard；
   - 这是存档卫生，不是对手识别。

## 6. 与“针对”规则的对应

- 规则 01/03/04：不写对手类名、包名、物品 ID、样本哈希；身份只用于日志。
- 规则 05：使用“直接写血/移除/反射/巨大位移”等技术条件，而不是“只有 A 会这样”的指纹。
- 规则 06：机制可以只覆盖一种攻击/一个版本，只要同技术条件的其他对象也走同一处理。
- 规则 07：只保留“Minecraft 自身 / 测试方自己”的排除；不对其他对象默认例外。

## 7. 当前优先级

当前最值得做的不是继续扩大 Scrubber，而是：

```text
客户端 tracking 守卫 / remove+add 重建
  > 读侧 raw 字段清理
  > 写侧通用门
  > 类加载索引
  > 更广的墓碑扫描
```

客户端那一层补上后，服务端“还活着”和客户端“看得见”才会一致。
