package a_silly_cat.golems_arsenal.base.entity;

import a_silly_cat.golems_arsenal.Golems_arsenal;
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
    private static final String TAG_DAMAGE = "LightPillarDamage";
    private static final String TAG_LIFE = "LightPillarLife";
    private static final String TAG_RADIUS = "LightPillarRadius";

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
    private LivingEntity ownerCache;
    private float damage;
    private double radius = 1.6;
    private int life = 40;
    private final Set<UUID> hit = new HashSet<>();

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

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            return; // 公转/自转都由渲染器按 tickCount 插值算，这里不动位置也不动角度
        }
        if (--this.life <= 0) {
            this.discard();
            return;
        }
        // 跟着傀儡走：本体固定在"出生角度那一圈"上（伤害判定就挂在这个位置上），
        // 渲染层面再叠公转；不跟的话傀儡走开就只剩原地四根光柱。
        LivingEntity owner = owner();
        if (owner != null && !owner.isRemoved()) {
            double a = Math.toRadians(this.getYRot());
            double r = this.orbitRadius();
            this.setPos(owner.getX() + Math.cos(a) * r, owner.getY(),
                    owner.getZ() + Math.sin(a) * r);
        }
        damageNearby();
    }

    private void damageNearby() {
        if (this.damage <= 0) {
            return;
        }
        LivingEntity owner = owner();
        AABB box = this.getBoundingBox().inflate(this.radius);
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
        this.radius = tag.getDouble(TAG_RADIUS);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (this.ownerId != null) {
            tag.putUUID(TAG_OWNER, this.ownerId);
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
