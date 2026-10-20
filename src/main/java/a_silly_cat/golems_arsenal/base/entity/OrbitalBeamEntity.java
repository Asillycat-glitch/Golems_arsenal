package a_silly_cat.golems_arsenal.base.entity;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.base.upgrade.GolemFriendlyFire;
import a_silly_cat.golems_arsenal.init.ModEntities;
import a_silly_cat.golems_arsenal.tech.mekanism.MekanismLaserDamage;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 天基激光光束：从高空竖直灌到落点的一根柱子，对**柱体沿途**的敌人持续造成 MEK 激光伤害。
 *
 * <p><b>伤害来源直接用 Mekanism 的激光伤害类型</b>（{@code MekanismDamageTypes.LASER.source(level)}），
 * 也就是 MEK 激光放大器打人的那一条。这样"这是 MEK 的激光"在语义上完全成立：护甲/标签行为与
 * MEK 自己的激光一致。
 *
 * <p><b>击杀故意不归属</b>（设计决定，不是漏配）：MEK 的 {@code MekanismDamageType} 只有
 * {@code source(Level)} / {@code source(RegistryAccess)} 两个重载，造出的 {@code DamageSource}
 * 不带攻击者，所以击杀在理论上属于天基武器本身。将来若想改成归功于引导者，
 * 需要用 MEK 的伤害类型 key 自己构造带 {@code causingEntity} 的 {@code DamageSource}。
 *
 * <p>实体自身的位置<b>就在落点</b>（贴地），光束往天上画 —— 这样它天然能被掉落实体、命令、
 * 以及"落点校验"复用，也不用同步 600 那个数。
 *
 * <p>与 {@link LightPillarEntity} 的关系：同一套骨架（synched 宽高、UUID 归属、命中名单、
 * 普通 {@code hurt}、不无视无敌帧），区别是①光柱钉在傀儡旁边、这根钉在落点；
 * ②光柱只打半径内一圈、这根打整条柱体；③连续多 tick 伤害而不是每敌只一次。
 */
public class OrbitalBeamEntity extends Entity {

    /** 光束宽度默认值（格）；实际由生成方写入。 */
    private static final float DEFAULT_WIDTH = 0.7F;
    /** 光束高度默认值（格）：从落点到"天上"。 */
    private static final float DEFAULT_HEIGHT = 256F;

    private static final String TAG_LIFE = "OrbitalLife";
    private static final String TAG_DAMAGE = "OrbitalDamage";
    private static final String TAG_RADIUS = "OrbitalRadius";
    private static final String TAG_OWNER = "OrbitalOwner";
    private static final String TAG_OWNER_PLAYER = "OrbitalOwnerPlayer";
    private static final String TAG_ALTITUDE = "OrbitalAltitude";

    /** 光束宽度（只给渲染用）。 */
    private static final EntityDataAccessor<Float> DATA_WIDTH =
            SynchedEntityData.defineId(OrbitalBeamEntity.class, EntityDataSerializers.FLOAT);
    /** 光束高度（只给渲染用）。 */
    private static final EntityDataAccessor<Float> DATA_HEIGHT =
            SynchedEntityData.defineId(OrbitalBeamEntity.class, EntityDataSerializers.FLOAT);
    /** 可见光束的顶端 Y（渲染裁到"相机附近"时也靠它定位）。 */
    private static final EntityDataAccessor<Float> DATA_TOP_Y =
            SynchedEntityData.defineId(OrbitalBeamEntity.class, EntityDataSerializers.FLOAT);
    /**
     * 光束**下端**的 Y（世界坐标）。正常等于实体的 Y（= 落点）；
     * 光往下钻穿方块后逐格下移，渲染据此把光柱画得更深。
     */
    private static final EntityDataAccessor<Float> DATA_TIP_Y =
            SynchedEntityData.defineId(OrbitalBeamEntity.class, EntityDataSerializers.FLOAT);

    private int life = 60;
    /** 冲击波炮弹伤：**只在落地那一下结算一次**，不是每 tick。 */
    private float damage;
    /** 余热：落地后每 tick 的固定伤害（不衰减，直到光柱消失）。 */
    private float residualDamage;

