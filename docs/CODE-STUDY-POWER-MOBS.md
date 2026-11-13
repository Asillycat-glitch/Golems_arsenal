# 强力生物代码研究：SuperSteve 与 鬼巫女（Onimiko）

> 对象：`super-steve-1.7.1.3shit.jar`、`oni_miko.jar`（`Tinkers' Warfare Demon Realm` 包内）。
> 方法：JDK 17 + Vineflower 1.10.1 反编译到 `build/decomp/`（未进版本库），读的是反编译源码；
> 文中 `m_xxxxx_` 是 SRG 名，括号里是语义。**这不是运行验证**，是静态代码阅读。
> 目的：学"强力生物"的实现套路，并标出哪些能抄、哪些不能抄。

---

## 0. 先说结论

这两个 boss 的"强"都不在数值，而在**绕开事件管线**：

| 做法 | SuperSteve | 鬼巫女 |
|---|---|---|
| 自定义血量 | `setHealth` 空实现，`getHealth` 读同步字符串 | 同步 `Float` 字段，`setHealth` 有 ±20/±10 的增量门 |
| 每击上限 | 伤害按 `amount / 玩家背包最大伤害` 缩放，再乘 10–20% | 硬上限 20（P1）/ 10（P2），超出直接截断 |
| 反伤 | `forceHurt(player, amount, true)` 直写玩家血 | `attacker.setHealth(attacker.getHealth() - amount)` + `hurt(1)` |
| 免死/复活 | `killEntity` 硬移除；`safeEntity` 重新加回世界 | `remove` 只放行 UNLOADED/CHANGED_DIMENSION；KILLED 且血>0 时重生 |
| 免传送/免击退 | `setPos`/`setDeltaMovement`/`teleportTo`/`push` 全部覆盖 | `setPos` 忽略 >10 格位移；tick 里拉回 `lastX/Y/Z` |
| 多线程 | **裸 `SS-Thread`**，每 1ms 从服务器线程外调 `ssTick(true)` | 不用线程，用 `TickLateEvent` 延迟任务 |
| 底层手段 | `Unsafe` 改 klass 指针 + `defineHiddenClass` 换玩家类 | 只用到常规实体 API + `setRemoved` |

**一句话**：SuperSteve 是"进程内 rootkit"，鬼巫女是"把伤害管线全部改写成自己规则"的raid boss。
前者不可学（也不该学），后者有很多可复用的防守模板。

---

## 1. SuperSteve：一个"进程内 rootkit"式的 boss

### 1.1 类结构

```
plz.lizi.supersteve
├── api/
│   ├── PLZBase              // Unsafe + IMPL_LOOKUP + defineHiddenClass + klassPtr
│   ├── SSUtil               // killEntity / safeEntity / forceHurt / tryModifyHealth / killPlayer
│   ├── FloatObfuscator      // 血量字符串的 XOR/rotate/multiply + Base64
│   ├── EntityInstance       // server/client 双实例
│   └── PlayerInstance       // ServerPlayer/LocalPlayer 双实例
├── entity/
│   ├── SuperSteveEntityBase // PathfinderMob；自定义 iInvulnerableTime、DATA_SS_HEALTH、SSMode
│   ├── SuperSteveEntity     // 主 boss：hurt/doHurtTarget/tick/remove/die 全部覆盖
│   ├── SafeServerPlayer / SafeLocalPlayer     // 换类后的"安全玩家"
│   └── DeathServerPlayer / DeathLocalPlayer   // 换类后的"死亡玩家"
├── item/EndOfPLZLite        // 一击必杀武器
└── SuperSteveMod            // 裸线程 SS-Thread
```

### 1.2 自定义血量：`setHealth` 直接空实现

`SuperSteveEntityBase` 里：

```java
public static final EntityDataAccessor<String> DATA_SS_HEALTH_ID =
    SynchedEntityData.defineId(SuperSteveEntityBase.class, EntityDataSerializers.STRING);
public int iInvulnerableTime = 0;
```

`SuperSteveEntity` 里：

```java
@Override public void ssSetHealth(float health) {
    this.entityData.set(DATA_SS_HEALTH_ID, FloatObfuscator.encryptToString(health));
}
@Override public float ssGetHealth() {
    return FloatObfuscator.decryptFromString(this.entityData.get(DATA_SS_HEALTH_ID));
}
@Override public void m_21153_(float health) { }          // setHealth：空实现
@Override public float m_21223_() { return this.ssGetHealth(); }  // getHealth
@Override public float m_21233_() { return 20.0F; }       // getMaxHealth
@Override public double m_21133_(Attribute attr) {        // getAttributeValue
    if (attr == Attributes.MAX_HEALTH) return 20.0;
    return attr == Attributes.ATTACK_DAMAGE ? Double.MAX_VALUE : super.m_21133_(attr);
}
```

**效果**：任何走 `setHealth` 的伤害/治疗/命令对 SuperSteve 都无效；它的血只存在一个 Base64 字符串里。
`FloatObfuscator` 是 XOR + rotateLeft(13) + 乘常数，再 Base64——只是防"一眼看出血量"，不是加密。

### 1.3 伤害公式：按"玩家背包最大伤害"缩放 + 全额反伤

