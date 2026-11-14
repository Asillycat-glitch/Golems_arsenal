# 仆从充能链接（玩家物品充电 → 仆从）

> 2026-10-24。解决的问题：傀儡的 FE 池只能手动回收或雷击补充，战时没有补给手段；
> Powah / 通量网络等模组能给**玩家身上的物品**充电，但不认识傀儡实体。

## 1. 结论

做一张**带 Forge `IEnergyStorage` 的物品卡**，放在玩家背包里：

```
Powah 玩家供电仪 / 通量网络 Flux Controller / MEK 充电台
        ↓  IEnergyStorage.receiveEnergy
仆从充能链接（1,000,000 FE 缓冲）
        ↓  每 10 tick，按范围找“属于玩家、有能量能力”的仆从
模块化傀儡 / MEK Robit / 其它 OwnableEntity + Forge ENERGY
```

它不需要给 Powah/Flux 写硬依赖，也不需要把傀儡伪装成物品；任何会调用
`IEnergyStorage.receiveEnergy` 的充电器都能用。它只**接收** FE（`canExtract=false`），
不会反向抽干仆从或玩家的其它物品。

## 2. 各模组的实际行为（已对源码核实）

| 模组 | 充电入口 | 对这张卡意味着什么 |
|---|---|---|
| **Powah** | `PlayerTransmitterTile` → `ForgeEnvHandler.chargeItemsInPlayerInv`，遍历 **主背包 / 盔甲 / 副手**，并对每个 `ItemStack` 调 `receiveEnergy`；另有 `ChargeableItemsEvent` 允许追加额外 stack | 卡放在主背包/快捷栏/副手/盔甲槽即可被充电；无需写 Powah 代码 |
| **Flux Networks** | `FluxControllerHandler` 的无线模式，按 **主手 / 副手 / 快捷栏 / 盔甲 / Curios** 扫描，使用 `IItemEnergyConnector`；Forge 能量由 `ForgeEnergyConnector` 支持 | 卡放在快捷栏/副手/盔甲槽（或 Curios，如果装了）即可；**普通主背包不扫**，别只丢在背包里 |
| **Mekanism** | 本家没有“玩家无线充电器”；充电台/能量板按物品的 Forge/Mek 能量能力工作 | 卡有 Forge `IEnergyStorage`，能被支持 Forge 能量的充电设备充；Robit 需要单独的 Mekanism 适配器（已做） |
| **AE2WTLib** | 量子桥卡是**无线连接**（终端 ↔ 量子桥），不是玩家物品充电器；AE2 的能量是 AE 不是 FE | 不能直接复用；只借用“频率/无线链接”的设计思路。要把 AE 能量转成 FE 需要另外的转换模组/适配器 |

参考源码：
- Powah `ChargeableItemsEvent` / `ForgeEnvHandler.chargeItemsInPlayerInv`：<https://github.com/Technici4n/Powah>
- Flux Networks `FluxControllerHandler`：<https://github.com/SonarSonic/Flux-Networks>
- AE2WTLib `ItemWT` / 量子桥卡：<https://github.com/Mari023/AE2WirelessTerminalLibrary>

## 3. 实现

| 文件 | 作用 |
|---|---|
| `tech/energy/MinionChargeLinkItem` | 物品、128M FE 缓冲、Forge 能量能力、每 10 tick 分发 |
| `tech/energy/MinionEnergyAccess` | 统一“给实体插 FE / 判断主人”的入口；Forge ENERGY 优先 |
| `tech/energy/MinionChargeLinkEvents` | `PlayerTickEvent`，每 10 tick 扫主背包/副手/盔甲 |
| `tech/energy/MinionEnergyRegistry` + `ReflectionEnergyAdapter` + `MinionEnergyReloadListener` | 数据包反射适配器（私有 FE 字段） |
| `compat/mekanism/MekanismMinionEnergy` | Robit 适配器：`EntityRobit.getEnergyContainer().insert(..., AutomationType.EXTERNAL)` |
| `Config` | `minion_charge_link.buffer / bandwidth / range` |
| `assets/.../minion_charge_link` | 物品模型 + 16×16 贴图 |
| `data/.../recipes/minion_charge_link.json` | 金锭 + 红石 + 末影珍珠 |

### 目标筛选