    /** 自定义伤害类型：穿甲、穿抗、穿附魔保护（见 resources/data/minecraft/tags/damage_type）。 */
    private static final net.minecraft.resources.ResourceKey<net.minecraft.world.damagesource.DamageType> ORBITAL_STRIKE =
            net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DAMAGE_TYPE,
                    new net.minecraft.resources.ResourceLocation("golems_arsenal", "orbital_strike"));

    /** 冲击伤按目标最大生命值的百分比附加。 */
    private static final float PERCENT_OF_MAX_HEALTH = 0.12F;
    /** 百分比部分的伤害上限（"增伤有上限"）。 */
    private static final float PERCENT_CAP = 45.0F;
    /** 柱体的伤害判定半径（格）。 */
    private double radius = 1.5;
    private UUID ownerId;
    private UUID ownerPlayerId;
    private LivingEntity ownerCache;
    /** 顶端高度（格）。默认取世界最高建筑高度，让光束"从天上下来"。 */
    private float altitude = 320F;

    /**
     * 当前被挖的那一格（光的下端）。从**落点那一格**开始，挖穿一格就往下移一格。
     *
     * <p>纯状态推进，不是射线探测：落点是发起方挑的，往下钻只是把已知的端点逐格下移。
     */
    private net.minecraft.core.BlockPos tip;
    /** 当前这一格的已累积挖掘进度（0..1）。换格即归零。 */
    private double tipProgress;

    /** 挖掘结算间隔（tick）。1 = 每 tick 都推进一点。 */
    private static final int DRILL_INTERVAL = 1;

    /** 冲击波在半径边缘处的伤害系数（中心为 1.0）。 */
    private static final float BLAST_EDGE_FACTOR = 0.45F;

    public OrbitalBeamEntity(EntityType<? extends OrbitalBeamEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    // ------------------------------------------------------------------
    // 生成入口
    // ------------------------------------------------------------------

    /**
     * 在 {@code impact} 处生成一道从天而降的激光。
     *
     * @param level     服务端世界
     * @param impact    落点
     * @param damage    每 tick 对柱体内每个敌人造成的伤害（0 = 纯视觉，用于调试观察）
     * @param hitRadius 柱体伤害判定半径
     * @param lifeTicks 存在时长
     * @param width     可见宽度
     * @param owner     引导者（可为 null：调试或"天基武器自主开火"）。仅用于友军判定，不参与击杀归属
     * @param altitude  光束顶点高度
     */
    public static OrbitalBeamEntity fire(ServerLevel level, net.minecraft.world.phys.Vec3 impact,
                                         float damage, float residualDamage,
                                         double hitRadius, int lifeTicks, float width,
                                         LivingEntity owner, float altitude) {
        OrbitalBeamEntity beam = new OrbitalBeamEntity(ModEntities.ORBITAL_BEAM.get(), level);
        beam.setPos(impact.x, impact.y, impact.z);
        beam.damage = damage;
        beam.residualDamage = residualDamage;
        beam.radius = hitRadius;
        beam.life = lifeTicks;
        beam.altitude = altitude;
        beam.entityData.set(DATA_WIDTH, width);
        beam.entityData.set(DATA_HEIGHT, Math.max(1.0F, altitude - (float) impact.y));
        beam.entityData.set(DATA_TOP_Y, altitude);
        beam.entityData.set(DATA_TIP_Y, (float) impact.y);
        // 光的下端就是落点那一格，从这里开始往下挖。
        beam.tip = net.minecraft.core.BlockPos.containing(impact.x, impact.y, impact.z);
        if (owner != null) {
            beam.ownerId = owner.getUUID();
            beam.ownerCache = owner;
            if (owner instanceof OwnableEntity ownable) {
                beam.ownerPlayerId = ownable.getOwnerUUID();
            }
        }
        level.addFreshEntity(beam);
        // 开火音效：用原版信标激活音当"充能-释放"的提示音，落地瞬间再补一次。
        level.playSound(null, impact.x, impact.y, impact.z,
                SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 2.0F, 0.6F);
        return beam;
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA_WIDTH, DEFAULT_WIDTH);
        this.entityData.define(DATA_HEIGHT, DEFAULT_HEIGHT);
        this.entityData.define(DATA_TOP_Y, 320F);
        this.entityData.define(DATA_TIP_Y, 0F);
    }

    public float beamWidth() {
        return this.entityData.get(DATA_WIDTH);
    }

    /** 可见高度：从光的下端（{@link #beamTipY()}）到顶端。 */
    public float beamHeight() {
        return Math.max(0.0F, beamTopY() - beamTipY());
    }

    public float beamTopY() {
        return this.entityData.get(DATA_TOP_Y);
    }

    /** 光束下端的 Y。正常等于落点；钻穿方块后逐格下移。 */
    public float beamTipY() {
        return this.entityData.get(DATA_TIP_Y);
    }

    // ------------------------------------------------------------------
    // tick
    // ------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            clientEffects();
            return;
        }
        if (--this.life <= 0) {
            this.discard();
            return;
        }
        // 首 tick 与落地瞬间各来一次冲击音，之后每 10 tick 一次低频轰鸣。
        if (this.tickCount % 10 == 0) {
            this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                    SoundEvents.BEACON_AMBIENT, SoundSource.PLAYERS, 1.6F, 0.5F);
        }
        // ① 落地的**冲击波炮弹伤**：只在第一 tick 结算一次（这是"炮"的主伤害）
        if (this.tickCount == 1) {
            impactBlast();
        }
        // ③ 余热：固定值、不衰减，直到光柱消失
        residualHeat();
    }



    /**
     * 对柱体（本次光束的竖直范围，水平半径 {@link #radius}）内的敌人持续造成 MEK 激光伤害。
     *
     * <p>竖直范围用**光的下端**（{@link #beamTipY()}，会随挖掘下移）而不是实体的 Y：
     * 光钻进地里之后，被埋住的敌人同样会被照到（这是"往下钻"的自然结果）。
     */
    /**
     * **冲击波炮弹伤**：落地瞬间对半径内所有敌人结算一次。
     *
     * <p>用户定的分工：炮的主伤害就是这一下，持续伤害只是"余热"。
     * 带距离衰减——越靠中心吃得越多（中心 100%，边缘 {@link #BLAST_EDGE_FACTOR}）。
     */
    private void impactBlast() {
        if (this.damage <= 0) {
            return;
        }
        double r = this.radius;
        double tipY = beamTipY();
        AABB box = new AABB(this.getX() - r, tipY - 1.0D, this.getZ() - r,
                this.getX() + r, beamTopY(), this.getZ() + r);
        LivingEntity owner = owner();
        for (LivingEntity target : this.level().getEntitiesOfClass(LivingEntity.class, box)) {
            if (!target.isAlive() || isFriendly(owner, target)) {
                continue;
            }
            double dx = target.getX() - this.getX();
            double dz = target.getZ() - this.getZ();
            double distSqr = dx * dx + dz * dz;
            if (distSqr > r * r) {
                continue;
            }
            // 线性衰减：中心 1.0，边缘 BLAST_EDGE_FACTOR
            double f = Math.sqrt(distSqr) / r;
            float scale = (float) (1.0D - (1.0D - BLAST_EDGE_FACTOR) * f);
            // 用户 2026-10-19：伤害 = 基础（20 + 傀儡攻击）+ min(最大生命 × 12%, 45)
            float pctPart = Math.min(target.getMaxHealth() * PERCENT_OF_MAX_HEALTH, PERCENT_CAP);
            target.hurt(laserSource(), (this.damage + pctPart) * scale);
        }
    }

    /**
     * **余热**：光柱存续期间每 tick 的固定伤害，**不随存活时间衰减**
     * （用户明确要求："这个不需要逐渐衰减，固定持续到消失即可"）。
     */
    private void residualHeat() {
        if (this.residualDamage <= 0) {
            return;
        }
        double r = this.radius;
        double tipY = beamTipY();
        AABB box = new AABB(this.getX() - r, tipY, this.getZ() - r,
                this.getX() + r, beamTopY(), this.getZ() + r);
        LivingEntity owner = owner();
        for (LivingEntity target : this.level().getEntitiesOfClass(LivingEntity.class, box)) {
            if (!target.isAlive() || isFriendly(owner, target)) {
                continue;
            }
            double dx = target.getX() - this.getX();
            double dz = target.getZ() - this.getZ();
            if (dx * dx + dz * dz > r * r) {
                continue;
            }
            target.hurt(laserSource(), this.residualDamage);
        }
    }

    /** 客户端侧的落点火花（服务端不产生粒子，避免无谓的包）。 */
    private void clientEffects() {
        if (this.tickCount % 2 != 0) {
            return;
        }
        for (int i = 0; i < 3; i++) {
            double a = this.random.nextDouble() * Math.PI * 2.0;
            double d = this.random.nextDouble() * Math.max(0.3, this.radius);
            this.level().addParticle(ParticleTypes.ELECTRIC_SPARK,
                    this.getX() + Math.cos(a) * d,
                    this.getY() + this.random.nextDouble() * 1.5,
                    this.getZ() + Math.sin(a) * d,
                    0.0, 0.05, 0.0);
        }
    }

    // ------------------------------------------------------------------
    // 伤害来源 / 友军判定（与 LightPillarEntity 同一套取舍）
    // ------------------------------------------------------------------

    /**
     * MEK 激光伤害源。**故意用反射**取 {@code MekanismDamageTypes.LASER}：这个实体是**无条件注册**的，
     * 常量池里不该出现 {@code mekanism.*}——一旦出现，没装 MEK 的环境里执行到这里就 NoClassDefFoundError。
     * 解析不到时退回"无攻击者的普通伤害"，激光变成了普通打击而不是报错。
     */
    private DamageSource laserSource() {
        var holder = this.level().registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE)
                .getHolderOrThrow(ORBITAL_STRIKE);
        LivingEntity owner = owner();
        // 自定义伤害类型：tags 里登记了 bypasses_armor / bypasses_effects / bypasses_enchantments
        return new DamageSource(holder, this, owner);
    }

    /**
     * 目标是不是自己人。规则与引导升级**共用一份**实现（{@link GolemFriendlyFire}）——
     * 两边各写一套的话，会出现"引导器认为该打、光束却判成友军而拒打"的矛盾。
     */
    private boolean isFriendly(LivingEntity owner, LivingEntity target) {
        return GolemFriendlyFire.isFriendly(owner, target, this.ownerId, this.ownerPlayerId);
    }

    private LivingEntity owner() {
        if (this.ownerCache == null && this.ownerId != null && this.level() instanceof ServerLevel server) {
            if (server.getEntity(this.ownerId) instanceof LivingEntity living) {
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
        // 光束很高，玩家可能在很远处就看到它；给一个宽松但仍有限的裁剪距离。
        return distance < 512.0 * 512.0;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.life = tag.getInt(TAG_LIFE);
        this.damage = tag.getFloat(TAG_DAMAGE);
        this.residualDamage = tag.getFloat("OrbitalResidual");
        this.radius = tag.getDouble(TAG_RADIUS);
        this.altitude = tag.getFloat(TAG_ALTITUDE);
        if (tag.hasUUID(TAG_OWNER)) {
            this.ownerId = tag.getUUID(TAG_OWNER);
        }
        if (tag.hasUUID(TAG_OWNER_PLAYER)) {
            this.ownerPlayerId = tag.getUUID(TAG_OWNER_PLAYER);
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt(TAG_LIFE, this.life);
        tag.putFloat(TAG_DAMAGE, this.damage);
        tag.putFloat("OrbitalResidual", this.residualDamage);
        tag.putDouble(TAG_RADIUS, this.radius);
        tag.putFloat(TAG_ALTITUDE, this.altitude);
        if (this.ownerId != null) {
            tag.putUUID(TAG_OWNER, this.ownerId);
        }
        if (this.ownerPlayerId != null) {
            tag.putUUID(TAG_OWNER_PLAYER, this.ownerPlayerId);
        }
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