```java
public boolean m_6469_(DamageSource source, float amount) {
    if (source.getEntity() instanceof Player player) {
        if (player.getMainHandItem().equals(new ItemStack(END_OF_PLZ_LITE.get()), false)) {
            SSUtil.killEntity(this);              // 终极武器：直接硬移除
            return true;
        }
        if (this.distanceTo(player) > 4.0F) return false;   // 只吃 4 格内近战
        if (this.iInvulnerableTime <= 0 && source.is(DamageTypes.f_268464_)) {   // 玩家攻击（按字段位置推断）
            float pst = amount / SSUtil.getMaxDamageInBag(player, this);
            boolean plz = this.ssGetMode() == SSMode.PLZLIZI;
            this.ssSetHealth(this.ssGetHealth() - pst * (plz ? rand(0.03F, 0.04F) : rand(0.1F, 0.2F)));
            super.m_6469_(source, 0.01F);          // 走一次原版 hurt（击退/音效）
            this.iInvulnerableTime = 40;           // 2 秒无敌
            SSUtil.forceHurt(player, this.damageSources().playerAttack(player), amount, true); // 全额反伤
            return true;
        }
    }
    return false;
}
```

**读法**：
- 玩家背包里最大单件伤害越高，`amount / maxDamage` 越小，SuperSteve 越肉——**它惩罚"高面板"**。
- 反伤是 `forceHurt`，不是 `player.hurt`，所以**不走 `LivingAttackEvent`/`LivingHurtEvent`**。
- `permanent=true` 会把反伤累计进 `HurtSynchedEntityData.hurtOffset`，**降低玩家的有效最大生命**。
- 只有 `END_OF_PLZ_LITE`（EOP）能直接 `killEntity` 它。

### 1.4 `forceHurt`：直接写 `SynchedEntityData` 的血

```java
public static void forceHurt(LivingEntity target, DamageSource source, float damage) {
    if (target.isSleeping() && !target.level().isClientSide) target.stopSleeping();
    target.noActionTime = 0;
    target.invulnerableTime = 0;                       // 清无敌帧
    target.getCombatTracker().recordDamage(source, damage);

    SynchedEntityData data = target.getEntityData();
    DataItem<Float> item = data.get(LivingEntity.DATA_HEALTH_ID);
    item.set(target.getHealth() - damage);             // 直接改 DataItem
    target.onSyncedDataUpdated(LivingEntity.DATA_HEALTH_ID);
    item.setDirty(true);
    data.isDirty = true;

    // 下面补 kill credit / stats / 音效 / lastHurt 等
}
```

**这是"写血"的教科书**：不调用 `hurt`，不触发 `LivingAttackEvent`/`LivingHurtEvent`/`LivingDamageEvent`，
只改 `SynchedEntityData` 里的 `DATA_HEALTH_ID`，再手动补 kill credit 和统计。
`HurtSynchedEntityData` 更狠：重写 `get/set`，把有效最大生命压到 `maxHealth - hurtOffset`——
**永久反伤会永久降低你的血上限**。

### 1.5 `killEntity`：硬移除，绕过死亡事件

`SSUtil.killEntity(entity)` 的做法（节选）：

```java
entity.isAddedToWorld = false;
entity.f_146795_ = RemovalReason.DISCARDED;   // removalReason
entity.f_19794_ = true;                       // 内部标志（按字段位置）
entity.f_19840_ = false;                      // 内部标志（按字段位置）
entity.canUpdate = false;
entity.setBoundingBox(new AABB(pos, pos));    // f_19828_
entity.setLevelCallback(EntityInLevelCallback.NULL); // f_146801_
// 从 ClientLevel/ServerLevel 的 EntitySection / EntityLookup / visibleEntityStorage 里删除
// 对 LivingEntity：清 effects、deathTime = 0、dead = true
```

**效果**：不发 `LivingDeathEvent`、不走 `die()`；掉落由它手动调 `m_6668_`（`dropAllDeathLoot`）处理，
**不保证和原版一致**。实体直接从世界索引里消失。
这是"杀"和"移除"的分离——`kill()`/`discard()` 都走 `remove(RemovalReason)`，而它绕过 `remove` 直接改索引。

### 1.6 `safeEntity`：把实体"重新加回世界"，并给玩家换类

`SSUtil.safeEntity(LivingEntity entity)` 做三件事：

1. **复活**：`entity.canUpdate = true`、`removalReason = null`、`isAddedToWorld = true`、`dead = false`、`deathTime = -1`……
   然后把实体重新塞回 `EntitySection` / `EntityLookup` / `visibleEntityStorage`。
2. **给 EOP 玩家换类**：
   ```java
   PLZBase.klassPtr(player, PLZBase.defineHiddenClassInPackage(
       player.getClass().getClassLoader(), SSUtil.class,
       "plz.lizi.supersteve.entity.SafeServerPlayer", null, ClassOption.STRONG));
   player.invulnerableTime = Integer.MAX_VALUE;
   player.abilities.instabuild = true;
   player.abilities.mayfly = true;
   // 给 EOP 武器、清死亡屏、换 SynchedEntityData / ItemCooldowns / EffectMap
   ```
3. **换数据容器**：
   - `SafeSynchedEntityData`：对 EOP 玩家，`get/set` 血量强制 20；
   - `SafeItemCooldowns`：所有物品无冷却；
   - `SafeEffectMap`：只允许夜视，其他效果全部无效。

`klassPtr` 的实现是 **Unsafe 写对象头**：

```java
public static void klassPtr(Object o, Class<?> clazz) {
    UNSAFE.ensureClassInitialized(clazz);
    UNSAFE.putIntVolatile(o, 8L, UNSAFE.getIntVolatile(UNSAFE.allocateInstance(clazz), 8L));
}
```

