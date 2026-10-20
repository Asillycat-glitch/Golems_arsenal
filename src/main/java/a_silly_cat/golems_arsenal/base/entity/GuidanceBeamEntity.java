package a_silly_cat.golems_arsenal.base.entity;

import a_silly_cat.golems_arsenal.tech.orbit.OrbitalGuidancePath;
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
 * **引导光束**：从高空照下来的一道细光，落点沿 {@link OrbitalGuidancePath} 移动。
 *
 * <p><b>它不是"画图案"</b>（2026-10-19 返工）：上一版把轨迹画在地上变成一个发光法阵，
 * 那是魔法侧观感。现在实体**停在圆心不动**，只同步 {@code progress}，
 * 由渲染端算出当前落点——所以移动完全在客户端完成，**每 tick 不产生额外网络包**，
 * 服务端也不用挪实体。
 *
 * <p><b>它是纯视觉的</b>：不造成伤害、不破坏方块。伤害仍然由 {@link OrbitalBeamEntity}
 * 负责（用户已定："破坏效果去掉，伤害保留"）。这里是引导阶段的"指示光"。
 *
 * <p>同步字段四个：进度、半径、光源高度、光束宽度——渲染需要的全部信息，
 * 都不需要每 tick 更新位置。
 */
public class GuidanceBeamEntity extends Entity {

    /**
     * 整段「引导 + 聚合瞄准」的 tick —— <b>直接取路径里的总时长</b>，
     * 免得这里和目标里各写一个数字（之前 80 tick = 4 秒，瞄准太拖）。
     */
    public static final int TOTAL_TICKS = OrbitalGuidancePath.AIM_TOTAL_TICKS;

    private static final EntityDataAccessor<Float> DATA_PROGRESS =
            SynchedEntityData.defineId(GuidanceBeamEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_RADIUS =
            SynchedEntityData.defineId(GuidanceBeamEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_ALTITUDE =
            SynchedEntityData.defineId(GuidanceBeamEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_WIDTH =
            SynchedEntityData.defineId(GuidanceBeamEntity.class, EntityDataSerializers.FLOAT);

    private static final String TAG_PROGRESS = "GuideProgress";
    private static final String TAG_RADIUS = "GuideRadius";
    private static final String TAG_ALTITUDE = "GuideAltitude";
    private static final String TAG_WIDTH = "GuideWidth";

    public GuidanceBeamEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /** 在 {@code center} 处开始一次引导（圆心静止，落点由进度算）。 */
    public static GuidanceBeamEntity create(ServerLevel level, Vec3 center, float radius,
                                            float altitude, float width) {
        GuidanceBeamEntity beam =
                new GuidanceBeamEntity(a_silly_cat.golems_arsenal.init.ModEntities.GUIDANCE_BEAM.get(), level);
        beam.setPos(center.x, center.y, center.z);
        beam.entityData.set(DATA_RADIUS, Math.max(0.5F, radius));
        beam.entityData.set(DATA_ALTITUDE, Math.max(64.0F, altitude));
        beam.entityData.set(DATA_WIDTH, Math.max(0.05F, width));
        beam.entityData.set(DATA_PROGRESS, 0.0F);
        level.addFreshEntity(beam);
        return beam;
    }

    public float progress() {
        return this.entityData.get(DATA_PROGRESS);
    }

    public float radius() {
        return this.entityData.get(DATA_RADIUS);
    }

    public float altitude() {
        return this.entityData.get(DATA_ALTITUDE);
    }

    public float beamWidth() {
        return this.entityData.get(DATA_WIDTH);
    }

    /** 当前落点相对圆心的 XZ 偏移（渲染端用。服务端一般不需要）。 */
    public float[] currentOffset() {
        return OrbitalGuidancePath.pointAt(this.progress(), this.radius());
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA_PROGRESS, 0.0F);
        this.entityData.define(DATA_RADIUS, 6.0F);
        this.entityData.define(DATA_ALTITUDE, 300.0F);
        this.entityData.define(DATA_WIDTH, 0.35F);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            return;
        }
        float next = this.progress() + 1.0F / TOTAL_TICKS;
        if (next >= 1.0F) {
            // 引导结束：先消失。发射阶段的特效是下一步单独做的，不在这里接。
            this.discard();
            return;
        }
        this.entityData.set(DATA_PROGRESS, next);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.entityData.set(DATA_PROGRESS, tag.getFloat(TAG_PROGRESS));
        if (tag.contains(TAG_RADIUS)) {
            this.entityData.set(DATA_RADIUS, tag.getFloat(TAG_RADIUS));
        }
        if (tag.contains(TAG_ALTITUDE)) {
            this.entityData.set(DATA_ALTITUDE, tag.getFloat(TAG_ALTITUDE));
        }
        if (tag.contains(TAG_WIDTH)) {
            this.entityData.set(DATA_WIDTH, tag.getFloat(TAG_WIDTH));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putFloat(TAG_PROGRESS, this.progress());
        tag.putFloat(TAG_RADIUS, this.radius());
        tag.putFloat(TAG_ALTITUDE, this.altitude());
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
