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
 * **发射段的落点冲击**：贴地扩散的冲击环 + 中心的短闪柱。
 *
 * <p>参考对象：
 * <ul>
 *   <li>秘法涡流的 {@code Shockwave} 实体——它把"扩散"拆成
 *       {@code currentSize / maxSize / expandSpeed / intensity / fadeDuration}，
 *       我们照这个思路，只是把参数放进同步字段由渲染端插值；</li>
 *   <li>{@code mgdp} 的「五雷天罚」——它的做法就是召唤纯视觉 {@code LightningBolt}，
 *       说明"打击感"主要来自**瞬间性**，所以我们这里的时间很短（{@link #LIFE} = 26 tick）。</li>
 * </ul>
 *
 * <p>纯视觉实体：不碰撞、不可选中、不造成伤害（伤害仍由 {@link OrbitalBeamEntity} 负责）。
 * 半径与进度都同步，渲染端据此重建几何——不传输顶点。
 */
public class OrbitalImpactEntity extends Entity {

    /** 整段特效时长（26 tick ≈ 1.3 秒）。用完即弃。 */
    public static final int LIFE = 26;

    private static final EntityDataAccessor<Float> DATA_PROGRESS =
            SynchedEntityData.defineId(OrbitalImpactEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_RADIUS =
            SynchedEntityData.defineId(OrbitalImpactEntity.class, EntityDataSerializers.FLOAT);
    /**
     * 弹体残留线的**顶端 Y**（发射高度）。
     *
     * <p>用户要求：弹体经过的空气要留下一条"看着像激光"的余痕。
     * 落地时把这条线整根画出来再淡出——弹体从天而降本身只要 1 秒多，
     * 视觉上和"一路拉出线"几乎没差别，但省掉了一个额外的轨迹实体。
     */
    private static final EntityDataAccessor<Float> DATA_TRAIL_TOP =
            SynchedEntityData.defineId(OrbitalImpactEntity.class, EntityDataSerializers.FLOAT);

    private static final String TAG_PROGRESS = "ImpactProgress";
    private static final String TAG_RADIUS = "ImpactRadius";

    public OrbitalImpactEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /** 在落点生成一次冲击。{@code radius} 是冲击环最终扩散到的半径（格）。 */
    public static OrbitalImpactEntity create(ServerLevel level, Vec3 pos, float radius) {
        return create(level, pos, radius, 0.0F);
    }

    /** @param trailTopY 残留线顶端；传 0 表示不画残留线 */
    public static OrbitalImpactEntity create(ServerLevel level, Vec3 pos, float radius, float trailTopY) {
        OrbitalImpactEntity impact = new OrbitalImpactEntity(
                a_silly_cat.golems_arsenal.init.ModEntities.ORBITAL_IMPACT.get(), level);
        impact.setPos(pos.x, pos.y, pos.z);
        impact.entityData.set(DATA_RADIUS, Math.max(1.0F, radius));
        impact.entityData.set(DATA_PROGRESS, 0.0F);
        impact.entityData.set(DATA_TRAIL_TOP, Math.max(0.0F, trailTopY));
        level.addFreshEntity(impact);
        return impact;
    }

    /** 0..1 的播放进度。 */
    public float progress() {
        return this.entityData.get(DATA_PROGRESS);
    }

    /** 冲击环的最终半径（格）。 */
    public float radius() {
        return this.entityData.get(DATA_RADIUS);
    }

    /** 残留线顶端 Y；0 表示不画。 */
    public float trailTopY() {
        return this.entityData.get(DATA_TRAIL_TOP);
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA_PROGRESS, 0.0F);
        this.entityData.define(DATA_RADIUS, 6.0F);
        this.entityData.define(DATA_TRAIL_TOP, 0.0F);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            return;
        }
        // 图三的"碎屑上冲"：前 8 tick 在落点周围抛碎屑（服务端发包，客户端各自播放）
        if (this.tickCount <= 8) {
            spawnDebris();
        }
        float next = this.progress() + 1.0F / LIFE;
        if (next >= 1.0F) {
            this.discard();
            return;
        }
        this.entityData.set(DATA_PROGRESS, next);
    }

    /**
     * 抛碎屑：向上 + 向外的初速度，让碎片"炸起来"而不是散落。
     *
     * <p>参考图三——击中后是<b>碎屑上冲</b>，不是单纯一圈环。用原版粒子即可
     * （不引入新贴图，也不会额外占带宽：{@code sendParticles} 只发给追踪该区块的玩家）。
     */
    private void spawnDebris() {
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        float r = this.radius() * 0.45F;
        // 火星主体已经由渲染端的几何火粒（OrbitalImpactRenderer#drawSparks）负责，
        // 这里补原版火花/烟尘/碎屑，让爆炸有体积和残留。
        server.sendParticles(net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK,
                this.getX(), this.getY() + 0.4D, this.getZ(),
                4, r * 0.55D, 0.35D, r * 0.55D, 0.45D);
        server.sendParticles(net.minecraft.core.particles.ParticleTypes.LAVA,
                this.getX(), this.getY() + 0.2D, this.getZ(),
                3, r * 0.4D, 0.15D, r * 0.4D, 0.0D);
        if (this.tickCount == 1) {
            // 爆炸主粒子：一个大的爆炸云 + 火团 + 烟 + 向外甩的熔岩/电火花
            server.sendParticles(net.minecraft.core.particles.ParticleTypes.EXPLOSION_EMITTER,
                    this.getX(), this.getY() + 0.8D, this.getZ(),
                    1, 0.0D, 0.0D, 0.0D, 0.0D);
            server.sendParticles(net.minecraft.core.particles.ParticleTypes.EXPLOSION,
                    this.getX(), this.getY() + 0.8D, this.getZ(),
                    2, r * 0.25D, 0.25D, r * 0.25D, 0.0D);
            server.sendParticles(net.minecraft.core.particles.ParticleTypes.FLAME,
                    this.getX(), this.getY() + 0.6D, this.getZ(),
                    40, r * 0.5D, 0.6D, r * 0.5D, 0.08D);
            server.sendParticles(net.minecraft.core.particles.ParticleTypes.LAVA,
                    this.getX(), this.getY() + 0.4D, this.getZ(),
                    20, r * 0.6D, 0.4D, r * 0.6D, 0.6D);
            server.sendParticles(net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK,
                    this.getX(), this.getY() + 0.5D, this.getZ(),
                    50, r * 0.7D, 0.7D, r * 0.7D, 0.9D);
            server.playSound(null, this.getX(), this.getY(), this.getZ(),
                    net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE,
                    net.minecraft.sounds.SoundSource.PLAYERS, 2.5F, 0.6F);
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
        return distance < 96.0 * 96.0;
    }
}