偏移 8 是压缩 oops 下的 klass 指针；把对象的"我是谁"改掉，JVM 就会按新类解释它。
`defineHiddenClass` 用 `ClassLoader.defineClass0` + `MethodHandles.Lookup.defineHiddenClass(NESTMATE|STRONG)` 定义隐藏类。

**这是 L3/L4 级别的手段**：不是"改字段"，是"改对象的类"。
它比 Mixin 更底层、更脆弱、也更像恶意软件。

### 1.7 `SS-Thread`：裸线程，1ms 一次

```java
new Thread(() -> {
    while (Minecraft.getInstance().isRunning()) {
        synchronized (this) { Thread.sleep(1L); }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        // 遍历 EOP_PLAYERS、SS_INSTANCES、所有 ServerLevel 的实体
        // 对每个 SuperSteve 调 ssTick(true)
        // 对每个 EOP 玩家调 safeEntity
    }
}, "SS-Thread").start();
```

`ssTick(true)` 里 `inOtherThread=true`，`doHurtTarget` 会把 `hurtValue` 置 0（避免从线程里直接打玩家），
但仍然会 `killEntity` 非玩家实体、`safeEntity`、改世界索引、切 `level.isClientSide` 字段。
**这是在服务器线程外改世界状态**——竞态、`ConcurrentModificationException`、区块索引损坏都是可预期的。

### 1.8 其它值得注意的细节

- `m_6034_`（`setPos`）走 `m_20343_` + `m_20011_`，并维护 `safePos`；tick 里检测 `NaN/Infinity`，异常就回 `safePos`。
- `m_146926_`/`m_146922_`/`m_5618_`/`m_5616_`（头部/身体旋转）都加 `Float.isFinite` 守卫——**NaN 防御**。
- `m_6021_`（`setDeltaMovement`）、`m_246847_`（`push`）空实现——**免击退**。
- `m_264318_`（`teleportTo`）返回 false、`m_20984_`（`moveTo`）返回 false——**免传送**。
- `m_142687_`（`remove`）只在 `ssHealth <= 0` 时放行——**免 discard**。
- `m_6667_`（`die`）空实现、`m_6153_`（`tickDeath`）20 tick 后 `killEntity`——**死亡流程自己接管**。
- `m_6593_`（`setCustomName`）：名字叫 `plzlizi` 就切 PLZLIZI 模式、回满 20 血、换 EOP 装备——**彩蛋/后门**。
- `getMaxDamageInBag` 会遍历背包/护甲/副手，算玩家最大单件伤害；这是它"惩罚高面板"的依据。

### 1.9 怎么防（对应 `THREAT-MODEL-MUGEN.md` §10）

| 它的手段 | 为什么常规防御无效 | 能做什么 |
|---|---|---|
| `setHealth` 空实现 | 所有走 `setHealth` 的伤害/治疗/命令都无效 | 不要用 `setHealth` 当输出；用 `setRemoved`/`remove(UNLOADED)` |
| `forceHurt` 直写 `SynchedEntityData` | 不发 `LivingAttack/Hurt/DamageEvent` | tick 级 delta 看门狗；`SynchedEntityData` 变更检测 |
| `killEntity` 硬移除 | 不发 `LivingDeathEvent`；掉落由它手动处理 | 检测 `isRemoved()` 但没有死亡事件；`EntityLeaveLevelEvent` |
| 换类 `Safe/DeathPlayer` | `getClass()` 变了，`instanceof` 可能失效 | 检测 `getClass() != 预期`；记录类名变化 |
| 裸线程 | 服务器线程外改世界 | `server.isSameThread()` 断言；off-thread 写入日志 |
| 反伤 `forceHurt` | 不走攻击者事件 | 攻击前 `LivingAttackEvent` 取消；或攻击者侧 `setHealth` 守卫 |

---

## 2. 鬼巫女（Onimiko）：把伤害管线改写成自己规则

### 2.1 类结构

```
oni_miko.entity.OnimikoEntity   // 1024 血、0 攻击、3 护甲、16 跟随
oni_miko.entity.T1Entity        // 箭矢/弹幕，god 模式命中 = setHealth(-50) + hurt(20) + 爆炸
oni_miko.entity.skill.ISkill    // run(t) / skillMain(t) / id()
oni_miko.misite.ForceAttack     // removeEntity：setRemoved 硬移除
oni_miko.event.TickLateEvent    // 延迟任务
```

### 2.2 自定义血量 + 每击上限

```java
private static final EntityDataAccessor<Float> health =
    SynchedEntityData.defineId(OnimikoEntity.class, EntityDataSerializers.FLOAT);

public float getTrueMaxHealth() { return this.entityData.get(falseMode) ? 1.0F : 1200.0F; }

@Override public void m_21153_(float newHealth) {           // setHealth
    if (Math.abs(newHealth - this.m_21223_()) > (this.m_21223_() > 600.0F ? 20 : 10)) return;
    if (newHealth > this.getTrueMaxHealth()) newHealth = this.getTrueMaxHealth();
    if (newHealth < 0.0F) newHealth = 0.0F;
    this.entityData.set(health, newHealth);
}

@Override public float m_21223_() { return this.entityData.get(health); }
```

**效果**：**任何单次超过 20（P1）/10（P2）的血量变化都被丢弃**。
这意味着：
- `setHealth(0)` 一发秒杀无效（1024 → 0 的 delta 太大）；
- `kill()`/`/kill` 走 `hurt(MAX_VALUE)`，会被 `hurt` 的 cap 截到 20；
- 只有"多段小伤害"或"直接 `setRemoved`"能杀它。