1. 在玩家周围 `range`（默认 16 格）内；
2. `LivingEntity`、活着、不是玩家自己；
3. **主人 UUID = 该玩家**：
   - `AbstractGolemEntity#getOwnerUUID()`（模块化傀儡，含龙傀儡等扩展）；
   - `OwnableEntity#getOwnerUUID()`（其它模组的可驯服/可拥有仆从）；
   - 数据包声明的 `owner_field` / `owner_method`（没有前两者时）；
   - MEK `EntityRobit#getOwnerUUID()`（走适配器）；
4. 有能量且**没满**：Forge `IEnergyStorage`、数据包反射字段、或 Robit 能量容器；
5. 按距离排序，预算 `bandwidth × 10` 平均分给所有合法仆从。

### 数据包反射（给没有 Forge 能力的 FE 缓存）

如果某个生物把 FE 放在私有字段里、没有把实体注册成 Forge 能量提供者，可以加一条数据包：
`data/<namespace>/minion_energy/<name>.json`。加载时解析一次 `Field`，运行期只做
`get/set`。完整 schema 与示例见 [MINION-ENERGY-DATAPACK.md](MINION-ENERGY-DATAPACK.md)。

优先级：Forge 能力 → 数据包反射字段 → Robit 内置适配器。数据包只支持字段，不支持
只有 getter/setter 的缓存；也不支持实体标签（用 `entities` 列 id）。

### 传输

- 卡每 10 tick 结算一次；`bandwidth=20,000,000 FE/t` → 每次脉冲最多 200,000,000 FE，足够把 128M 缓冲整包倒空。
- 多个仆从平均分配；某个仆从满了/拒收的部分不会转给下一个（保持实现简单）。
- 卡内 FE 只来自外部充电器；没有仆从时留在卡里，等下一轮。
- 卡的 `extractEnergy` 永远返回 0，`canExtract=false`。

## 4. Config

```toml
[minion_charge_link]
    buffer = 128000000   # 单张卡的内部缓冲
    bandwidth = 20000000 # FE/t；每 10 tick 结算，单次最多 bandwidth×10
    range = 16.0         # 以玩家为中心找己方仆从的半径
```

> 旧版的 `capacity` / `transfer` 键已废弃；Forge 会保留它们但不会读取。
> 新键是 `buffer` / `bandwidth`，默认值与旧键不同。

## 5. 使用与测试

1. 合成“仆从充能链接”，放在**主背包/快捷栏/副手/盔甲槽**（Flux 无线模式要覆盖该槽位）。
2. 站到 Powah 玩家供电仪旁边，或打开通量网络控制器的无线充电；观察卡内 FE 上升。
3. 带着卡靠近自己的傀儡 / MEK Robit；每 0.5 秒应看到仆从 FE 上升。
4. 傀儡没有能量升级（容量 0）时不会被选中；先装基础电量 + 合金能源。
5. 仆从满电后会从候选里剔除，卡停止向它分配。

## 6. 限制与后续

- **Curios 槽暂不扫描**：卡在主背包/副手/盔甲槽；Flux 的 Curios 模式可以给它充电，但我们的分发 tick 不会扫 Curios。需要的话可以加 Curios 软兼容。
- **只支持有 Forge 能量能力、数据包反射字段、或 Robit 适配器的仆从**；其它模组的“能量存在物品里”的仆从需要单独适配。
- **数据包反射只支持字段**（`IEnergyStorage` 或 int/long/float/double），不支持只有 getter/setter 的缓存、Mekanism `FloatingLong` 字段、实体标签；详见 [MINION-ENERGY-DATAPACK.md](MINION-ENERGY-DATAPACK.md)。
- **不发电**：必须有 Powah/Flux/MEK 充电台等外部电源；卡只是中转。
- **10 tick 结算**：不是每 tick 平滑传输；平均速率是 `bandwidth`，瞬时是每 0.5 秒一批。
- **AE2WTLib 没有直接接入**；它是无线终端/量子桥，不是 FE 充电器。若需要“AE 网络给仆从供电”，需要 AE→FE 转换层，属于另一个设计。
- 这套方案对**所有**会调 `IEnergyStorage.receiveEnergy` 的玩家物品充电器都生效；以后再加别的充电模组，通常不需要改代码。
