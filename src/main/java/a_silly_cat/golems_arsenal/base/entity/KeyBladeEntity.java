package a_silly_cat.golems_arsenal.base.entity;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.base.event.KeySwordEventHandler;
import a_silly_cat.golems_arsenal.init.ModEntities;
import a_silly_cat.golems_arsenal.init.ModItems;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The flying/rotating key blade of the "Key Blade Spin" special move.
 * <p>
 * Throw mode: the golem spins once and hurls the blade at its target; the blade keeps flying
 * through enemies (piercing, one hit per enemy) until it reaches the throw range, then flies back
 * to the golem, still damaging enemies along the way. Orbit mode (melee): instead of throwing, the
 * blade circles the golem once, damaging every enemy it touches. It is an {@link ItemEntity}
 * subclass on purpose, so the vanilla item renderer spins the key-sword model while it flies.
 */
public class KeyBladeEntity extends ItemEntity {
    public static final int MODE_ORBIT = 0;
    public static final int MODE_THROW = 1;
    /** Visual spin speed in full rotations per second (render-only, not configurable). */
    private static final double SPIN_ROTATIONS_PER_SECOND = 3.0;
    /** The melee orbit sweeps this many full turns (2 = 720°) so no surrounding enemy is missed. */
    private static final int ORBIT_TURNS = 2;

    private static final String TAG_MODE = "KeyBladeMode";
    private static final String TAG_DAMAGE = "KeyBladeDamage";
    private static final String TAG_OWNER = "KeyBladeOwner";
    private static final String TAG_ORBIT = "KeyBladeOrbit";
    private static final String TAG_RETURN = "KeyBladeReturning";
    private static final String TAG_OUT = "KeyBladeOutDist";

    private int mode = MODE_THROW;
    private float damage;
    private UUID ownerId;
    private double orbitAngle;
    private boolean returning;
    private double outDistance;
    private final Set<UUID> hit = new HashSet<>();

    public KeyBladeEntity(EntityType<? extends KeyBladeEntity> type, Level level) {
        super(type, level);
    }

    public static KeyBladeEntity orbit(AbstractGolemEntity<?, ?> golem, double damage) {
        KeyBladeEntity blade = create(golem, MODE_ORBIT, damage);
        blade.setDeltaMovement(Vec3.ZERO);
        return blade;
    }

    public static KeyBladeEntity throwAt(AbstractGolemEntity<?, ?> golem, Vec3 aimPoint,
                                         double damage) {
        Vec3 from = golem.getEyePosition(1.0f);
        Vec3 dir = aimPoint.subtract(from).normalize();
        KeyBladeEntity blade = create(golem, MODE_THROW, damage);
        // Launch straight away at the speed-curve start; the golem's wind-up turn is driven by the
        // caller before this entity is even created.
        blade.setDeltaMovement(dir.scale(Config.KEY_BLADE_THROW_SPEED.get()
                * Config.KEY_BLADE_THROW_END_FACTOR.get()));
        return blade;
    }