> 这是全包最值得学的一行防守：**用"增量门"而不是"数值上限"来挡爆发**。
> 它同时挡住了 `setHealth`、`/kill`、`/data merge`（如果走 setHealth）和一发超模武器。

### 2.3 `hurt`：反伤 + 上限 + 阶段减伤

```java
public boolean m_6469_(DamageSource source, float amount) {
    if (source.getEntity() instanceof LivingEntity ent) {
        ent.m_21153_(ent.m_21223_() - amount);        // 直接写攻击者血
        ent.m_6469_(this.damageSources().generic(), 1.0F); // 走一次 hurt（击退/效果）
    }
    if (amount > (this.m_21223_() > 600 ? 20 : 10)) amount = (this.m_21223_() > 600 ? 20 : 10);
    if (this.m_21223_() < 600.0F) {
        if (!this.level().isClientSide && random.nextInt(10) < 8) {
            for (int i = 0; i < 300; i++) { /* 随机找空气块 */ this.m_6034_(pos); }
        }
        amount *= 0.2F;                                // 阶段 2：80% 减伤
    }
    return super.m_6469_(source, amount);
}
```

**读法**：
- 反伤是 `ent.setHealth(ent.getHealth() - amount)`，不是 `ent.hurt(...)`——**不走攻击者的事件**。
- 对玩家：`setHealth` 会改客户端同步血，但不会触发 `LivingHurtEvent`；
- 对 boss：如果对方也有 `setHealth` 增量门，这一下会被对方丢弃（互相免疫）。
- 阶段 2 的 80% 减伤 + 传送，是"打不死"的主要来源。

### 2.4 免死：`remove` 门 + 重生

```java
@Override public void m_142687_(RemovalReason reason) {
    if (reason == UNLOADED_TO_CHUNK || reason == CHANGED_DIMENSION || reason == UNLOADED_WITH_PLAYER)
        super.m_142687_(reason);
    if (reason == KILLED && !this.m_6084_()) super.m_142687_(reason);
}
```

- `DISCARDED` 直接被忽略（`discard()` 无效）；
- `KILLED` 只有在 `isAlive() == false` 时才放行；
- `UNLOADED_*` / `CHANGED_DIMENSION` 放行——**这是唯一的合法"移走"方式**。

再加上构造器里的 `TickLateEvent`：

```java
new TickLateEvent(false, tick -> {
    if (this.isRemoved()) {
        if (this.getRemovalReason() == KILLED && this.getHealth() > 0.0F) {
            OnimikoEntity entity = new OnimikoEntity(type, level);
            CompoundTag tag = new CompoundTag();
            entity.load(tag);
            tag.remove("UUID");
            entity.load(tag);
            entity.entityData.set(health, entity.getHealth());
            entity.moveTo(this.position());
            level.addFreshEntity(entity);
            ModEvent.sendMessageToAll("Tell to me,Can you kill a god?");
        }
        return true;
    }
    return false;
});
```

**效果**：即使你绕过 `remove` 门把它 `KILLED`，只要血 >0，它会在下一 tick 原地重生。
要真正杀死，必须 **先让血到 0**，再 `KILLED`；但 `setHealth(0)` 又被增量门挡住——**死锁**。
唯一出口：`setRemoved(DISCARDED)`（绕过 `m_142687_`），或者 `UNLOADED_*`/`CHANGED_DIMENSION` 把它移走。
`ForceAttack.removeEntity` 就是这么干的（§2.7）。

### 2.5 免传送 / 免击退 / 免效果

```java
@Override public void m_6034_(double x, double y, double z) {   // setPos
    if (lastX == NaN || lastY == NaN || lastZ == NaN ||
        distance(lastX,lastY,lastZ, x,y,z) <= 10.0) super.m_6034_(x,y,z);
}
@Override public void m_8119_() {  // tick
    if (distance(lastX,lastY,lastZ, getX(),getY(),getZ()) > 10.0) m_6034_(lastX,lastY,lastZ);
    ...
}
@Override public void m_6021_(double x, double y, double z) { }   // setDeltaMovement
@Override public void m_246847_(double x, double y, double z) { } // push
@Override public boolean m_264318_(...) { return false; }          // teleportTo
@Override public boolean m_20984_(...) { return false; }           // moveTo
@Override public boolean m_7301_(MobEffectInstance e) { return false; } // canBeAffected
@Override protected void m_142540_(MobEffectInstance e, Entity x) { }   // onEffectAdded
```

**效果**：>10 格的传送被忽略；tick 里拉回原位；击退/推/效果全部无效。
这挡住了"传送进虚空""击退到岩浆""给虚弱/缓慢"等常规解法。

### 2.6 阶段技能 + 全服写血

- `getSkill()`：`health > 600 ? skills1 : skills2`；`skills1`/`skills2` 各 7–8 个 `ISkill`。
- `m_8119_`（tick）：skill[0] 每 tick，skill[1..] 每 3 tick。
- `skills1` 包括：T1 弹幕、40 tick 全玩家 20/ tick、30–50 个 T1 小怪、60 tick 定身、封印物品、直接写目标血、10 个 false 分身、方块牢笼。
- 阶段 2 的 `kishintai2` 计时器到 60 tick 时：
  ```java
  level.getEntities(this, new AABB(pos, pos).inflate(50))
       .forEach(e -> { if (e instanceof LivingEntity liv) {
           liv.m_21153_(liv.m_21223_() - 1000.0F);
           liv.m_6469_(this.damageSources().generic(), 1000.0F);
       }});
  ```
  **100×100×100 格内所有 LivingEntity 先 `setHealth(-1000)` 再 `hurt(1000)`**——房间级核弹。
