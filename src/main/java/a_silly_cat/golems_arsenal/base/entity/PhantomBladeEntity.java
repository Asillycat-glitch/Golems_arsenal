package a_silly_cat.golems_arsenal.base.entity;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.init.ModEntities;
import a_silly_cat.golems_arsenal.tech.item.GolemZeroSwordItem;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 幻梦零（隐藏彩蛋）甩出的剑气：一个"贴图平面"实体，朝正前方飞、穿透，每个敌人只吃一次。
 * <p>
 * 伤害在生成时算好（攻击力 × 蓄力倍率 × 电量上限系数），结算走普通 {@code hurt()}，
 * <b>不</b>清目标无敌帧（"伤害不无视无敌"），所以不会被当成多段爆发。
 * 触发条件见 {@link #isGenmuZero(AbstractGolemEntity)}：傀儡被命名为 Zero 且手持 Z 光剑。
 */
public class PhantomBladeEntity extends Entity {
    /** 剑气贴图（模型用的那张，64×64）。 */
    public static final ResourceLocation TEXTURE =
            Golems_arsenal.id("textures/entity/genmu_zero.png");

    /** 幻梦零是分两波甩的：第一波三角阵，隔 {@code GENMU_VOLLEY_GAP} 后第二波平铺。 */
    public enum Formation {
        /** 三角阵：顶点 1 道在正上方，底边 2 道在下方左右。 */
        TRIANGLE,
        /** 平铺：一排均匀铺开。 */
        FLAT
    }

    /** 三角阵：顶点抬高 / 底边降低（格）。 */
    private static final double TRIANGLE_APEX_LIFT = 1.0;
    private static final double TRIANGLE_BASE_DROP = 0.5;
    /** 三角阵底边两道相对中线的散开半角（度）。 */
    private static final double TRIANGLE_SPREAD_DEGREES = 11.0;
    /** 平铺一波几道 / 相对中线的散开半角（度）。 */
    private static final int FLAT_BLADE_COUNT = 5;
    private static final double FLAT_SPREAD_DEGREES = 14.0;
    /** 剑气的尾迹：每 tick 撒一次，保证沿着剑的轨迹连成一条而不是断续几坨。 */
    private static final int TRAIL_PARTICLE_INTERVAL = 1;
    /**
     * 沿剑身长度采样的点数与间距（单位 = 模型缩放倍率）。
     * <p>
     * 只在根部一个点会显得像"从屁股冒烟"；沿剑身取 3 个点，出来的是**一道有宽度的残影**。
     * 模型局部 +Y 就是飞行方向（尖端在前），所以采样点沿 +Y 排布。
     */
    private static final int TRAIL_SAMPLES = 3;
    private static final double TRAIL_SAMPLE_SPACING = 0.35;
    /** 根部基准偏移：模型 y=0 落在 entity Y 上方这么多（乘缩放），尾迹从这附近起。 */
    private static final double ROOT_TRAIL_LENGTH = 0.1;
    /**
     * 交给粒子自身的下落速度（格/tick），让尾迹受重力、自然往下沉。
     * <p>
     * 这是"受重力"的实现方式：原版粒子带 vy &lt; 0 就会自行下坠，所以不需要另写 tick 逻辑。
     * {@code speed = 0} 时粒子会飘在原地不动（旧行为）。
     */
    private static final double TRAIL_GRAVITY = 0.12;
    /** 尾迹额外沿飞行反方向甩出的分量，让粒子落在剑后方而不是糊在剑身上。 */
    private static final double TRAIL_BACKWARD_DRIFT = 0.1;
    /** 模型基础缩放（模型本身很小，靠这里放大）：2 倍。 */
    private static final float BASE_MODEL_SCALE = 2.0F;
    /** 飞行速度（格/tick）。 */
    private static final double BLADE_SPEED = 1.25;
    /** 存活时长（tick），速度 × 时长 ≈ 射程。 */
    private static final int BLADE_LIFE_TICKS = 22;
    /** 剑气平面的宽 / 高（格）；想改形状就改这两个。 */
    private static final float BLADE_WIDTH = 1.7f;
    private static final float BLADE_HEIGHT = 1.5f;
    /** 命中判定在路径上的额外膨胀半径。 */
    private static final double HIT_INFLATE = 0.9;
    /** 电量上限换算伤害的单位：1,000,000 电量上限 ≈ 伤害 ×2。 */
    private static final double CAPACITY_DAMAGE_UNIT = 1_000_000.0;

    private static final String TAG_OWNER = "PhantomBladeOwner";
    private static final String TAG_DAMAGE = "PhantomBladeDamage";
    private static final String TAG_LIFE = "PhantomBladeLife";

    /**
     * 伤害类型：<b>有意与光柱（{@code LightPillarEntity}）共用 {@code golems_arsenal:genmu_zero}</b> ——
     * 同一套"傀儡秘术"伤害共用一条定义。该类型刻意不登记进任何 {@code is_magic} 标签，因此是普通伤害；
     * 完整说明与改动注意事项见 {@code LightPillarEntity} 里同名常量的注释。
     */
    private static final ResourceKey<DamageType> DAMAGE_TYPE =
            ResourceKey.create(Registries.DAMAGE_TYPE, Golems_arsenal.id("genmu_zero"));

    private static final EntityDataAccessor<Float> DATA_WIDTH =
            SynchedEntityData.defineId(PhantomBladeEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_HEIGHT =
            SynchedEntityData.defineId(PhantomBladeEntity.class, EntityDataSerializers.FLOAT);

    private UUID ownerId;
    private LivingEntity ownerCache;
    private float damage;
    private int life = BLADE_LIFE_TICKS;
    private final Set<UUID> hit = new HashSet<>();

    public PhantomBladeEntity(EntityType<? extends PhantomBladeEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    // ---------------------------------------------------------------- 触发

    /** 触发条件：傀儡被命名为 Zero（忽略颜色符号/大小写）且主手拿着 Z 光剑。 */
    public static boolean isGenmuZero(AbstractGolemEntity<?, ?> golem) {
        Component name = golem.getCustomName();
        if (name == null) {
            return false;
        }
        String plain = ChatFormatting.stripFormatting(name.getString()).trim();
        return plain.equalsIgnoreCase("zero")
                && golem.getMainHandItem().getItem() instanceof GolemZeroSwordItem;
    }

    /** 单道剑气伤害 = 攻击力 × 蓄力倍率 × (1 + 电量上限 / 单位)。 */
    public static float bladeDamage(AbstractGolemEntity<?, ?> golem, ItemStack stack) {
        double attack = golem.getAttributeValue(Attributes.ATTACK_DAMAGE);
        double capacity = stack.getItem() instanceof GolemZeroSwordItem sword
                ? sword.getEnergyCapacity(stack) : 0.0;
        double factor = 1.0 + capacity / CAPACITY_DAMAGE_UNIT;
        return (float) Math.max(0.0,
                attack * GolemZeroSwordItem.CHARGE_DAMAGE_MULTIPLIER * factor);
    }

    /** 朝正前方甩出一波剑气，阵型见 {@link Formation}。 */
    public static void fire(AbstractGolemEntity<?, ?> golem, ItemStack stack, Formation formation) {
        if (golem.level().isClientSide) {
            return;
        }
        Vec3 look = golem.getLookAngle();
        Vec3 base = new Vec3(look.x, 0, look.z);
        if (base.lengthSqr() < 1.0E-6) {
            return;
        }
        base = base.normalize();
        Vec3 from = golem.position()
                .add(0, golem.getBbHeight() * 0.55, 0)
                .add(base.scale(0.9));
        float damage = bladeDamage(golem, stack);
        int count = formation == Formation.TRIANGLE ? 3 : FLAT_BLADE_COUNT;
        for (int i = 0; i < count; i++) {
            double yOffset;
            double spread;
            if (formation == Formation.TRIANGLE) {
                // i=0 顶点（抬高、走中线），i=1/2 是底边两道
                yOffset = i == 0 ? TRIANGLE_APEX_LIFT : -TRIANGLE_BASE_DROP;
                spread = i == 0 ? 0.0 : (i == 1 ? -TRIANGLE_SPREAD_DEGREES : TRIANGLE_SPREAD_DEGREES);
            } else {
                yOffset = 0.0;
                double t = count <= 1 ? 0.0 : (i / (double) (count - 1)) * 2.0 - 1.0;
                spread = FLAT_SPREAD_DEGREES * t;
            }
            double angle = Math.toRadians(spread);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            Vec3 dir = new Vec3(base.x * cos - base.z * sin, 0, base.x * sin + base.z * cos);
            PhantomBladeEntity blade = new PhantomBladeEntity(ModEntities.PHANTOM_BLADE.get(),
                    golem.level());
            blade.ownerId = golem.getUUID();
            blade.ownerCache = golem;
            blade.damage = damage;
            blade.life = BLADE_LIFE_TICKS;
            float scale = BASE_MODEL_SCALE * sizeFactor(golem);
            blade.entityData.set(DATA_WIDTH, scale);
            blade.entityData.set(DATA_HEIGHT, scale);
            blade.setPos(from.x + dir.x * 0.3, from.y + yOffset, from.z + dir.z * 0.3);
            blade.setDeltaMovement(dir.scale(BLADE_SPEED));
            blade.setYRot((float) (Math.atan2(-dir.x, dir.z) * 180.0 / Math.PI));
            golem.level().addFreshEntity(blade);
        }
        if (golem.level() instanceof ServerLevel server) {
            server.playSound(null, from.x, from.y, from.z, SoundEvents.PLAYER_ATTACK_SWEEP,
                    SoundSource.HOSTILE, 1.2F, 0.8F);
            server.sendParticles(ParticleTypes.SWEEP_ATTACK, from.x, from.y, from.z,
                    4, 0.5, 0.3, 0.5, 0.0);
        }
    }

    // ---------------------------------------------------------------- 实体

    /** 体型加成：以 2 格高为基准，傀儡越高剑气越大（1.0 ~ 2.5 倍）。 */
    private static float sizeFactor(AbstractGolemEntity<?, ?> golem) {
        float factor = (float) (golem.getBbHeight() / 2.0);
        return Math.max(1.0F, Math.min(2.5F, factor));
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA_WIDTH, BLADE_WIDTH);
        this.entityData.define(DATA_HEIGHT, BLADE_HEIGHT);
    }

    public float bladeWidth() {
        return this.entityData.get(DATA_WIDTH);
    }

    public float bladeHeight() {
        return this.entityData.get(DATA_HEIGHT);
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide && --this.life <= 0) {
            this.discard();
            return;
        }
        Vec3 motion = this.getDeltaMovement();
        Vec3 from = this.position();
        if (motion.lengthSqr() > 1.0E-8) {
            this.setYRot((float) (Math.atan2(-motion.x, motion.z) * 180.0 / Math.PI));
            this.setPos(from.x + motion.x, from.y + motion.y, from.z + motion.z);
        }
        if (!this.level().isClientSide) {
            damageAlong(from, this.position());
            // 绿色尾迹：骨粉那种小星星（原版 FIREWORK 火星的颜色是写死的，染不了绿）
            if (this.tickCount % TRAIL_PARTICLE_INTERVAL == 0
                    && this.level() instanceof ServerLevel server) {
                // 沿剑身取几个采样点，粒子带下落速度 —— 出来是一条会往下沉的残影带。
                //
                // 模型几何（对应 GenmuZeroRenderer 的变换）：模型 +Z 对齐飞行方向，再绕 Y 转
                // -(yaw + MODEL_YAW_OFFSET)，所以模型局部 +Y 就是世界飞行方向；中央枢轴修正
                // VERTICAL_CENTER_FIX = -0.125 使模型 y=0 落在 entity Y + 0.51 × scale 处，
                // 而模型向 +Y 延伸 —— 也就是**尖端朝前、根部在后**。
                //
                // 速度写法：sendParticles 没有"按分量给速度"的重载，唯一的办法是 count = 0，
                // 此时后三个偏移量被当作速度矢量，末尾的 speed 再整体缩放（本家 SonicCannon 与
                // 秘奥义都是这个用法）。TRAIL_GRAVITY 取正、这里取负，粒子就会自己往下沉。
                Vec3 dir = motion.normalize();
                double scale = Math.max(0.01, this.bladeWidth());
                Vec3 base = this.position().add(dir.scale(ROOT_TRAIL_LENGTH * scale));
                double vx = -dir.x * TRAIL_BACKWARD_DRIFT;
                double vz = -dir.z * TRAIL_BACKWARD_DRIFT;
                for (int i = 0; i < TRAIL_SAMPLES; i++) {
                    Vec3 at = base.add(dir.scale(TRAIL_SAMPLE_SPACING * scale * i));
                    server.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                            at.x, at.y, at.z,
                            0,
                            vx, -TRAIL_GRAVITY, vz,
                            1.0);
                }
            }
        }
    }

    /** 沿这一 tick 走过的线段找敌人，每个敌人只吃一次。 */
    private void damageAlong(Vec3 from, Vec3 to) {
        if (this.damage <= 0) {
            return;
        }
        LivingEntity owner = owner();
        AABB box = new AABB(from, to).inflate(HIT_INFLATE);
        for (LivingEntity target : this.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive() && !this.hit.contains(e.getUUID()))) {
            if (target == owner || (owner != null && target.isAlliedTo(owner))) {
                continue;
            }
            this.hit.add(target.getUUID());
            target.hurt(bladeSource(owner), this.damage);
        }
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
        this.damage = tag.getFloat(TAG_DAMAGE);
        this.life = tag.getInt(TAG_LIFE);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (this.ownerId != null) {
            tag.putUUID(TAG_OWNER, this.ownerId);
        }
        tag.putFloat(TAG_DAMAGE, this.damage);
        tag.putInt(TAG_LIFE, this.life);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
