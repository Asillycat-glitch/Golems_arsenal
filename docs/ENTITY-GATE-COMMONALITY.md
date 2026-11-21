# 实体攻击面的共通性（2026-11-16）

样本：ArcaneVortex、toaru、supersteve、pigzilla、miztinker、momotinker、witherzilla。
目标：找出不依赖类名/包名/物品 ID 的“门”，并说明为什么之前的门看起来没用。

## 1. 结论

这些模组的实现方式不同，但最终都落到同一组**终态操作**：

```text
健康值
  LivingEntity.setHealth
  SynchedEntityData.set(DATA_HEALTH_ID)
  SynchedEntityData.DataItem.setValue
  反射/JNI 写 health 字段

移除
  Entity.setRemoved
  Entity.onRemovedFromWorld
  Entity.discard / kill
  EntityLookup.remove
  EntitySection.remove
  EntityTickList.remove
  PersistentEntitySectionManager / ChunkMap 回调

位置
  Entity.setPosRaw
  直接写 x/y/z
  巨大/NaN 位移

客户端
  ClientLevel.removeEntity
  ClientboundRemoveEntitiesPacket
  自定义 remove packet → 客户端再次 kill

native
  JNI SetObjectField / SetFloatField / SetDoubleField
  Unsafe / MethodHandle
```

**真正的通用门不是“谁调用”，而是“终态操作 + 受保护实体状态”。**

## 2. 各模组的共通做法

| 模组 | 看到的路径 | 终态 |
|---|---|---|
| ArcaneVortex | `HealthModify.entityDataModify/tagModify/attributeModifier`；`EntityKillerHelp.tickDeath/setRemoved/remove`；`NativeAgentHealthMethod` | DataItem / removal / native health |
| toaru | `GungnirHealthCell`、`GungnirHealthStorage`、`GungnirWorldRemoval`、`GungnirLookupAccess`、`GungnirTickIndex`、`GungnirVitalAnchor` | health / lookup / tick / removal |
| supersteve | `SSUtil.killEntity`：raw `removalReason`、`EntityLookup`、自定义 `RemoveClientEntity` | raw field + index + client packet |
| pigzilla | `target.discard()` | `DISCARDED` |
| miztinker | `LivingEntitySetHealthMixin`、`EntityRemoveUtil`、`ForceHurtUtil` | setHealth / remove / force hurt |
| momotinker | `PenetratingDamage` 直接写 `DATA_HEALTH_ID`，再 `die` | DataItem + die |
| witherzilla | 对 `EntityLookup`、`EntitySection`、`EntityTickList`、`ClientLevel`、`SynchedEntityData`、`LivingEntity`、`ServerLevel` 同时 mixin，统一调用 `ModUtil.safeEntity` | 同一组终态 |

witherzilla 的写法最值得学架构，不值得学谓词：

```text
一个 safeEntity(Entity) 谓词
  → 同时挂在 EntityLookup/Section/TickList/ClientLevel/SynchedEntityData/LivingEntity
```

它的谓词是“自己的 Boss 实体”，属于身份针对；
我们只要把谓词换成**受保护傀儡状态**，架构就是通用的。

## 3. 通用谓词

```java
boolean isProtected(Entity e) {
    if (!(e instanceof AbstractGolemEntity<?, ?> golem)) return false;
    if (golem.getOwnerUUID() == null) return false;
    GolemSoul soul = SoulRegistry.soulFor(golem);
    if (soul == null || soul.died) return false;
    if (RemovalGuard.isAllowedLifecycle(golem)) return false;
    return true;
}
```

要求：

- O(1) 映射；
- 不扫类名；
- 不扫 mod 列表；
- 不做反射；
- 不依赖调用者身份；
- 内部重建用 thread-local scope 放行。

## 4. 门的层次

### 第一层：健康值终态

```text
LivingEntity.setHealth
SynchedEntityData.set(DATA_HEALTH_ID)
DataItem.setValue
```

受保护实体：

- 非法数值 → 拒绝；
- 非准入下降 → 拒绝；
- 合法大伤害 → 放行；
- 若已经落到 0，标记投影异常，从 Soul 恢复。

### 第二层：移除终态

```text
Entity.setRemoved
Entity.onRemovedFromWorld
Entity.setLevelCallback(NULL)
EntityLookup.remove
EntitySection.remove
EntityTickList.remove
```

受保护实体：取消操作，不记录后继续。

当前 `IndexGuard` 已经在部分点记录，但允许了后续操作，导致“门响了，实体还是没了”。

### 第三层：客户端终态

```text
ClientLevel.removeEntity
ClientPacketListener.handleRemoveEntities
自定义 remove packet 的最终 ClientLevel.removeEntity
```

受保护 entityId：取消。

重建时用一次性 allow，允许旧投影被删。

### 第四层：重建

```text
不要 addFreshEntity 旧对象
  → 从 Soul 生成新投影
  → 服务端注册
  → 客户端 remove old + add new
```

## 5. 为什么当前的门看起来没用

日志证明它们触发过：

```text
DamageAdmission rejected negative health
client_remove_blocked
ATTRIBUTE_BASE_BLOCKED
INDEX-DENIED
```

但：

```text
REINDEX-ADD-FAILED
preRemoved=true
dump=0
```

说明：

1. 门只挡住了某一次写；
2. 另一条终态操作已经把实体从世界摘掉；
3. 旧对象无法再 `addFreshEntity`；
4. `watchdog readded` 只是循环，不是恢复。

所以问题不是“门太少”，而是：

```text
门没有覆盖所有终态
  +
恢复策略错了（救旧对象）
```

## 6. 建议实现

```text
ProtectedEntityPolicy
  isProtected(entity)

UnifiedEntityGate
  gateSetHealth
  gateDataHealth
  gateSetRemoved
  gateOnRemoved
  gateLookupRemove
  gateSectionRemove
  gateTickListRemove
  gateClientRemove

ProjectionScope
  REBUILDING ThreadLocal
  允许内部 add/remove

ProjectionRebuild
  from Soul
  client remove+add
```

Agent 侧只做两件事：

1. 类加载期找出所有调用/写这些终态的 mod 类；
2. 给没有 mixin 覆盖的入口补字段/调用门。

不再写：

```text
if (mod == ArcaneVortex) ...
if (item == ...) ...
```

## 7. 与针对规则的关系

- 谓词基于“实体是否受保护/是否活/是否正常生命周期”；
- 不基于对手类名、包名、物品 ID、哈希；
- 对任何 mod 的同样终态操作一视同仁；
- 身份只用于日志；
- 特殊对象（Minecraft 自身、我方）走统一例外。

这是一条**技术条件**，不是身份指纹。