- `m_6074_`（移除/死亡钩子）：
  ```java
  ModEvent.server.getPlayerList().getPlayers().forEach(ent -> ent.m_21153_(0.0F));
  ```
  **全服玩家血量归零**——"我死你也死"。

### 2.7 `ForceAttack.removeEntity`：绕过 `remove` 门的硬移除

```java
public static void removeEntity(Entity entity) {
    if (entity instanceof Player player) player.m_21153_(0.0F);
    entity.load(new CompoundTag());            // 清 NBT
    entity.canUpdate(false);
    entity.onRemovedFromWorld();
    entity.m_142467_(RemovalReason.DISCARDED); // setRemoved，绕过 remove()
    entity.m_141960_(EntityInLevelCallback.NULL);
    new TickLateEvent(..., tick -> { if (tick == 1) { entity.m_20234_(-1); return true; } ... });
}
```

**关键**：它调用 `m_142467_`（`setRemoved`），不是 `m_142687_`（`remove`）。
所以 Onimiko 的 `remove` 门拦不住它——**这就是"用更低一层绕过"的 MC 版**。
鬼巫女自己用这招处决目标（`targetTime > 400` 后），也可以被别人用同样方式处决。

### 2.8 怎么防

| 它的手段 | 常规防御为什么无效 | 能做什么 |
|---|---|---|
| 增量门 20/10 | `setHealth(0)`、`/kill`、一发超模都被丢 | 多段 + `bypasses_cooldown`；或直接 `setRemoved` |
| 反伤直写 | 不走攻击者事件 | 攻击前取消；或攻击者侧 `setHealth` 守卫 |
| `remove` 门 + 重生 | `discard()`/`kill()` 无效 | `setRemoved(DISCARDED)`；`UNLOADED`/`CHANGED_DIMENSION` |
| 免传送/免击退 | 传送/击退/效果无效 | 直接改字段；或 `setRemoved` |
| 全服写血 | 事件看不到 | tick 看门狗；`m_6074_` 触发前阻止 |
| 目标锁 400 tick 处决 | 冻住 + `removeEntity` | 400 tick 内杀掉它；或保持 >50 格；或 `canUpdate` 守卫 |

---

## 3. 共同模板：这些"强力生物"都在解决什么问题

| 需求 | 他们的实现 | 合法替代（推荐） | 风险 |
|---|---|---|---|
| 自定义血量 | 同步字段 + `setHealth` 覆盖 | `AttributeInstance` + `LivingDamageEvent` | 破坏其他 mod 的 `setHealth`/治疗 |
| 每击上限 | `setHealth` 增量门 + `hurt` cap | `LivingHurtEvent` 里 `Math.min` | 多段/乘区叠加会绕开 |
| 反伤 | 直接 `setHealth` 攻击者 | `LivingHurtEvent` 里 `attacker.hurt(...)` | 递归/事件风暴 |
| 免死 | `remove` 门 + 重生 | `LivingDeathEvent` 取消 + `setHealth` | 软锁/无限重生 |
| 硬移除 | `setRemoved` + 清索引 | `discard()` | 绕过死亡事件/掉落/任务 |
| 免传送 | `setPos` 距离门 | `canChangeDimensions` + `EntityTravelToDimensionEvent` | 兼容性差 |
| 多线程 | 裸 `Thread` | `server.execute(...)` | 竞态/崩服 |
| 换类 | `Unsafe` klass 指针 | **不要做** | 版本锁死/崩溃/被当恶意软件 |

---

## 4. 值得抄 / 不值得抄

### 4.1 值得抄（低风险、高收益）

1. **`setHealth` 增量门**（鬼巫女）：`abs(newHealth - oldHealth) > cap` 就丢弃。
   这是挡"一发秒杀"最便宜的办法，比 `LivingHurtEvent` 里的 `Math.min` 更底层。
   注意：它也会挡掉合法的 `setHealth(0)`，要给自己留一个 `force` 通道。
2. **阶段技能数组**（鬼巫女）：`getSkill()` 按血量阈值返回不同 `ISkill[]`，tick 里统一调度。
   比一堆 `if (phase == 2)` 干净。
3. **`Float.isFinite` 守卫**（SuperSteve）：所有旋转/位置写入前判有限，异常回 `safePos`。
4. **`safePos` 回退**（SuperSteve）：每 tick 记录最后一次合法位置，NaN/Infinity 时回退。
5. **`lastX/lastY/lastZ` 反传送**（鬼巫女）：>10 格就拉回；简单有效。
6. **`TickLateEvent`**（鬼巫女）：延迟任务/技能排程；比 `schedule` 轻，但要自己管生命周期。

### 4.2 不值得抄（高风险/反模式）

1. **`setHealth` 空实现**：会让所有 `setHealth` 路径（治疗、命令、其他 mod）失效，兼容性灾难。
2. **`killEntity` 硬移除**：绕过 `LivingDeathEvent`、掉落、任务、成就；服务器会"看起来正常但数据错"。
3. **`Unsafe` klass 指针 / `defineHiddenClass`**：版本锁死、JVM 崩溃、被反作弊/杀毒标记；**绝对不要**。
4. **裸 `Thread` 改世界**：竞态、`ConcurrentModificationException`、区块索引损坏；用 `server.execute`。
5. **`FloatObfuscator`**：只是让调试变难；对安全没有帮助。
6. **"我死全服死"**（`m_6074_`）：这是 grief 设计，不是 boss 设计。

