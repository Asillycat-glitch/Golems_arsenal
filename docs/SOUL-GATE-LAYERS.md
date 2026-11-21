# Soul 与统一实体门的分层清单（2026-11-16）

目标：确认没有漏掉“看起来无关、实际不可逆”的层。
谓词统一为：受保护傀儡 + Soul 活着 + 非正常生命周期。
不按 mod 名、类名、物品 ID 判断。

## 1. 健康值层

- [x] `LivingEntity.setHealth`
- [x] `SynchedEntityData.set(DATA_HEALTH_ID)`
- [x] `SynchedEntityData.DataItem.setValue`
- [x] `getHealth` / `getMaxHealth` 读侧
- [x] `AttributeInstance` 最大生命/基础值
- [ ] `LivingEntity.die` 后的 `setHealth`
- [ ] `EntityDataAccessor` 被替换/重新注册
- [ ] JNI/Unsafe/反射写 health 字段（DLL 当前未启用）

Soul 需要：`health / maxHealth / lastGoodHealth / died`。

## 2. 死亡与掉落层

- [ ] `LivingEntity.die`
- [x] `LivingDeathEvent` 取消/审计
- [x] `DropLootGuardMixin` 阻止敌对掉落
- [ ] `dropAllDeathLoot` 前的库存/升级备份
- [ ] `deathTime / dead / removalReason` 三字段一致性
- [ ] `tickDeath` 清尸体/移除
- [ ] `LivingEntity.onDeath` 的额外副作用

Soul 需要：`inventoryDigest / upgradesDigest / deathEpoch`，并在真正死亡前拍快照。

## 3. 移除与索引层

- [x] `Entity.setRemoved`
- [x] `Entity.onRemovedFromWorld`
- [x] `Entity.setLevelCallback(NULL)`
- [x] `EntityLookup.remove`
- [x] `EntitySection.remove`
- [x] `EntityTickList.remove`
- [x] `ClassInstanceMultiMap.remove`
- [ ] `PersistentEntitySectionManager` 的 add/remove 回调
- [ ] `ChunkMap.TrackedEntity` 的 tracking 移除
- [ ] `ServerEntity` 的 stop tracking
- [ ] `ServerLevel` 的 entity callback
- [x] `ClientLevel.removeEntity`
- [x] `ClientboundRemoveEntitiesPacket` 两个构造器
- [ ] 自定义 remove packet（最终会落到 ClientLevel，但需要测试）

Soul 需要：`projectionGeneration / expectedProjectionId`；旧投影被移除时不重建旧对象，而是生成新投影。

## 4. 位置/物理层

- [x] `Entity.setPosRaw` 非有限/远距离
- [x] 每 tick START 位置修复
- [x] delta 上限
- [ ] 直接写 `x/y/z` 字段
- [ ] `setDeltaMovement` 的极端值
- [ ] `teleportTo` / 跨维度
- [ ] `moveTo` 的异常坐标
- [ ] `EntitySection` 位置迁移
- [ ] 碰撞扫描（Radium/Lithium）
- [ ] 区块未加载/世界边界

Soul 需要：`lastGoodPos / dimension / lastGoodTick`。

## 5. 实体身份层

- [x] `IdentityGuard` 不再在移除时旋转 UUID
- [ ] `Entity.setUUID`
- [ ] `EntityLookup.byUuid / byId`
- [ ] `knownUuids`
- [ ] `GolemTracker` 的 UUID 表
- [ ] 外部模组按 UUID 存的 buff/冷却
- [ ] projection UUID 与 soulId 的映射
- [ ] 重复 UUID/双实体清理

Soul 需要：稳定 `soulId`；projection UUID 可换。

## 6. 客户端/tracking 层

- [x] `ClientLevel.removeEntity`
- [x] `ClientboundRemoveEntitiesPacket`
- [x] 客户端 `isRemoved` 读侧
- [x] 客户端血量 0 护盾
- [ ] `ClientboundAddEntityPacket` 重建
- [ ] `ChunkMap.TrackedEntity` 重建
- [ ] `ServerEntity` add/remove 对
- [ ] `ClientboundSetEntityDataPacket` 血量同步
- [ ] 自定义 payload 的 remove 语义
- [ ] Jade 查询的实体必须真实在 `EntityLookup`

Soul 需要：`ProjectionRebuild`，不是 `addFreshEntity` 旧对象。

## 7. NBT / 升级 / 库存 / 持久化层

- [x] `LivingEntityEquipmentNbtRefreshMixin`（部分）
- [ ] `Entity.saveWithoutId`
- [ ] `Entity.load`
- [ ] `readAdditionalSaveData` / `addAdditionalSaveData`
- [ ] Modular Golems 的 upgrades/inventory capability
- [ ] `GolemTracker`
- [ ] `HealthLedgerData`
- [ ] `DiskShield` 的存档镜像
- [ ] 区块 NBT/实体 NBT 被外部改写

Soul 需要：不可由 projection 直接生成的 NBT 摘要/备份。

## 8. Native / 反射层

- [ ] JNI `SetObjectField / SetFloatField / SetDoubleField`
- [ ] `Unsafe` / `MethodHandle`
- [ ] 反射写 `removalReason / health / x/y/z`
- [ ] `Instrumentation.redefineClasses`
- [ ] `Instrumentation.retransformClasses`
- [ ] `ClassFileTransformer`
- [ ] `ClassRestoreManager / PristineSealer`

当前 DLL `sanctuary_jvmhook` 加载了但 `NativeLoader` 主动不 install；这一层现在是空的。

## 9. 线程 / 权威状态层

- [x] `GuardAudit OFF_THREAD` 记录
- [ ] 权威 Soul 不依赖 server thread
- [ ] write journal / revision
- [ ] authority thread
- [ ] 独立进程 IPC

## 10. 命令 / 外部入口层

- [ ] `/kill`
- [ ] `/data merge entity`
- [ ] `/execute ... run kill`
- [ ] `/summon` 复制
- [ ] 世界边界/虚空
- [ ] 玩家回收手杖
- [ ] 区块卸载/跨维度

## 11. 结论

当前门覆盖了：

```text
血量写
移除方法
部分索引
位置
客户端移除
属性
```

没有覆盖或只部分覆盖：

```text
死亡/掉落/库存
NBT/升级持久化
ChunkMap/ServerEntity tracking
自定义 payload
UUID/重复实体
native/Unsafe/反射
线程/权威状态
命令入口
```

所以真正要做的不是继续加 readd，而是：

```text
Soul 保存不可逆状态
UnifiedEntityGate 覆盖终态
ProjectionRebuild 替代旧对象恢复
```

这份清单作为实现时的逐层验收表。
