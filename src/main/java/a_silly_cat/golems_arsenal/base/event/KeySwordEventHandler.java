package a_silly_cat.golems_arsenal.base.event;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.base.entity.KeyBladeEntity;
import a_silly_cat.golems_arsenal.base.item.KeySwordItem;
import a_silly_cat.golems_arsenal.base.upgrade.GolemFlagModifier;
import a_silly_cat.golems_arsenal.base.upgrade.GolemUpgrades;
import a_silly_cat.golems_arsenal.compat.golemmagicka.ScrollSchoolHelper;
import a_silly_cat.golems_arsenal.init.ModTags;
import dev.xkmc.l2library.init.events.GeneralEventHandler;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * Key Sword special attacks (all three can trigger on the same hit, each with an independent
 * roll): 3-4 blaze fireballs, a lightning strike and a frost nova like L2Complements'
 * winterstorm wand. Every special hit is {@code fixed + ratio x attack damage + spell power}
 * where the spell-power part only exists while Iron's Spells / Golem Magicka is installed.
 * The execute effect (more damage the lower the target's HP, max at 10% HP) scales the base
 * hit. Works for players and every golem type holding the sword in the main hand.
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID)
public final class KeySwordEventHandler {
    private static final String FIREBALL_TAG = "GolemsArsenalKeyFireball";
    private static final String FIREBALL_DAMAGE_TAG = "GolemsArsenalKeyFireballDamage";
    private static final String COOLDOWN_KEY = "GolemsArsenalKeySwordCooldown";
    private static final String KEY_BLADE_CD_KEY = "GolemsArsenalKeyBladeCooldown";
    private static final String MAGIC_MSG_ID = "key_sword_magic";
    /** Fixed internal cooldown (ticks) between key blade spin uses; not configurable. */
    private static final int KEY_BLADE_SPIN_COOLDOWN_TICKS = 80;
    /** Wind-up turn (ticks) before the ranged throw; the blade spawns only after the turn. */
    private static final int FAST_SPIN_TICKS = 8;
    private static final ResourceKey<DamageType> KEY_SWORD_MAGIC =
            ResourceKey.create(Registries.DAMAGE_TYPE, Golems_arsenal.id(MAGIC_MSG_ID));

    private static Attribute spellPowerCache;
    private static Attribute fireSpellPowerCache;
    private static Attribute iceSpellPowerCache;
    private static Attribute lightningSpellPowerCache;
    private static MobEffect l2IceEffectCache;

    private KeySwordEventHandler() {
    }