---

## 5. 第二批：剑人 CSDY（jzyy）与凋灵斯拉（witherzilla）

> 对应截图里的三张任务页：
> - 「剑人」CSDY = `com.csdy.jzyy.entity.boss.entity.SwordManCsdy` + `CsdyWorldDiadema`
> - 「执行之龙」/「凋灵斯拉」 = `com.wzz.witherzilla.entity.ExecutionDragonEntity` / `WitherzillaEntity`
> 反编译产物在 `build/decomp/jzyy/` 与 `build/decomp/witherzilla/`。

### 5.1 剑人 CSDY：30M 血 + 对数伤害压缩

`SwordManCsdy` 的字段：

```java
private float currentHealth = 3.0E7F;   // 30,000,000
private static float maxHealth = 3.0E7F;
private boolean isDying = false;
```

`getHealth` 返回 `currentHealth`；`getMaxHealth` 返回 `maxHealth`；
`setHealth` 只在 `!isReal() && !isDying` 且 **新值更大** 时生效——**外部 `setHealth` 伤害一律被忽略**。
原生血每 10 tick 同步成 `max(0.1, currentHealth)`，所以 boss 血条能显示，但真血在字段里。

`hurt` 是核心：

```java
float healthRatio = currentHealth / getMaxHealth();
float logDamage = (float)Math.log10(Math.max(1.0F, damage));
float processedDamage;
if (logDamage <= 7.0F)       processedDamage = logDamage * 20000.0F;
else if (logDamage <= 10.0F) processedDamage = 140000.0F + (logDamage - 7.0F) * 1000.0F;
else                         processedDamage = 143000.0F + (logDamage - 10.0F) * 100.0F;
float healthMultiplier = 0.9F + (1.0F - healthRatio) * 0.2F;
float realDamage = Math.max(50000.0F, Math.min(processedDamage * healthMultiplier, 500000.0F));
currentHealth -= realDamage;
teleportToAttacker(source);   // 每被打一次就传送到攻击者身边
```

**读法**：
- 伤害从 1 到 `1e308`，`log10` 后被压到 **5 万–19 万**（上限 50 万实际到不了）。
- 30M 血 ≈ **200 刀**；单发伤害再高也没用，**hit 数才是货币**。
- `healthMultiplier = 0.9 + (1 - healthRatio) * 0.2`：血越少，每刀越疼（0.9 → 1.1），软狂暴。
- 每挨一刀就传送到攻击者身边——远程放风筝会被立刻贴脸。
- `isReal()` 为 true 时 `hurt` 直接返回 false——**完全无敌**。

**免死**：
- `setHealth` 只接受"变大"，所以 `setHealth(0)`、`/kill`、`/data merge` 都无效。
- `remove(KILLED)` 在 `!isDying` 时被忽略，还会说一句"苟延残喘又不肯认输的样子真丑陋啊"。
- `m_6074_` 说"可悲的尝试"。
- 唯一正门是 `currentHealth <= 0` → `handleDeath()`：`isDying=true`，`super.die`，`dropAllDeathLoot`，`super.setHealth(0)`。

**死亡后 10 秒**：
`tickDeath` 从 20 tick 到 200 tick，每 tick 对 8 格内所有 `LivingEntity`：
```java
int lv = target.hasEffect(DEEP_WOUND) ? target.getEffect(DEEP_WOUND).getAmplifier() : -1;
int newLv = lv + 1;
float damage = (float)(1 + newLv);
Util.forceHurt(target, ..., damage * 200000.0F);
LivingEntityUtil.reflectionSeverance(target, target.getHealth() - 100000.0F);
target.addEffect(new MobEffectInstance(DEEP_WOUND, 300, newLv));
```
200 tick 后移除，并说 `<CSDY>我死了,看来本体没有把我写的太强`。
这就是截图里的"死亡后他会以更强的姿态战斗 10 秒"。

**「沉睡的艺」后门**：
```java
public void setCustomName(Component name) {
    super.setCustomName(name);
    if (name != null && name.getString().contains("沉睡的艺") && !isReal()) {
        this.currentHealth = this.getMaxHealth();
        this.setReal(true);          // isReal = true
        this.syncHealthToNative();
    }
}
```
`isReal()` 后 `hurt` 返回 false（无敌），而领域 `CsdyWorldDiadema` 的 `isReal` 分支会把 8 格内**所有玩家**的 `AbsoluteSeveranceHealth` 设为 0——**立刻秒杀**。
所以"命名成沉睡的艺"不是"更强"，是"换成一个不可战胜的模式"。

### 5.2 领域「csdy 世界」：`CsdyWorldDiadema`

半径 8 的半球，跟随 boss，`perTick` 做这些事：

1. **删方块**：范围内所有非空气方块 → 空气（`setBlock(..., air, 11)`），包括头顶 10 格以上。
2. **攻击**：对范围内每个非 boss 的 `LivingEntity` 调 `holder.doHurtTarget(living)`。
3. **`isReal` 模式**：玩家 → `setAbsoluteSeveranceHealth(0)` + `forceSetAllCandidateHealth(0)`（秒杀）。
4. **非玩家**：`reHealth = health - maxHealth * 0.01`；`setAbsoluteSeveranceHealth` + `forceSetAllCandidateHealth`；
   如果血没掉或 `<=0` → `MsUtil.KillEntity(living)`。
