package a_silly_cat.golems_arsenal.base.entity;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import dev.xkmc.l2library.init.events.GeneralEventHandler;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.network.NetworkHooks;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 光柱（隐藏彩蛋式升级的视觉/伤害载体）：对半径内的敌人各造成一次伤害。
 * <p>
 * 位置本身钉在出生点不动（伤害判定就固定在那一圈上），**公转和自转都在渲染器里按
 * {@code tickCount} 算**（{@code BeaconPillarRenderer}）——普通实体每次位置同步都是硬跳，
 * 服务端挪它会变成 20Hz 的顿挫；渲染时算则是完全平滑的。
 * <p>
 * 实体的 {@code yRot} 在这里只当"在环上的出生角度"用，服务端设定后不再改动。
 * <p>
 * 参考本家信标炮（{@code BeaconLaserEntity}）的做法：**归属者实体 + 自己的命中名单**，
 * 每个敌人只吃一次；伤害走普通 {@code hurt()}（不无视无敌帧）。
 */
public class LightPillarEntity extends Entity {
    /** 柱子的宽 / 高（格）。 */
    private static final float PILLAR_WIDTH = 0.9F;
    private static final float PILLAR_HEIGHT = 18F;
    /** 公转半径的默认值（老存档/没设过时的兜底，正常由 {@code spawnRing} 写入）。 */
    private static final float DEFAULT_ORBIT_RADIUS = 1.3F;

    private static final String TAG_OWNER = "LightPillarOwner";
    /**
     * 施放者傀儡<b>的主人</b>的 UUID。存下来是因为 {@code isAlliedTo} 判不出"主人不在线的同主傀儡"，
     * 见 {@link #isFriendly} 的注释。
     */
    private static final String TAG_OWNER_PLAYER = "LightPillarOwnerPlayer";
    private static final String TAG_DAMAGE = "LightPillarDamage";
    private static final String TAG_LIFE = "LightPillarLife";
    private static final String TAG_RADIUS = "LightPillarRadius";

    /**
     * 伤害类型：<b>有意与幻梦零（{@code PhantomBladeEntity}）共用 {@code golems_arsenal:genmu_zero}</b>，
     * 两者是同一套"傀儡秘术"伤害，共用一条定义（见 {@code data/golems_arsenal/damage_type/genmu_zero.json}）。
     * <p>
     * ⚠️ 这个类型<b>刻意不登记</b>在任何 {@code is_magic} 伤害类型标签里
     * （既不进 {@code forge:tags/damage_type/is_magic.json}，也不进 {@code minecraft} 的同名标签），
     * 所以光柱与幻梦零的伤害是<b>普通伤害</b>：吃护甲、吃附魔、吃抗性，也不吃魔法增伤。
     * 这是当前平衡下的取舍，不是漏配 —— 改之前请先想清楚它会让这两者同时被魔法抗性减免。
     * <p>
     * 另注：{@code genmu_zero} 没有 {@code death.attack.*} 文案（全 mod 的伤害类型都还没有），
     * 所以傀儡用它击杀玩家时，死亡消息会退回显示原始 id。
     */
    private static final ResourceKey<DamageType> DAMAGE_TYPE =
            ResourceKey.create(Registries.DAMAGE_TYPE, Golems_arsenal.id("genmu_zero"));

