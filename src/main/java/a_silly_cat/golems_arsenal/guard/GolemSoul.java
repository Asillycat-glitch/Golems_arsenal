package a_silly_cat.golems_arsenal.guard;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * 影子 Soul：目前只做 H0/H1 观察，不改变任何防御行为。
 *
 * <p>它不是 Entity，不持有 Level/Entity 引用；后续才会升级为 authority。
 * soulId 稳定，projection UUID 可以随重建变化。</p>
 */
public final class GolemSoul {

    public final UUID soulId;
    public final UUID owner;
    public ResourceKey<Level> dimension;
    public BlockPos lastPos;
    private final SealedFloat health = new SealedFloat();
    private final SealedFloat maxHealth = new SealedFloat();
    public boolean died;
    public long revision;
    /** 受信任的投影 NBT 摘要；重建时优先使用，而不是被攻击方写脏的 Entity 快照。 */
    public CompoundTag trustedNbt;

    GolemSoul(UUID soulId, UUID owner, ResourceKey<Level> dimension, BlockPos lastPos,
              float health, float maxHealth) {
        this.soulId = soulId;
        this.owner = owner;
        this.dimension = dimension;
        this.lastPos = lastPos;
        this.health.set(health);
        this.maxHealth.set(maxHealth);
        this.revision = 1L;
    }

    public synchronized float health() {
        return health.get();
    }

    public synchronized float maxHealth() {
        return maxHealth.get();
    }

    public synchronized void observe(float health, float maxHealth, BlockPos pos,
                                     ResourceKey<Level> dimension, boolean died) {
        if (Float.isFinite(health)) {
            this.health.set(health);
        }
        if (Float.isFinite(maxHealth) && maxHealth > 0.0F) {
            this.maxHealth.set(maxHealth);
        }
        if (pos != null) {
            this.lastPos = pos;
        }
        if (dimension != null) {
            this.dimension = dimension;
        }
        if (died) {
            this.died = true;
        }
        revision++;
    }

    public synchronized void captureNbt(CompoundTag tag) {
        if (tag != null) {
            this.trustedNbt = tag.copy();
            revision++;
        }
    }

    public synchronized CompoundTag nbt() {
        return trustedNbt == null ? null : trustedNbt.copy();
    }
}