5. **超过 5 个生物**：范围内所有生物 `setHealth(0)` + `forceSetAllCandidateHealth(0)`，boss 每杀一个回 750 血，
   广播"我受够繁文缛节了"。
6. **不超过 5 个**：对玩家——
   - 非 `ModifiableArmorItem` 的护甲 → `Helper.replaceClass(armorItem, FakeStack.class)` + `FakeItem.class`（**封印非匠魂护甲**）；
   - 背包每个物品 `player.getCooldowns().addCooldown(item, 60)`（**全部进入冷却**）。
7. **进入领域**：`abilities.mayfly=false`、`instabuild=false`、`flying=false`、`CuriosUtil.removeAllCurios(player)`、`onUpdateAbilities`。
8. **离开领域**：
   - 恢复 `instabuild`/`flying`；
   - 如果 boss 活着且玩家不是创造/旁观：说"想逃？"，`core.setTarget(player)`，**把玩家 `setPos` 到 boss 身边**，`core.doHurtTarget(player)`。

这几乎逐条对上了截图里的领域描述：
"剥夺建造/飞行"、"一切物品进入冷却"、"粉碎饰品"、"封印非匠魂胸甲"、"摧毁一切阻碍"、"生物数量超过 5……"、"逃离会遭到追击"。

### 5.3 `Hold`：空气当盾（csdy 护甲）

```java
public boolean isDamageBlocked(IToolStackView tool, ModifierEntry entry, EquipmentContext context,
                               EquipmentSlot slot, DamageSource source, float damage) {
    if (context.getEntity() instanceof Player player) {
        int air = player.getAirSupply();
        if (air > 5) {
            player.setAirSupply(air - 5);
            return true;          // 完全挡下这次伤害
        }
    }
    return false;
}
```

**每次挡伤害消耗 5 点空气**；空气耗尽才掉血。
这就是截图 tooltip 的"csdy 会保留你的最后一口气"——它用空气条当额外血条。

### 5.4 全局健康门：三个 mixin

- `AbsoluteSeveranceHealthMixin`（`LivingEntity.getHealth` HEAD）：
  如果 `getAbsoluteSeveranceHealth(entity)` 比真实血低，就返回那个值，并把 `DATA_HEALTH_ID` 写回去。
  这是"绝对切断"的核心：给实体设一个"血上限"，`getHealth` 永远不高于它。
- `LivingEntityMixin`（`priority = Integer.MAX_VALUE`）：
  - `setHealth` HEAD：`health > severanceCap` → cancel；玩家有 `ImagineBreakArmor` 且伤害 >7 → cancel。
  - `addEffect` HEAD：玩家有 `ClearBody` 且效果不是增益 → cancel。
- `DataItemMixin`（`priority = 1`）：
  `@Redirect` `DataItem.setValue`，**只有 `WriteFlag.isInternalWrite()` 为真才写入**；
  否则外部对 `SynchedEntityData` 的写（包括 `forceHurt` 那类直写）会被静默吞掉。

### 5.5 不是 boss，是基础设施：agent / coremod / 外挂

`jzyy` 里还有三类东西，和"boss 代码"不是一回事：

| 类 | 作用 |
|---|---|
| `com.csdy.jzyy.agent.LxAgent` | `ClassFileTransformer`：把所有类名含 `kakiku` 的类的方法体全部替换成 `return 0/false/null`，字段/接口清空——**定向废掉某个 mod** |
| `com.csdy.jzyy.coremod.CsdyTransformationService` | LaunchPluginService / coremod，启动期改类 |
| `com.csdy.jzyy.cheat.UI` | **Swing `JFrame` 外部窗口**，标题 `LXiSZH - …官方外挂`；`FunctionSet` 里是功能开关 |
| `com.csdy.jzyy.mixins.*` | 多个 `priority = Integer.MAX_VALUE` 的全局 mixin |

`LxAgent` 的目标 `kakiku` 不在这个包里（我扫了全部 219 个 jar，没有 `kakiku` 类），所以它现在是休眠的；
但"Java agent + 定向类改写 + 外部外挂窗口"这套能力本身，就是 MC 版的「論外外部」。
这比任何一个 boss 的数值都更值得警惕。

### 5.6 凋灵斯拉 / 执行之龙：DPS 上限 + 阶段门

**`WitherzillaEntity`**：

```java
public float getMaxHealth() { return 5.0E11F; }          // 5000 亿
public void setHealth(float h) { if (h > 0.0F) super.setHealth(h); }  // 不接受 <=0
public boolean hurt(DamageSource src, float amount) {
    if (amount >= 100000.0F) amount = 100000.0F;          // 每击上限 10 万
    if (getHealth() <= 100000.0F) { isDead = true; ModUtil.forceRemoveEntity(this); }
    return super.hurt(src, amount);
}
public float getHealth() {
    if (dataHealth < 0 && !isDead) return getMaxHealth();
    if (damageTooHigh && !isDead) { data.set(HEALTH, lastHealth); return lastHealth; }  // 回滚
    return dataHealth;
}
```

每 20 tick 统计"这一秒掉了多少血"：
```java
float damageThisSecond = oldHealth - currentHealth;
if (damageThisSecond < 0) damageThisSecond = 0;
damageTooHigh = damageThisSecond > 300000.0F;
lastHealth = oldHealth;
oldHealth = currentHealth;
```
**一秒内超过 30 万伤害 → 整秒回滚**。配合每击 10 万上限，实际是"每秒最多 3 击有效"的 DPS 门。