    private static final EntityDataAccessor<Float> DATA_WIDTH =
            SynchedEntityData.defineId(LightPillarEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_HEIGHT =
            SynchedEntityData.defineId(LightPillarEntity.class, EntityDataSerializers.FLOAT);
    /** 绕傀儡公转的半径（格）；0 = 不公转。 */
    private static final EntityDataAccessor<Float> DATA_ORBIT_RADIUS =
            SynchedEntityData.defineId(LightPillarEntity.class, EntityDataSerializers.FLOAT);
    /** 归属傀儡的实体 id（客户端渲染时用它查傀儡的插值位置，做到平滑跟随）。 */
    private static final EntityDataAccessor<Integer> DATA_OWNER_ENTITY_ID =
            SynchedEntityData.defineId(LightPillarEntity.class, EntityDataSerializers.INT);

    private UUID ownerId;
    /** 施放者傀儡的主人；主人不在线时 {@code isAlliedTo} 判不出友军，靠它兜底。 */
    private UUID ownerPlayerId;
    private LivingEntity ownerCache;
    private float damage;
    private double radius = 1.6;
    private int life = 40;
    private final Set<UUID> hit = new HashSet<>();
    /**
     * 本轮共享的命中名单。三轮铺开时由生成方传入，<b>同一轮的所有光柱共用一份</b>，
     * 于是"每名敌人每轮只吃一次"是跨光柱成立的（否则 N 根各打一次，一轮就 N 次）。
     */
    private Set<UUID> sharedHit;
    /** 当前角度（弧度）与角速度（弧度/tick）；外扩速度（格/tick）与外扩上限（格）。 */
    private double angle;
    private double angularSpeed;
    private double radialSpeed;
    private double radialAcceleration;
    private double maxRadius = 4.5;
    /** 当前绕傀儡中心的半径（格）；到 {@link #maxRadius} 后停在外圈继续转。 */
    private double currentRadius = DEFAULT_ORBIT_RADIUS;

    public LightPillarEntity(EntityType<? extends LightPillarEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    /** 在傀儡周围生成一圈光柱。 */
    public static void spawnRing(AbstractGolemEntity<?, ?> golem, int count, double ringRadius,
                                 float damage, double hitRadius, int lifeTicks) {
        if (golem.level().isClientSide) {
            return;
        }
        for (int i = 0; i < count; i++) {
            double angle = Math.PI * 2.0 * i / count;
            LightPillarEntity pillar = new LightPillarEntity(
                    a_silly_cat.golems_arsenal.init.ModEntities.LIGHT_PILLAR.get(), golem.level());
            pillar.ownerId = golem.getUUID();
            pillar.ownerPlayerId = golem.getOwnerUUID();
            pillar.ownerCache = golem;
            pillar.entityData.set(DATA_OWNER_ENTITY_ID, golem.getId());
            pillar.damage = damage;
            pillar.radius = hitRadius;
            pillar.life = lifeTicks;
            pillar.entityData.set(DATA_WIDTH, PILLAR_WIDTH);
            pillar.entityData.set(DATA_HEIGHT, PILLAR_HEIGHT);
            pillar.entityData.set(DATA_ORBIT_RADIUS, (float) ringRadius);
            pillar.setPos(golem.getX() + Math.cos(angle) * ringRadius,
                    golem.getY(),
                    golem.getZ() + Math.sin(angle) * ringRadius);
            // 这里只是"出生角度"：渲染器靠它反推傀儡中心，再让光柱绕着公转。
            pillar.setYRot((float) (angle * 180.0 / Math.PI));
            golem.level().addFreshEntity(pillar);
        }
    }

    // ---------------------------------------------------------------- 三轮螺旋铺开（Omega 联动）

    /** 轮数：顺 → 逆 → 顺。 */
    private static final int BLOOM_ROUNDS = 3;
    /**
     * 轮间隔（tick）：<b>允许与上一轮重叠</b> —— 三轮各自处在不同半径上，靠半径与角度自然区分，
     * 不需要等上一轮消失。单根寿命（20+6）比它长，所以同一时刻可能有两轮并存，这是有意的。
     */
    private static final int BLOOM_ROUND_INTERVAL = 12;
    /** 每轮根数（偶数：三轮交替旋向才会形成对称花冠，奇数会有一根走错位）。 */
    private static final int BLOOM_PILLARS = 8;
    /** 起始半径 / 外扩上限（格）。 */
    private static final double BLOOM_START_RADIUS = 1.2D;
    /**
     * 外扩上限：到这一圈就**消失**。速度整体 ×1.5 而行程时间仍是 20 tick，所以外圈从 6.5 推到 9.15
     * （1.2 + 7.95）。
     */
    private static final double BLOOM_MAX_RADIUS = 9.15D;
    /**
     * 外扩初速与加速度：要求"**正好 20 tick（1 秒）从起始圈推到外圈**"且保留加速感 ——
     * 解 {@code 20·v₀ + ½·a·20² = 9.15 − 1.2 = 7.95} 得 v₀=0.2475、a=0.015，
     * 末速 0.5475（约初速的 2.2 倍）。相对上一版整体 ×1.5。
     */
    private static final double BLOOM_RADIAL_SPEED = 0.2475D;
    private static final double BLOOM_RADIAL_ACCEL = 0.015D;
    /**
     * 角速度：设计口径是"**在推到外圈的这 20 tick 里，把相邻光柱的角度差填满**"。
     * 每根光柱的总转角取 {@link #BLOOM_CLOSE_ANGLE_DEG}（90°）⇒ 角速度 = 90° / 20 tick = 4.5°/tick。
     * （8 根光柱的自然相邻间隔是 45°，这里刻意用 90°，让弧线拉开得更明显。）
     */
    private static final double BLOOM_CLOSE_ANGLE_DEG = 90.0D;
    /** 单根寿命 = 外扩耗时 + 余量（正常在到达外圈那一刻提前消失，这个值只是保险）。 */
    private static final int BLOOM_TRAVEL_TICKS = 20;
    private static final int BLOOM_PILLAR_LIFE = BLOOM_TRAVEL_TICKS + 6;
    private static final double BLOOM_HIT_RADIUS = 1.2D;

    /**
     * 受击触发：<b>三轮光柱（顺 → 逆 → 顺）螺旋外扩</b>。
     * <p>
     * 每轮生成 {@link #BLOOM_PILLARS} 根、共用<b>同一份命中名单</b>，所以"每名敌人每轮只吃一次"
     * 是跨光柱成立的（全程最多 3 次/敌人），而不是 N 根各打一次。
     * <p>
     * 运动是真运动（服务端推进位置/角度），因此会把 {@code DATA_ORBIT_RADIUS} 设为 0，
     * 让渲染器不再自行公转、直接画实体真实位置 —— 视觉与判定一致。
     */
    public static void spawnBloom(AbstractGolemEntity<?, ?> golem, float damagePerHit) {
        if (!(golem.level() instanceof ServerLevel server)) {
            return;
        }
        long start = server.getGameTime();
        for (int round = 0; round < BLOOM_ROUNDS; round++) {
            final int index = round;
            final Set<UUID> roundHits = new HashSet<>();
            final long due = start + 1L + (long) index * BLOOM_ROUND_INTERVAL;
            GeneralEventHandler.schedulePersistent(() -> {
                if (server.getGameTime() < due) {
                    return false;
                }
                if (!golem.isAlive() || golem.isRemoved()) {
                    return true; // 傀儡中途没了：本轮放弃
                }
                spawnBloomRound(golem, index, damagePerHit, roundHits);
                return true;
            });
        }
    }

    private static void spawnBloomRound(AbstractGolemEntity<?, ?> golem, int round,
                                        float damage, Set<UUID> roundHits) {
        double direction = round % 2 == 0 ? 1.0D : -1.0D; // 顺 → 逆 → 顺
        double offset = round * Math.PI / BLOOM_PILLARS;  // 每轮错开半格，三轮轨迹不重叠
        for (int i = 0; i < BLOOM_PILLARS; i++) {
            double angle = offset + Math.PI * 2.0 * i / BLOOM_PILLARS;
            LightPillarEntity pillar = new LightPillarEntity(
                    a_silly_cat.golems_arsenal.init.ModEntities.LIGHT_PILLAR.get(), golem.level());
            pillar.ownerId = golem.getUUID();
            pillar.ownerPlayerId = golem.getOwnerUUID();
            pillar.ownerCache = golem;
            pillar.entityData.set(DATA_OWNER_ENTITY_ID, golem.getId());
            pillar.damage = damage;
            pillar.radius = BLOOM_HIT_RADIUS;
            pillar.life = BLOOM_PILLAR_LIFE;
            pillar.angle = angle;
            pillar.currentRadius = BLOOM_START_RADIUS;
            pillar.angularSpeed = Math.toRadians(BLOOM_CLOSE_ANGLE_DEG / BLOOM_TRAVEL_TICKS) * direction;
            pillar.radialSpeed = BLOOM_RADIAL_SPEED;
            pillar.radialAcceleration = BLOOM_RADIAL_ACCEL;
            pillar.maxRadius = BLOOM_MAX_RADIUS;
            pillar.sharedHit = roundHits;
            pillar.entityData.set(DATA_WIDTH, PILLAR_WIDTH);
            pillar.entityData.set(DATA_HEIGHT, PILLAR_HEIGHT);
            pillar.entityData.set(DATA_ORBIT_RADIUS, 0.0F);
            pillar.setPos(golem.getX() + Math.cos(angle) * BLOOM_START_RADIUS,
                    golem.getY(),
                    golem.getZ() + Math.sin(angle) * BLOOM_START_RADIUS);
            pillar.setYRot((float) Math.toDegrees(angle));
            golem.level().addFreshEntity(pillar);
        }
    }

    /** 一轮铺开需要的时间（tick）：最后一轮生成 + 单根寿命。给触发方算无敌窗口用。 */
    public static int bloomDurationTicks() {
        return (BLOOM_ROUNDS - 1) * BLOOM_ROUND_INTERVAL + BLOOM_PILLAR_LIFE + 1;
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA_WIDTH, PILLAR_WIDTH);
        this.entityData.define(DATA_HEIGHT, PILLAR_HEIGHT);
        this.entityData.define(DATA_ORBIT_RADIUS, DEFAULT_ORBIT_RADIUS);
        this.entityData.define(DATA_OWNER_ENTITY_ID, -1);
    }

    public float pillarWidth() {
        return this.entityData.get(DATA_WIDTH);
    }

    public float pillarHeight() {
        return this.entityData.get(DATA_HEIGHT);
    }

    /** 绕傀儡公转的半径（格）；0 表示不公转。 */
    public float orbitRadius() {
        return this.entityData.get(DATA_ORBIT_RADIUS);
    }

    /** 归属傀儡的实体 id；-1 = 没有。 */
    public int ownerEntityId() {
        return this.entityData.get(DATA_OWNER_ENTITY_ID);
    }

    /**
     * 光柱的推进：<b>真运动（服务端）</b>——每 tick 同时叠加"绕傀儡中心的角度"与"远离中心的半径"，
     * 于是轨迹是螺旋；半径到 {@link #maxRadius} 后不再外扩，只在外圈继续转（扫掠不飞出战场）。
     * <p>
     * 运动放在服务端之后，生成方会把 {@code DATA_ORBIT_RADIUS} 设为 0，渲染器按约定不再自行公转，
     * 直接画实体真实位置（配合原版的插值），视觉与判定一致。
     */
    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            return;
        }
        if (--this.life <= 0) {
            this.discard();
            return;
        }
        LivingEntity owner = owner();
        if (owner != null && !owner.isRemoved()) {
            this.angle += this.angularSpeed;
            if (this.currentRadius < this.maxRadius) {
                this.radialSpeed += this.radialAcceleration;   // 逐渐加速
                this.currentRadius = Math.min(this.maxRadius, this.currentRadius + this.radialSpeed);
            }
            this.setPos(owner.getX() + Math.cos(this.angle) * this.currentRadius,
                    owner.getY(),
                    owner.getZ() + Math.sin(this.angle) * this.currentRadius);
        }
        // 到达外圈：最后扫一次判定的位置，然后**直接消失**（不再留在外圈空转）。
        if (this.currentRadius >= this.maxRadius) {
            damageNearby();
            this.discard();
            return;
        }
        // 判定节流：每 2 tick 扫一次，20~24 根光柱也不会把实体扫描开销翻倍。
        if (this.tickCount % 2 == 0) {
            damageNearby();
        }
    }

    /** 命中名单：优先用本轮共享的那一份。 */
    private Set<UUID> hits() {
        return this.sharedHit != null ? this.sharedHit : this.hit;
    }

    private void damageNearby() {
        if (this.damage <= 0) {
            return;
        }
        Set<UUID> hits = this.hits();
        LivingEntity owner = owner();
        AABB box = this.getBoundingBox().inflate(this.radius);
        for (LivingEntity target : this.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive() && !hits.contains(e.getUUID()))) {
            if (isFriendly(owner, target)) {
                continue;
            }
            hits.add(target.getUUID());
            target.hurt(bladeSource(owner), this.damage);
        }
    }

    /**
     * 目标是不是"自己人"。
     * <p>
     * ⚠️ <b>不能只靠 {@code isAlliedTo}</b>：本家的 {@code AbstractGolemEntity#isAlliedTo} 走的是
     * {@code getOwner()}，而 {@code getOwner()} = {@code level().getPlayerByUUID(uuid)} ——
     * <b>主人不在线就返回 null</b>（FakePlayer 同理），此时 {@code isAlliedTo} 会退化成"看队伍"，
     * 于是<b>同一个主人的两只傀儡会被判成敌人</b>，光柱就会打自家傀儡。
     * 那种情况下之所以没看到掉血，只是因为对方正好在无敌帧里被吞掉了 —— 是巧合，不是机制：
     * 一旦无敌窗口错开，友军伤害就会真的落下去。
     * <p>
     * 所以这里在 {@code isAlliedTo} 之外<b>再加一道不依赖主人在线与否的 {@code getOwnerUUID()} 比对</b>
     * （与本仓库 {@code GolemTrainModifier} 判"同主"的写法一致），并把施放者本人也按 UUID 排除，
     * 这样即使傀儡实体已经不在场，也认得出自己人。
     */
    private boolean isFriendly(LivingEntity owner, LivingEntity target) {
        if (target == owner || target.getUUID().equals(this.ownerId)) {
            return true;
        }
        if (owner != null && (target.isAlliedTo(owner) || owner.isAlliedTo(target))) {
            return true;
        }
        UUID master = this.ownerPlayerId;
        if (master == null && owner instanceof OwnableEntity ownable) {
            master = ownable.getOwnerUUID();
        }
        if (master == null) {
            return false;
        }
        if (master.equals(target.getUUID())) {
            return true;
        }
        return target instanceof OwnableEntity other && master.equals(other.getOwnerUUID());
    }

    private DamageSource bladeSource(LivingEntity owner) {
        var registry = this.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        return new DamageSource(registry.getHolderOrThrow(DAMAGE_TYPE), owner);
    }

    private LivingEntity owner() {
        if (this.ownerCache == null && this.ownerId != null
                && this.level() instanceof ServerLevel server) {
            var entity = server.getEntity(this.ownerId);
            if (entity instanceof LivingEntity living) {
                this.ownerCache = living;
            }
        }
        return this.ownerCache;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 128.0 * 128.0;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.hasUUID(TAG_OWNER)) {
            this.ownerId = tag.getUUID(TAG_OWNER);
        }
        if (tag.hasUUID(TAG_OWNER_PLAYER)) {
            this.ownerPlayerId = tag.getUUID(TAG_OWNER_PLAYER);
        }
        this.damage = tag.getFloat(TAG_DAMAGE);
        this.life = tag.getInt(TAG_LIFE);
        this.radius = tag.getDouble(TAG_RADIUS);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (this.ownerId != null) {
            tag.putUUID(TAG_OWNER, this.ownerId);
        }
        if (this.ownerPlayerId != null) {
            tag.putUUID(TAG_OWNER_PLAYER, this.ownerPlayerId);
        }
        tag.putFloat(TAG_DAMAGE, this.damage);
        tag.putInt(TAG_LIFE, this.life);
        tag.putDouble(TAG_RADIUS, this.radius);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