    private static KeyBladeEntity create(AbstractGolemEntity<?, ?> golem, int mode, double damage) {
        Level level = golem.level();
        KeyBladeEntity blade = new KeyBladeEntity(ModEntities.KEY_BLADE.get(), level);
        blade.setItem(new ItemStack(ModItems.KEY_SWORD.get()));
        blade.setPos(golem.getX(), golem.getEyeY() - 0.1, golem.getZ());
        blade.setNoGravity(true);
        blade.setNeverPickUp();
        blade.setInvulnerable(true);
        blade.mode = mode;
        blade.damage = (float) damage;
        blade.ownerId = golem.getUUID();
        // Orbit must start at 0 and track the total swept angle; a random start near 2*PI would
        // make the blade disappear after only a short arc, losing most of the melee damage.
        blade.orbitAngle = 0;
        level.addFreshEntity(blade);
        return blade;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            return;
        }
        if (tickCount > 200) {
            discard();
            return;
        }
        LivingEntity owner = ownerEntity();
        if (owner == null || !owner.isAlive()) {
            discard();
            return;
        }
        if (mode == MODE_ORBIT) {
            tickOrbit(owner);
        } else {
            tickThrow(owner);
        }
    }

    private void tickThrow(LivingEntity owner) {
        double range = Config.KEY_BLADE_THROW_RANGE.get();
        double mid = Config.KEY_BLADE_THROW_MID_FACTOR.get();
        double end = Config.KEY_BLADE_THROW_END_FACTOR.get();
        if (!returning) {
            outDistance += getDeltaMovement().length();
            if (outDistance >= range) {
                returning = true;
            } else {
                // Outbound speed curve: accelerates toward the middle of the throw, then slows
                // down near the far end before turning back.
                double progress = outDistance / range;
                double factor = end + (mid - end) * Math.sin(Math.PI * progress);
                setDeltaMovement(getDeltaMovement().normalize()
                        .scale(Config.KEY_BLADE_THROW_SPEED.get() * factor));
            }
        }
        if (returning) {
            Vec3 to = owner.getEyePosition().subtract(position());
            double dist = to.length();
            if (dist < 1.2) {
                discard();
                return;
            }
            // Return speed curve mirrors the outbound one: slow right after the turn, fast in the
            // middle, then slow again close to the owner so the blade can be caught.
            double progress = Math.min(1.0, dist / range);
            double factor = end + (mid - end) * Math.sin(Math.PI * progress);
            setDeltaMovement(to.normalize().scale(Config.KEY_BLADE_THROW_SPEED.get() * factor));
        }
        hurtAlongPath(owner);
    }

    private void tickOrbit(LivingEntity owner) {
        orbitAngle += Math.PI * 2 / Config.KEY_BLADE_ORBIT_TICKS.get();
        // Base radius plus half the golem's bounding width: for large golems the orbit must sit
        // outside the body model, roughly where the golem's own attack ring is.
        double radius = Config.KEY_BLADE_ORBIT_RADIUS.get() + owner.getBbWidth() * 0.5;
        setPos(owner.getX() + Math.cos(orbitAngle) * radius,
                owner.getY() + owner.getBbHeight() * 0.5,
                owner.getZ() + Math.sin(orbitAngle) * radius);
        setDeltaMovement(Vec3.ZERO);
        hurtAlongPath(owner);
        if (orbitAngle >= Math.PI * 2 * ORBIT_TURNS) {
            discard();
        }
    }

    /** Each enemy is hit at most once per flight, including enemies that move into the path. */
    private void hurtAlongPath(LivingEntity owner) {
        List<LivingEntity> list = level().getEntitiesOfClass(LivingEntity.class,
                getBoundingBox().inflate(0.4),
                e -> e.isAlive() && e != owner && !e.isAlliedTo(owner) && !hit.contains(e.getUUID()));
        for (LivingEntity e : list) {
            hit.add(e.getUUID());
            e.hurt(KeySwordEventHandler.keySwordMagicSource(owner), damage);
        }
    }

    private LivingEntity ownerEntity() {
        if (ownerId == null || !(level() instanceof ServerLevel server)) {
            return null;
        }
        Entity entity = server.getEntity(ownerId);
        return entity instanceof LivingEntity living ? living : null;
    }

    /**
     * Faster visual spin while flying: the vanilla item spin is only about one revolution every
     * six seconds, so the blade's rotation speed is overridden to the config value
     * (rotations per second).
     */
    @Override
    public float getSpin(float partialTick) {
        double radiansPerTick = SPIN_ROTATIONS_PER_SECOND * Math.PI * 2 / 20.0;
        return (float) ((tickCount + partialTick) * radiansPerTick);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt(TAG_MODE, mode);
        tag.putFloat(TAG_DAMAGE, damage);
        if (ownerId != null) {
            tag.putUUID(TAG_OWNER, ownerId);
        }
        tag.putDouble(TAG_ORBIT, orbitAngle);
        tag.putBoolean(TAG_RETURN, returning);
        tag.putDouble(TAG_OUT, outDistance);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        mode = tag.getInt(TAG_MODE);
        damage = tag.getFloat(TAG_DAMAGE);
        if (tag.hasUUID(TAG_OWNER)) {
            ownerId = tag.getUUID(TAG_OWNER);
        }
        orbitAngle = tag.getDouble(TAG_ORBIT);
        returning = tag.getBoolean(TAG_RETURN);
        outDistance = tag.getDouble(TAG_OUT);
    }
}