`getWitherzillaLife()` 为 true 时，`remove` / `onRemovedFromWorld` / `invalidateCaps` 都不放行（只在 life 为 false 时调 super）；
`canUpdate` 强制 true；`setPos` 单独限制单轴 ≤300 格——**免传送**。

**`ExecutionDragonEntity`**：

```java
public boolean hurt(DamageSource src, float amount) {
    return !getWitherzillaLife() ? false : super.hurt(src, amount);
}
public void setHealth(float h) { super.setHealth(getWitherzillaLife() ? h : 0.0F); }
public float getHealth() { return getWitherzillaLife() ? super.getHealth() : 0.0F; }
```

**阶段门**：`getWitherzillaLife()` 为 false 时，龙完全无敌、血显示 0；开了才可打。
这是"执行之龙"的"执行"——不是伤害，是"你有没有资格打"。

### 5.7 怎么防

| 机制 | 常规防御为什么无效 | 能做什么 |
|---|---|---|
| 对数伤害压缩（CSDY） | 提高单发伤害没用，1e308 也只算 17 万 | **多段/高频**，每段独立吃 5 万；或 `setRemoved` |
| `setHealth` only-heal（CSDY） | `setHealth(0)`、`/kill` 被忽略 | `setRemoved(DISCARDED)`；`UNLOADED`/`CHANGED_DIMENSION` |
| `remove` 门（CSDY） | `kill()`/`discard()` 无效 | `setRemoved`；同 `ForceAttack.removeEntity` |
| 领域（CSDY） | 进 8 格就被剥飞行/建造/饰品/护甲 + 全物品冷却 | 域外远程击杀；先移除 `CsdyWorldDiadema` 的 core；不要进 |
| `isReal` 模式（CSDY） | 无敌 + 领域秒杀 | 不要命名"沉睡的艺"；或 `setRemoved` |
| DPS 回滚（Witherzilla） | 爆发 >30 万/秒被回滚 | 持续小伤害；或 `setRemoved` |
| 阶段门（ExecutionDragon） | 未开 life 时无敌 | 等阶段/找开关；或 `setRemoved` |
| Java agent / 外挂 UI（jzyy） | 进程内防不住 | 服务端权威；不装这个包；沙箱 |

### 5.8 值得抄 / 不要抄（补充）

**值得抄**：
- **对数伤害压缩**：如果要做"hit-count boss"，这是最干净的实现；但要在 tooltip 里写清楚"伤害数字无效，看段数"。
- **领域分层**：先剥能力（飞行/建造）→ 再剥装备（饰品/护甲）→ 再压物品（冷却）→ 最后才伤害。比"一进来就秒"更有交互。
- **空气当盾**（`Hold`）：比"无敌"更有交互感，也更容易做数值。
- **`setHealth` only-heal + `remove` 门**：如果要做"不可被 `setHealth` 秒"的 boss，这是最直接的写法；但要给自己留 `force` 通道，否则你也治不了自己。

**不要抄**：
- **Java agent（`LxAgent`）、coremod、Swing 外挂 UI**：这是外挂/恶意软件级，不是 boss 设计。
- **`priority = Integer.MAX_VALUE` 的全局 `setHealth`/`addEffect` mixin**：会和所有 mod 打架。
- **`DataItemMixin` 全局吞 `SynchedEntityData` 写入**：会让别的 mod 的同步失效。
- **`FakeStack`/`FakeItem` 替换玩家装备**：这是 grief，不是机制。
- **8 格球体全部删方块**：卡服/毁地形。
- **`forceSetAllCandidateHealth` / `MsUtil.KillEntity`**：绕过所有防御，没有兼容性可言。

---

## 6. 如果还要继续

这次只反编译了两个 jar。包里还有一批"强力生物"可以按同样方法读：

- `craziness_awakened`（疯狂觉醒，12.4MB）
- `jzyy`（221MB，KubeJS 里的终局词条都指向它）
- `eeeabsmobs`（27.6MB，`ignore_armor_attack`/`immortal_magic`）
- `the_cosmic_spokespersons`（27.8MB，`out_universe`）
- `noixmodapi`（6.4MB，Illager-Wither）
- `prinegorerouse`（41.8MB）
- `block_factorys_bosses`（20.6MB）
- `kaiju_calamity`（26.6MB）
- `ultris`（14MB）、`trek`（31.5MB）

反编译命令（本机已装 Vineflower 到 `build/vineflower.jar`）：

```
java -jar build/vineflower.jar --folder <mod.jar> build/decomp/<name>
```

如果要看 KJS 里的"终局词条"是怎么落到代码的，可以先把 `kubejs/data/**` 的 trait 名和对应 mod 的
`Trait`/`Modifier` 类对上，再反编译那个类。

---

## 7. 限制

- 反编译源码不是原始源码：变量名、注释、泛型会丢失；`m_xxxxx_` 是 SRG 名，语义是按上下文推断的。
- 没有运行验证；伤害公式/触发条件以代码为准，但实际表现受其他 mod 的 mixin/事件影响。
- 只读了两个 jar；`SuperSteve` 的 `PLZBase` 还有 900+ 行底层工具（模块 `implAddReads`、`ClassLoader` 反射等），
  本文只挑了和"强力生物"直接相关的部分。
- 这些代码里的 `Unsafe`/换类/裸线程部分**不建议在任何正式 mod 里复现**；本文的目的是理解和防守，不是照抄。
