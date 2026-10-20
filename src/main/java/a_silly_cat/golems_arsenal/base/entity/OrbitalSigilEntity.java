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
 * **法阵**：贴地画出的一圈光纹。
 *
 * <p><b>保留它作为渲染示例</b>（用户 2026-10-19 的要求）：将来如果要做"法阵类联动"，
 * 这个实体 + {@code OrbitalSigilRenderer} 是一份可以直接改的样板
 * （分段、按进度绘制、贴地光带、笔尖亮斑）。
 *
 * <p>注意它<b>不是</b>引导阶段用的东西——引导用的是 {@link GuidanceBeamEntity}
 * （细光柱 + 落点移动，没有图案）。两者共用同一条轨迹数据
 * {@code OrbitalGuidancePath}，所以形状永远一致。
 *
 * <p>纯视觉：不碰撞、不可选中、不造成伤害。
 */
public class OrbitalSigilEntity extends Entity {

    /** 画完整个法阵需要的 tick。40 = 2 秒。 */
    public static final int DRAW_TICKS = 40;
    /** 画完后再亮着的 tick。 */
    public static final int HOLD_TICKS = 25;

    private static final EntityDataAccessor<Float> DATA_PROGRESS =
            SynchedEntityData.defineId(OrbitalSigilEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_RADIUS =
            SynchedEntityData.defineId(OrbitalSigilEntity.class, EntityDataSerializers.FLOAT);

    private static final String TAG_PROGRESS = "SigilProgress";
    private static final String TAG_RADIUS = "SigilRadius";

    public OrbitalSigilEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /** 在指定位置画一个法阵。 */
    public static OrbitalSigilEntity create(ServerLevel level, Vec3 center, float radius) {
        OrbitalSigilEntity sigil = new OrbitalSigilEntity(
                a_silly_cat.golems_arsenal.init.ModEntities.ORBITAL_SIGIL.get(), level);
        sigil.setPos(center.x, center.y, center.z);
        sigil.entityData.set(DATA_RADIUS, Math.max(0.5F, radius));
        sigil.entityData.set(DATA_PROGRESS, 0.0F);
        level.addFreshEntity(sigil);
        return sigil;
    }

    /** 画到哪了（0..1）。 */
    public float progress() {
        return this.entityData.get(DATA_PROGRESS);
    }

    /** 法阵半径（格）。 */
    public float radius() {
        return this.entityData.get(DATA_RADIUS);
    }

    /** 画完之后还能亮多久（渲染端用来做淡出）。 */
    public float fade() {
        int age = this.tickCount;
        if (age <= DRAW_TICKS) {
            return 1.0F;
        }
        float t = (age - DRAW_TICKS) / (float) HOLD_TICKS;
        return Math.max(0.0F, 1.0F - t);
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA_PROGRESS, 0.0F);
        this.entityData.define(DATA_RADIUS, 4.0F);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            return;
        }
        float next = this.progress() + 1.0F / DRAW_TICKS;
        this.entityData.set(DATA_PROGRESS, Math.min(1.0F, next));
        if (this.tickCount > DRAW_TICKS + HOLD_TICKS) {
            this.discard();
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.entityData.set(DATA_PROGRESS, tag.getFloat(TAG_PROGRESS));
        if (tag.contains(TAG_RADIUS)) {
            this.entityData.set(DATA_RADIUS, tag.getFloat(TAG_RADIUS));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putFloat(TAG_PROGRESS, this.progress());
        tag.putFloat(TAG_RADIUS, this.radius());
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
        return distance < 64.0 * 64.0;
    }
}
