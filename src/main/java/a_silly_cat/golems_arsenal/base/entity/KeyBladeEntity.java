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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
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
    public static final int MODE_RISE = 2;
    public static final int MODE_FALL = 3;
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
    private static final String TAG_AIM_X = "KeyBladeAimX";
    private static final String TAG_AIM_Y = "KeyBladeAimY";
    private static final String TAG_AIM_Z = "KeyBladeAimZ";
    private static final String TAG_SPREAD = "KeyBladeSpread";
    private static final String TAG_COUNT = "KeyBladeCount";
    private static final String TAG_RISE_VY = "KeyBladeRiseVy";
    private static final String TAG_FALL_VX = "KeyBladeFallVx";
    private static final String TAG_FALL_VY = "KeyBladeFallVy";
    private static final String TAG_FALL_VZ = "KeyBladeFallVz";
    private static final String TAG_CENTER_X = "KeyBladeCenterX";
    private static final String TAG_CENTER_Z = "KeyBladeCenterZ";

    private int mode = MODE_THROW;
    private float damage;
    private UUID ownerId;
    private double orbitAngle;
    private boolean returning;
    private double outDistance;
    private double aimX;
    private double aimY;
    private double aimZ;
    private double spread;
    private int fallCount;
    private double riseVy;
    private double fallVx;
    private double fallVy;
    private double fallVz;
    private double centerX;
    private double centerZ;
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

    /**
     * Sword Rain part 1: the key blade spirals upward (fast at first, then slowing) until it
     * stops, then splits into falling iron swords (see {@link #spawnRain()}).
     */
    public static KeyBladeEntity swordRainRise(AbstractGolemEntity<?, ?> golem, Vec3 aim,
                                               double damage, int count, double spread) {
        KeyBladeEntity blade = create(golem, MODE_RISE, damage);
        blade.aimX = aim.x;
        blade.aimY = aim.y;
        blade.aimZ = aim.z;
        blade.fallCount = count;
        blade.spread = spread;
        blade.riseVy = 1.1;
        blade.centerX = golem.getX();
        blade.centerZ = golem.getZ();
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
        } else if (mode == MODE_THROW) {
            tickThrow(owner);
        } else if (mode == MODE_RISE) {
            tickRise(owner);
        } else if (mode == MODE_FALL) {
            tickFall(owner);
        }
    }

    /** Spiral rise with decreasing vertical speed; splits into falling swords at the apex. */
    private void tickRise(LivingEntity owner) {
        riseVy -= 0.055;
        if (riseVy <= 0) {
            spawnRain();
            discard();
            return;
        }
        orbitAngle += 0.5;
        double radius = 0.7;
        setPos(centerX + Math.cos(orbitAngle) * radius,
                getY() + riseVy,
                centerZ + Math.sin(orbitAngle) * radius);
        setDeltaMovement(Vec3.ZERO);
    }

    /** Split the rising blade into {@code fallCount} iron swords over the captured aim point. */
    private void spawnRain() {
        for (int i = 0; i < fallCount; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double radius = random.nextDouble() * spread;
            double x = aimX + Math.cos(angle) * radius;
            double z = aimZ + Math.sin(angle) * radius;
            double y = aimY + 9 + random.nextDouble() * 3;
            createFallingSword(x, y, z);
        }
    }

    private void createFallingSword(double x, double y, double z) {
        KeyBladeEntity sword = new KeyBladeEntity(ModEntities.KEY_BLADE.get(), level());
        sword.setItem(new ItemStack(Items.IRON_SWORD));
        sword.setPos(x, y, z);
        sword.setNoGravity(true);
        sword.setNeverPickUp();
        sword.setInvulnerable(true);
        sword.mode = MODE_FALL;
        sword.damage = damage;
        sword.ownerId = ownerId;
        sword.fallVy = -0.6;
        level().addFreshEntity(sword);
    }

    /** Falling iron sword: accelerates downward, weakly homes to nearby enemies, hits once. */
    private void tickFall(LivingEntity owner) {
        // Downward acceleration (negative), capped so the swords do not reach absurd speed.
        fallVy = Math.max(-2.6, fallVy - 0.12);
        LivingEntity target = nearestHostile(owner, 12);
        if (target != null) {
            Vec3 to = target.getEyePosition().subtract(position());
            double horizontal = Math.sqrt(to.x * to.x + to.z * to.z);
            if (horizontal > 1.0E-4) {
                double desiredX = to.x / horizontal * 0.55;
                double desiredZ = to.z / horizontal * 0.55;
                fallVx += (desiredX - fallVx) * 0.18;
                fallVz += (desiredZ - fallVz) * 0.18;
            }
        }
        // Face the current flight direction so the client renderer can tilt the blade toward it.
        double hSpeed = Math.sqrt(fallVx * fallVx + fallVz * fallVz);
        double speed = Math.sqrt(hSpeed * hSpeed + fallVy * fallVy);
        if (speed > 1.0E-4) {
            double nx = fallVx / speed;
            double nz = fallVz / speed;
            double ny = fallVy / speed;
            setYRot((float) (Math.atan2(-nx, nz) * 180.0 / Math.PI));
            setXRot((float) (Math.asin(Math.max(-1.0, Math.min(1.0, -ny))) * 180.0 / Math.PI));
        }
        moveRelative(fallVx, fallVy, fallVz);
        List<LivingEntity> targets = level().getEntitiesOfClass(LivingEntity.class,
                getBoundingBox().inflate(0.35),
                e -> e.isAlive() && e != owner && !e.isAlliedTo(owner) && !hit.contains(e.getUUID()));
        if (!targets.isEmpty()) {
            hit.add(targets.get(0).getUUID());
            targets.get(0).hurt(KeySwordEventHandler.swordRainSource(owner), damage);
            discard();
            return;
        }
        // Landed on a solid block: the sword sticks briefly in spirit and vanishes.
        if (!level().getBlockState(blockPosition().below()).isAir()) {
            discard();
        }
    }

    private void moveRelative(double vx, double vy, double vz) {
        setPos(getX() + vx, getY() + vy, getZ() + vz);
        setDeltaMovement(Vec3.ZERO);
    }

    private LivingEntity nearestHostile(LivingEntity owner, double radius) {
        List<LivingEntity> list = level().getEntitiesOfClass(LivingEntity.class,
                AABB.ofSize(position(), radius * 2, radius * 2, radius * 2),
                e -> e.isAlive() && e != owner && !e.isAlliedTo(owner));
        LivingEntity best = null;
        double bestDist = radius * radius;
        for (LivingEntity e : list) {
            double d = e.distanceToSqr(this);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
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
        // Falling rain swords (iron swords) drop normally without spinning. Checked by item so the
        // client (which does not receive the mode field) also renders them static.
        if (mode == MODE_FALL || getItem().is(Items.IRON_SWORD)) {
            return 0.0F;
        }
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
        tag.putDouble(TAG_AIM_X, aimX);
        tag.putDouble(TAG_AIM_Y, aimY);
        tag.putDouble(TAG_AIM_Z, aimZ);
        tag.putDouble(TAG_SPREAD, spread);
        tag.putInt(TAG_COUNT, fallCount);
        tag.putDouble(TAG_RISE_VY, riseVy);
        tag.putDouble(TAG_FALL_VX, fallVx);
        tag.putDouble(TAG_FALL_VY, fallVy);
        tag.putDouble(TAG_FALL_VZ, fallVz);
        tag.putDouble(TAG_CENTER_X, centerX);
        tag.putDouble(TAG_CENTER_Z, centerZ);
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
        aimX = tag.getDouble(TAG_AIM_X);
        aimY = tag.getDouble(TAG_AIM_Y);
        aimZ = tag.getDouble(TAG_AIM_Z);
        spread = tag.getDouble(TAG_SPREAD);
        fallCount = tag.getInt(TAG_COUNT);
        riseVy = tag.getDouble(TAG_RISE_VY);
        fallVx = tag.getDouble(TAG_FALL_VX);
        fallVy = tag.getDouble(TAG_FALL_VY);
        fallVz = tag.getDouble(TAG_FALL_VZ);
        centerX = tag.getDouble(TAG_CENTER_X);
        centerZ = tag.getDouble(TAG_CENTER_Z);
    }
}
