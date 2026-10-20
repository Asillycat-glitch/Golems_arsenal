package a_silly_cat.golems_arsenal.base.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

/**
 * **白热射弹**：瞄准收束完成后从高空射下的那一发。
 *
 * <p>用户 2026-10-19 定的表现：不要"激光柱持续照射"，要一发**弹体**——
 * 亮白的热核 + 红热的拖尾（速度感全在拖尾上），落地再触发冲击波。
 *
 * <p>弹体**比瞄准光柱宽**，这样下落时才显眼（瞄准阶段六条细光，弹体是"实心一发"）。
 *
 * <p>行为很简单：每 tick 向下推进固定距离，抵达落点 Y 时生成
 * {@link OrbitalImpactEntity}（冲击环 + 白热球 + 碎屑）并自毁。
 * 不造成伤害——伤害由落地后的光束/冲击实体负责。
 */
public class OrbitalProjectileEntity extends Entity {

    /** 下落速度（格/tick）。12 格/tick 从 y=300 到地面约 25 tick ≈ 1.2 秒。 */
    private static final double FALL_SPEED = 12.0D;

    /** 发射高度（用于落地后画残留线的长度）。 */
    private static final EntityDataAccessor<Float> DATA_START_Y =
            SynchedEntityData.defineId(OrbitalProjectileEntity.class, EntityDataSerializers.FLOAT);

    private static final EntityDataAccessor<Float> DATA_TARGET_Y =
            SynchedEntityData.defineId(OrbitalProjectileEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_RADIUS =
            SynchedEntityData.defineId(OrbitalProjectileEntity.class, EntityDataSerializers.FLOAT);
    /** 弹体视觉宽度（格）。比瞄准光柱宽，突出"这一发很大"。 */
    private static final EntityDataAccessor<Float> DATA_WIDTH =
            SynchedEntityData.defineId(OrbitalProjectileEntity.class, EntityDataSerializers.FLOAT);

    private static final String TAG_TARGET_Y = "ProjTargetY";
    private static final String TAG_RADIUS = "ProjRadius";
    private static final String TAG_WIDTH = "ProjWidth";

    public OrbitalProjectileEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /**
     * 从 {@code startY} 向 {@code targetY} 射出一发。
     *
     * @param radius 落地冲击的半径（格）
     * @param width  弹体视觉宽度（格）
     */
    public static OrbitalProjectileEntity fire(ServerLevel level, Vec3 ground, double startY,
                                               float radius, float width) {
        OrbitalProjectileEntity proj = new OrbitalProjectileEntity(
                a_silly_cat.golems_arsenal.init.ModEntities.ORBITAL_PROJECTILE.get(), level);
        proj.setPos(ground.x, startY, ground.z);
        proj.entityData.set(DATA_START_Y, (float) startY);
        proj.entityData.set(DATA_TARGET_Y, (float) ground.y);
        proj.entityData.set(DATA_RADIUS, Math.max(1.0F, radius));
        proj.entityData.set(DATA_WIDTH, Math.max(0.2F, width));
        level.addFreshEntity(proj);
        return proj;
    }

    public float targetY() {
        return this.entityData.get(DATA_TARGET_Y);
    }

    public float impactRadius() {
        return this.entityData.get(DATA_RADIUS);
    }

    public float beamWidth() {
        return this.entityData.get(DATA_WIDTH);
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA_START_Y, 300.0F);
        this.entityData.define(DATA_TARGET_Y, 64.0F);
        this.entityData.define(DATA_RADIUS, 8.0F);
        this.entityData.define(DATA_WIDTH, 1.6F);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            return;
        }
        double nextY = this.getY() - FALL_SPEED;
        if (nextY <= this.targetY()) {
            // 落地：生成冲击（纯视觉），然后自毁
            if (this.level() instanceof ServerLevel server) {
                OrbitalImpactEntity.create(server,
                        new Vec3(this.getX(), this.targetY(), this.getZ()), this.impactRadius(),
                        this.entityData.get(DATA_START_Y));
            }
            this.discard();
            return;
        }
        this.setPos(this.getX(), nextY, this.getZ());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.entityData.set(DATA_TARGET_Y, tag.getFloat(TAG_TARGET_Y));
        if (tag.contains(TAG_RADIUS)) {
            this.entityData.set(DATA_RADIUS, tag.getFloat(TAG_RADIUS));
        }
        if (tag.contains(TAG_WIDTH)) {
            this.entityData.set(DATA_WIDTH, tag.getFloat(TAG_WIDTH));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putFloat(TAG_TARGET_Y, this.targetY());
        tag.putFloat(TAG_RADIUS, this.impactRadius());
        tag.putFloat(TAG_WIDTH, this.beamWidth());
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 512.0 * 512.0;
    }
}