    @SubscribeEvent
    public static void onKeySwordHit(LivingHurtEvent event) {
        if (event.getEntity().level().isClientSide) {
            return;
        }
        if (MAGIC_MSG_ID.equals(event.getSource().getMsgId())) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof LivingEntity attacker)) {
            return;
        }
        if (attacker == event.getEntity()
                || !(attacker.getMainHandItem().getItem() instanceof KeySwordItem)) {
            return;
        }
        LivingEntity victim = event.getEntity();

        // Execute: lower target HP = more damage, max bonus reached at the config HP ratio.
        float amount = (float) (event.getAmount() * executeFactor(victim));

        // Special Attack 1 (ported into the special-move system): the key sword itself no longer
        // randomly fires the three element attacks. A golem holding it that has a special-move
        // upgrade installed AND a scroll upgrade with a recorded fire / ice / lightning spell
        // fires the matching element attack on each hit, gated only by the shared cooldown.
        if (attacker instanceof AbstractGolemEntity<?, ?> golem && specialReady(golem)) {
            String element = scrollElement(golem);
            if (element != null) {
                switch (element) {
                    case "fire" -> fireballAttack(attacker, victim);
                    case "ice" -> frostAttack(attacker, victim);
                    case "lightning" -> lightningAttack(attacker, victim);
                    default -> {
                    }
                }
                startSpecialCooldown(golem);
            }
        }

        // Key Blade Spin special move (example of the special-move upgrade category): melee hits
        // orbit the key blade around the golem once; ranged targets are thrown at from the
        // per-golem tick (updateKeyBladeThrow).
        if (attacker instanceof AbstractGolemEntity<?, ?> golem
                && GolemFlagModifier.hasUpgrade(golem, GolemUpgrades.KEY_BLADE_SPIN.get())
                && keyBladeReady(golem)) {
            double damage = Config.KEY_BLADE_FIXED_DAMAGE.get()
                    + Config.KEY_BLADE_ATTACK_DAMAGE_RATIO.get()
                    * attacker.getAttributeValue(Attributes.ATTACK_DAMAGE);
            KeyBladeEntity.orbit(golem, damage);
            startKeyBladeCooldown(golem);
        }

        event.setAmount(amount);
    }

    private static boolean specialReady(LivingEntity attacker) {
        return attacker.level().getGameTime()
                >= attacker.getPersistentData().getLong(COOLDOWN_KEY);
    }

    private static void startSpecialCooldown(LivingEntity attacker) {
        attacker.getPersistentData().putLong(COOLDOWN_KEY,
                attacker.level().getGameTime() + Config.KEY_SWORD_SPECIAL_COOLDOWN.get());
    }

    /**
     * Periodic ranged half of the Key Blade Spin special move, called from the golem's per-tick
     * pass: throws the key blade at a distant target. Melee-range targets are handled by the
     * orbit branch in {@link #onKeySwordHit}.
     */
    public static void updateKeyBladeThrow(AbstractGolemEntity<?, ?> golem, ItemStack stack) {
        if (!(stack.getItem() instanceof KeySwordItem)
                || !GolemFlagModifier.hasUpgrade(golem, GolemUpgrades.KEY_BLADE_SPIN.get())
                || !keyBladeReady(golem)) {
            return;
        }
        LivingEntity target = golem.getTarget();
        if (target == null || !target.isAlive()) {
            return;
        }
        double melee = Config.KEY_BLADE_ORBIT_TRIGGER_RANGE.get();
        if (golem.distanceToSqr(target) <= melee * melee) {
            return;
        }
        double damage = Config.KEY_BLADE_FIXED_DAMAGE.get()
                + Config.KEY_BLADE_ATTACK_DAMAGE_RATIO.get()
                * golem.getAttributeValue(Attributes.ATTACK_DAMAGE);
        Vec3 aim = target.getBoundingBox().getCenter().add(0, target.getBbHeight() * 0.3, 0);
        spinThenThrow(golem, aim, damage);
        startKeyBladeCooldown(golem);
    }

    /**
     * Ranged throw sequence: the golem first does one fast 360° turn (nothing visible in hand),
     * and only after the turn completes is the key blade created and thrown at the captured aim
     * point. Driven by the L2lib per-server-tick task, so the blade never hovers in front of the
     * golem while waiting.
     */
    private static void spinThenThrow(AbstractGolemEntity<?, ?> golem, Vec3 aim, double damage) {
        if (!(golem.level() instanceof ServerLevel server)) {
            return;
        }
        // Anchor the turn to the starting facing: even if the movement AI pulls the golem's
        // rotation back between ticks, every tick forces the exact angle of the full turn, so the
        // 360° spin is always completed before the blade is thrown.
        float startYaw = golem.getYRot();
        float startHead = golem.yHeadRot;
        float startBody = golem.yBodyRot;
        float step = 360.0f / FAST_SPIN_TICKS;
        int[] spins = {0};
        GeneralEventHandler.schedulePersistent(() -> {
            if (!golem.isAlive() || golem.level() != server) {
                return true;
            }
            spins[0]++;
            float angle = step * spins[0];
            golem.setYRot(startYaw + angle);
            golem.yHeadRot = startHead + angle;
            golem.yBodyRot = startBody + angle;
            if (spins[0] < FAST_SPIN_TICKS) {
                return false;
            }
            KeyBladeEntity.throwAt(golem, aim, damage);
            return true;
        });
    }

    private static boolean keyBladeReady(AbstractGolemEntity<?, ?> golem) {
        return golem.level().getGameTime()
                >= golem.getPersistentData().getLong(KEY_BLADE_CD_KEY);
    }

    private static void startKeyBladeCooldown(AbstractGolemEntity<?, ?> golem) {
        golem.getPersistentData().putLong(KEY_BLADE_CD_KEY,
                golem.level().getGameTime() + KEY_BLADE_SPIN_COOLDOWN_TICKS);
    }

    /** Key-sword fireballs deal the stored scaled damage instead of vanilla fixed fireball damage. */
    @SubscribeEvent
    public static void onFireballImpact(ProjectileImpactEvent event) {
        if (event.getEntity().level().isClientSide
                || !(event.getEntity() instanceof SmallFireball fireball)) {
            return;
        }
        CompoundTag tag = fireball.getPersistentData();
        if (!tag.getBoolean(FIREBALL_TAG)) {
            return;
        }
        event.setCanceled(true);
        double damage = tag.getDouble(FIREBALL_DAMAGE_TAG);
        Entity attacker = fireball.getOwner();
        fireball.discard();

        if (event.getRayTraceResult() instanceof EntityHitResult hit
                && hit.getEntity() instanceof LivingEntity target && target.isAlive()) {
            if (attacker instanceof LivingEntity living) {
                target.hurt(keySwordMagicSource(living), (float) damage);
            } else {
                target.hurt(target.damageSources().magic(), (float) damage);
            }
            target.setSecondsOnFire(5);
        } else if (fireball.level() instanceof ServerLevel server) {
            Vec3 pos = event.getRayTraceResult().getLocation();
            server.sendParticles(ParticleTypes.FLAME, pos.x, pos.y, pos.z,
                    8, 0.15, 0.15, 0.15, 0.02);
        }
    }

    /** Linear ramp from 0 at full HP to {@code execute_max_bonus} at {@code execute_hp_ratio} HP. */
    private static double executeFactor(LivingEntity victim) {
        float max = victim.getMaxHealth();
        if (max <= 0) {
            return 1.0;
        }
        double missing = 1.0 - victim.getHealth() / max;
        double threshold = 1.0 - Config.KEY_SWORD_EXECUTE_HP_RATIO.get();
        double maxBonus = Config.KEY_SWORD_EXECUTE_MAX_BONUS.get();
        if (missing >= threshold) {
            return 1.0 + maxBonus;
        }
        if (missing <= 0) {
            return 1.0;
        }
        return 1.0 + maxBonus * missing / threshold;
    }

    private static void fireballAttack(LivingEntity attacker, LivingEntity victim) {
        double damage = specialDamage(attacker, "fire",
                Config.KEY_SWORD_FIREBALL_FIXED.get(),
                Config.KEY_SWORD_FIREBALL_ATTACK_RATIO.get());
        int min = Config.KEY_SWORD_FIREBALL_COUNT_MIN.get();
        int max = Config.KEY_SWORD_FIREBALL_COUNT_MAX.get();
        int count = min + (max > min ? attacker.getRandom().nextInt(max - min + 1) : 0);
        Level level = attacker.level();
        for (int i = 0; i < count; i++) {
            Vec3 from = attacker.getEyePosition(1.0f);
            Vec3 to = victim.getBoundingBox().getCenter().add(0, victim.getBbHeight() * 0.3, 0);
            Vec3 dir = to.subtract(from).normalize();
            double spread = 0.14;
            dir = dir.add((attacker.getRandom().nextDouble() - 0.5) * spread,
                    (attacker.getRandom().nextDouble() - 0.5) * spread,
                    (attacker.getRandom().nextDouble() - 0.5) * spread).normalize().scale(0.9);
            SmallFireball fireball = new SmallFireball(level, attacker, dir.x, dir.y, dir.z);
            fireball.getPersistentData().putBoolean(FIREBALL_TAG, true);
            fireball.getPersistentData().putDouble(FIREBALL_DAMAGE_TAG, damage);
            level.addFreshEntity(fireball);
        }
    }

    private static void lightningAttack(LivingEntity attacker, LivingEntity victim) {
        double damage = specialDamage(attacker, "lightning",
                Config.KEY_SWORD_LIGHTNING_FIXED.get(),
                Config.KEY_SWORD_LIGHTNING_ATTACK_RATIO.get());
        Level level = attacker.level();
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt != null) {
            bolt.moveTo(victim.getX(), victim.getY(), victim.getZ());
            bolt.setVisualOnly(true);
            level.addFreshEntity(bolt);
        }
        victim.hurt(keySwordMagicSource(attacker), (float) damage);
    }

    /**
     * Frost nova centered on the victim, mirroring L2Complements' winterstorm wand: pushes nearby
     * mobs away, freezes them and applies the L2Complements "frozen" effect. The freeze/effect
     * duration grows with the attacker's attack damage.
     */
    private static void frostAttack(LivingEntity attacker, LivingEntity victim) {
        double damage = specialDamage(attacker, "ice",
                Config.KEY_SWORD_FROST_FIXED.get(),
                Config.KEY_SWORD_FROST_ATTACK_RATIO.get());
        victim.hurt(keySwordMagicSource(attacker), (float) damage);

        Level level = attacker.level();
        double radius = Config.KEY_SWORD_FROST_RADIUS.get();
        int duration = Config.KEY_SWORD_FROST_DURATION_BASE.get()
                + (int) Math.round(Config.KEY_SWORD_FROST_DURATION_PER_DAMAGE.get()
                * attacker.getAttributeValue(Attributes.ATTACK_DAMAGE));
        MobEffect ice = l2IceEffect();
        List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class,
                AABB.ofSize(victim.position(), radius * 2, radius * 2, radius * 2),
                e -> e.isAlive() && e != victim && e != attacker && !e.isAlliedTo(attacker));
        for (LivingEntity e : nearby) {
            Vec3 push = e.position().subtract(victim.position()).normalize().scale(0.35);
            e.push(push.x, push.y, push.z);
            // The wand's freeze meter is always raised to 140 (full freeze); a shorter meter
            // decays before the frost effect can be felt.
            e.setTicksFrozen(Math.max(e.getTicksFrozen(), Math.max(duration, 140)));
            if (ice != null) {
                e.addEffect(new MobEffectInstance(ice, duration, 0));
            }
        }
        if (level instanceof ServerLevel server) {
            Vec3 center = victim.position();
            server.sendParticles(ParticleTypes.SNOWFLAKE,
                    center.x, center.y + victim.getBbHeight() * 0.5, center.z,
                    30, radius * 0.5, radius * 0.5, radius * 0.5, 0.01);
        }
    }

    /**
     * fixed + attack_ratio x attack damage + spell_ratio x generic spell power
     * + element_ratio x elemental spell power. The spell-power terms only apply when the attacker
     * is a golem that has a special-move upgrade installed AND its scroll upgrade has recorded a
     * spell of the matching school (fire for the fireballs, ice for the frost nova, lightning for
     * the lightning strike), so the damage "ports" the recorded spell's element into the attack.
     */
    private static double specialDamage(LivingEntity attacker, String element,
                                        double fixed, double attackRatio) {
        double damage = fixed + attackRatio * attacker.getAttributeValue(Attributes.ATTACK_DAMAGE);
        if (attacker instanceof AbstractGolemEntity<?, ?> golem
                && hasSpecialMove(golem)
                && matchingScrollSchool(golem, element)) {
            damage += Config.KEY_SWORD_SPELL_POWER_RATIO.get()
                    * attributeValue(attacker, spellPowerCache());
            damage += Config.KEY_SWORD_ELEMENTAL_SPELL_POWER_RATIO.get()
                    * attributeValue(attacker, elementAttribute(element));
        }
        return damage;
    }

    private static boolean hasSpecialMove(AbstractGolemEntity<?, ?> golem) {
        return golem.getUpgrades().stream()
                .anyMatch(item -> new ItemStack(item).is(ModTags.SPECIAL_MOVE_UPGRADES));
    }

    /**
     * Element of the recorded scroll spell ("fire", "ice" or "lightning") when the golem has a
     * special-move upgrade installed and Golem Magicka is present; null otherwise. Other spell
     * schools (blood, nature, ...) do not map to any of the three special attacks.
     */
    private static String scrollElement(AbstractGolemEntity<?, ?> golem) {
        if (!hasSpecialMove(golem) || !ModList.get().isLoaded("golemmagicka")) {
            return null;
        }
        String school = ScrollSchoolHelper.schoolOf(golem);
        if (school != null && ScrollSchoolHelper.hasMatchingSchool(golem, school)
                && ("fire".equals(school) || "ice".equals(school) || "lightning".equals(school))) {
            return school;
        }
        return null;
    }

    private static boolean matchingScrollSchool(AbstractGolemEntity<?, ?> golem, String element) {
        if (!ModList.get().isLoaded("golemmagicka")) {
            return false;
        }
        return ScrollSchoolHelper.hasMatchingSchool(golem, element);
    }

    private static double attributeValue(LivingEntity entity, Attribute attribute) {
        return attribute == null ? 0 : entity.getAttributeValue(attribute);
    }

    private static Attribute elementAttribute(String element) {
        return switch (element) {
            case "fire" -> fireSpellPowerAttribute();
            case "ice" -> iceSpellPowerAttribute();
            case "lightning" -> lightningSpellPowerAttribute();
            default -> null;
        };
    }

    private static Attribute spellPowerCache() {
        if (spellPowerCache == null) {
            spellPowerCache = ForgeRegistries.ATTRIBUTES.getValue(
                    new ResourceLocation("irons_spellbooks", "spell_power"));
        }
        return spellPowerCache;
    }

    private static Attribute fireSpellPowerAttribute() {
        if (fireSpellPowerCache == null) {
            fireSpellPowerCache = ForgeRegistries.ATTRIBUTES.getValue(
                    new ResourceLocation("irons_spellbooks", "fire_spell_power"));
        }
        return fireSpellPowerCache;
    }

    private static Attribute iceSpellPowerAttribute() {
        if (iceSpellPowerCache == null) {
            iceSpellPowerCache = ForgeRegistries.ATTRIBUTES.getValue(
                    new ResourceLocation("irons_spellbooks", "ice_spell_power"));
        }
        return iceSpellPowerCache;
    }

    private static Attribute lightningSpellPowerAttribute() {
        if (lightningSpellPowerCache == null) {
            lightningSpellPowerCache = ForgeRegistries.ATTRIBUTES.getValue(
                    new ResourceLocation("irons_spellbooks", "lightning_spell_power"));
        }
        return lightningSpellPowerCache;
    }

    /** L2Complements' "frozen" effect, resolved by registry name (only applies when loaded). */
    private static MobEffect l2IceEffect() {
        if (l2IceEffectCache == null) {
            l2IceEffectCache = ForgeRegistries.MOB_EFFECTS.getValue(
                    new ResourceLocation("l2complements", "frozen"));
        }
        return l2IceEffectCache;
    }

    /** Magic damage source attributed to the attacker; damage type is tagged forge:is_magic. */
    public static DamageSource keySwordMagicSource(Entity attacker) {
        var registry = attacker.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        return new DamageSource(registry.getHolderOrThrow(KEY_SWORD_MAGIC), attacker);
    }
}
