# 仆从能量反射数据包（v1）

> 给“有 FE 缓存、但没有挂 Forge `IEnergyStorage` 能力”的生物用。
> 路径：`data/<namespace>/minion_energy/<name>.json`；`/reload` 生效。

## 1. 为什么是数据包

不同模组把 FE 存在不同地方：Forge 能力、Mekanism `IEnergyContainer`、自己的私有
`int/long` 字段……给每个模组写一个硬编码反射类会越来越大，而且新模组一换字段名就失效。

这里只保留**一种最小反射形状**：数据包告诉我们在哪个字段上读/写 FE；加载时解析一次
`Field`，运行期只做 `get/set`。没有按模组写死类名，也不在每 tick 查类名。

优先级（`MinionEnergyAccess`）：

1. 实体自己的 Forge `IEnergyStorage`；
2. 本数据包的反射适配器；
3. 内置的 Mekanism Robit 适配器（`getEnergyContainer()`）。

所以：**能用 Forge 能力的生物根本不需要写 JSON**；数据包只服务第 2 类。

## 2. 路径与 reload

```
datapacks/my_minion_energy/
  pack.mcmeta
  data/mymod/minion_energy/robot.json
```

`pack.mcmeta`（1.20.1 数据包格式）：

```json
{
  "pack": {
    "pack_format": 15,
    "description": "My minion energy reflection adapters"
  }
}
```

- 服务端数据包；单人游戏放在存档的 `datapacks/`，服务器放在世界的 `datapacks/`。
- 加载/修改后执行 `/reload`。
- 日志里会有 `[minion_energy] loaded N reflection adapters`；解析失败会打印
  `[minion_energy] <file>: ...` 并跳过该条，不会让 reload 失败。
- 只按**精确实体类型**匹配；一条 JSON 可以用 `entities` 列多个 id。

## 3. JSON 形状

```json
{
  "entity": "mymod:robot",
  "field": "energy",
  "energy": "forge_energy_storage",
  "owner_method": "getOwnerUUID",
  "charge_unowned": false
}
```

| 键 | 必填 | 说明 |
|---|---|---|
| `entity` | 二选一 | 单个实体 id，例如 `mymod:robot` |
| `entities` | 二选一 | 实体 id 数组，多个类型共用同一套字段 |
| `field` | ✅ | 实体类（含父类）上的字段名；私有字段会 `setAccessible(true)` |
| `energy` |  | `forge_energy_storage`（默认）/ `int` / `long` / `float` / `double` |
| `capacity` | 数字字段必填* | 容量常量；与 `capacity_field` 二选一 |
| `capacity_field` | 数字字段必填* | 容量所在字段（数字类型） |
| `owner_field` |  | 主人字段；值可以是 `UUID` / `Player` / `Entity` |
| `owner_method` |  | 无参主人方法；返回值同上 |
| `charge_unowned` |  | `true` = 不检查主人；默认 `false` |

\* 数字字段（int/long/float/double）必须给 `capacity` 或 `capacity_field`，否则不会充能。
`forge_energy_storage` 的容量由 `IEnergyStorage` 自己提供，不需要这两个键。

### 3.1 字段是 Forge `IEnergyStorage`

```json
{
  "entity": "mymod:robot",
  "field": "energyStorage",
  "energy": "forge_energy_storage",
  "owner_method": "getOwnerUUID"
}
```

- `field` 的类型必须是 `net.minecraftforge.energy.IEnergyStorage`（或其子类）；
- 充能直接调 `receiveEnergy`；
- 适合“作者把电池对象放在字段里、但没有把实体注册成能力提供者”的情况。

### 3.2 字段是数字

```json
{
  "entity": "mymod:robot",
  "field": "energyStored",
  "energy": "int",
  "capacity_field": "energyCapacity",
  "owner_field": "ownerUUID"
}
```

- 读 `energyStored`，按 `energyCapacity - energyStored` 计算可接受量；
- 非模拟时把新值写回同一个字段；
- 支持 `int / long / float / double`（以及 `Integer/Long/Float/Double/Number`）。

### 3.3 主人

```json
{
  "entity": "mymod:robot",
  "field": "energy",
  "energy": "forge_energy_storage",
  "owner_method": "getOwner"
}
```

- `owner_method` / `owner_field` 的返回值可以是：
  - `UUID`：和玩家 UUID 比较；
  - `Player`：取 `getUUID()`；
  - `Entity`：取 `getUUID()`。
- 不写 owner 时，会先走 `AbstractGolemEntity#getOwnerUUID` / `OwnableEntity#getOwnerUUID`；
  两者都没有时返回“不是你的”，不会充能。
- `charge_unowned: true` 时不看主人；用于无主机器人/公共设施，慎用。

### 3.4 多个实体共用

```json
{
  "entities": ["mymod:robot_a", "mymod:robot_b"],
  "field": "energy",
  "energy": "long",
  "capacity": 200000,
  "charge_unowned": true
}
```

## 4. 运行期行为

- 加载时解析 `Field`（父类链也会找），缓存；运行期 `Field.get` / `Field.set`。
- 反射失败只打印一次 `[minion_energy] reflection failed for ...`，之后该实体返回 0/false。
- 如果字段是 `final`、或被 Java 模块系统挡住 `setAccessible`，会解析失败/运行失败并写日志；
  这种情况只能让作者补一个普通字段或能力，数据包解决不了。
- 数据包只负责“读/写 FE 缓存”，不创建 FE 缓存，也不改变实体 AI。

## 5. 限制（v1）

- **字段名必须是运行时字段名**。模组自己的字段通常不做混淆；Minecraft 原版成员在生产环境是
  SRG 名（开发环境是 official 名），所以不建议对原版实体写反射条目——优先用 Forge 能力。
- **只支持字段**，不支持“只有 getter/setter 方法”的缓存；也不支持嵌套字段、List/Map。
- **不支持实体标签**：同一个字段名不一定在标签里每个类上都存在；需要给每个 id 写一条，
  或用 `entities` 列出来。
- **不支持 Mekanism `FloatingLong` 字段**（v1）；Robit 走内置适配器。以后可以加
  `energy: "mekanism_floating_long"`，但那需要单独的 MEK 软兼容层。
- **Forge 能力优先**：实体已经有 Forge `IEnergyStorage` 时，数据包字段不会被使用。
- **主人接口优先**：实体是 `AbstractGolemEntity` / `OwnableEntity` 时，先按它们的 owner 判断；
  数据包的 owner 只在这两者都没有时生效。

## 6. 安全提醒

数据包可以让反射读取/写入任意字段名。它是**服务端管理员信任的内容**，和 KubeJS/数据包
脚本同级；不要给不可信玩家数据包权限。v1 只做 `Field.get/set`，不调用任意方法/构造器，
但这仍然能改私有状态。
