package a_silly_cat.golems_arsenal.base.upgrade;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.base.item.ShenTongStaffItem;
import dev.xkmc.l2library.init.events.GeneralEventHandler;
import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Black Monkey stance upgrade (max level 2).
 * <p>
 * Attacking, moving and being hurt fill the stance gauge; a full gauge converts into one stance
 * stack (3 stacks at level 1, 4 stacks at level 2). The next melee attack consumes every stack and
 * deals hit damage * stacks * {@code stance_melee_ratio} magic damage. Arrows fired while stacks are
 * held carry them in the arrow NBT and add stacks * L2lib BOW_STRENGTH * {@code stance_bow_ratio} magic damage
 * on impact. The current stack count is rendered as a rotating ring of flame particles around the
 * golem, one flame per stack, following the L2Hostility killer-aura particle-circle approach.
 */
public class GolemStanceModifier extends GolemModifier {
    public static final int MAX_LEVEL = 2;

    private static Attribute bowStrengthAttributeCache;
    private static final String PROGRESS_KEY = "golem_stance_progress";
    private static final String STACKS_KEY = "golem_stance_stacks";
    private static final ResourceKey<DamageType> STANCE_MAGIC =
            ResourceKey.create(Registries.DAMAGE_TYPE, Golems_arsenal.id("stance_magic"));
    private static final double TWO_PI = Math.PI * 2;

    public GolemStanceModifier() {
        super(StatFilterType.ATTACK, MAX_LEVEL);
    }

    @Override
    public Component getTooltip(int level) {
        return Component.translatable("upgrade.golems_arsenal.golem_stance")
                .withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        List<MutableComponent> list = new ArrayList<>();
        list.add(Component.translatable("upgrade.golems_arsenal.golem_stance.desc")
                .withStyle(ChatFormatting.GREEN));
        list.add(Component.translatable(level >= 2
                        ? "upgrade.golems_arsenal.golem_stance.desc_lv2"
                        : "upgrade.golems_arsenal.golem_stance.desc_lv1")
                .withStyle(ChatFormatting.GREEN));
        return list;
    }

    /**
     * Melee hit: consume every stance stack first, turn it into bonus magic damage, then fill the
     * gauge from attacking so a stack gained on this hit is available for the next attack.
     * Projectile hits are skipped here - arrows consume their stacks at firing time instead.
     */
    @Override
    public void onHurtTarget(AbstractGolemEntity<?, ?> golem, LivingHurtEvent event, int level) {
        if (golem.level().isClientSide
                || event.getSource().is(DamageTypeTags.IS_PROJECTILE)
                || isStanceMagic(event.getSource())) {
            return;
        }
        int stacks = consumeAll(golem);
        if (stacks > 0) {
            float bonus = (float) (event.getAmount() * stacks * Config.STANCE_MELEE_RATIO.get());
            if (holdingShenTongStaff(golem)) {
                bonus *= 2;
            }
            if (bonus > 0 && event.getEntity().isAlive()) {
                scheduleMagicHit(golem, event.getEntity(), bonus);
            }
        }
        addProgress(golem, Config.STANCE_ATTACK_GAIN.get());
    }

    /** Being hurt fills the stance gauge. */
    @Override
    public void onHurt(AbstractGolemEntity<?, ?> golem, LivingHurtEvent event, int level) {
        if (golem.level().isClientSide) {
            return;
        }
        addProgress(golem, Config.STANCE_HURT_GAIN.get());
    }

    /**
     * Movement fills the gauge; the current stack count is drawn as a rotating flame ring around
     * the golem (one flame per stack, every 3 ticks to keep the particle cost low).
     */
    @Override
    public void onAiStep(AbstractGolemEntity<?, ?> golem, int level) {
        if (golem.level().isClientSide) {
            return;
        }
        double dx = golem.getX() - golem.xOld;
        double dz = golem.getZ() - golem.zOld;
        double moved = Math.sqrt(dx * dx + dz * dz);
        if (moved > 0.001) {
            addProgress(golem, moved * Config.STANCE_MOVE_GAIN.get());
        }
        int stacks = stacksOf(golem);
        if (stacks > 0 && golem.tickCount % 3 == 0) {
            spawnStanceParticles(golem, stacks);
        }
    }

    /** Delivers the magic hit one tick later, so the original melee event cannot recurse into itself. */
    private static void scheduleMagicHit(AbstractGolemEntity<?, ?> golem, LivingEntity target, float bonus) {
        if (!(golem.level() instanceof ServerLevel server)) {
            return;
        }
        long time = server.getGameTime();
        GeneralEventHandler.schedulePersistent(() -> {
            if (server.getGameTime() < time + 1) {
                return false;
            }
            if (target.isAlive()) {
                target.hurt(magicSource(golem), bonus);
            }
            return true;
        });
    }

    private static void spawnStanceParticles(AbstractGolemEntity<?, ?> golem, int stacks) {
        if (!(golem.level() instanceof ServerLevel server)) {
            return;
        }
        double radius = golem.getBbWidth() * 0.5 + 0.4;
        double y = golem.getY() + golem.getBbHeight() * 0.35;
        double base = (server.getGameTime() * 0.12) % TWO_PI;
        for (int i = 0; i < stacks; i++) {
            double angle = base + i * (TWO_PI / stacks);
            server.sendParticles(ParticleTypes.FLAME,
                    golem.getX() + Math.cos(angle) * radius,
                    y + Math.sin(angle) * 0.15,
                    golem.getZ() + Math.sin(angle) * radius,
                    1, 0, 0.04, 0, 0.01);
        }
    }

    /**
     * Magic damage source attributed to the attacker (the golem or its arrow). The damage type
     * itself carries {@code bypasses_armor} and {@code bypasses_cooldown}, so the bonus always
     * lands; {@link #isStanceMagic} lets our own attack handlers skip it to avoid double-dipping.
     */
    public static DamageSource magicSource(Entity attacker) {
        var registry = attacker.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        return new DamageSource(registry.getHolderOrThrow(STANCE_MAGIC), attacker);
    }

    /** True when the damage source is this mod's stance magic, used to skip re-processing. */
    public static boolean isStanceMagic(DamageSource source) {
        return "stance_magic".equals(source.getMsgId());
    }

    /**
     * Current L2lib BOW_STRENGTH (projectile strength) attribute value of the golem - the arrow
     * base damage source. Returns 0 when the attribute cannot be resolved.
     */
    public static double bowStrengthOf(AbstractGolemEntity<?, ?> golem) {
        Attribute attr = bowStrengthAttribute();
        if (attr == null) {
            return 0;
        }
        return golem.getAttributeValue(attr);
    }

    public static Attribute bowStrengthAttribute() {
        if (bowStrengthAttributeCache == null) {
            bowStrengthAttributeCache = resolveL2Attribute("BOW_STRENGTH");
        }
        return bowStrengthAttributeCache;
    }

    private static Attribute resolveL2Attribute(String field) {
        try {
            Class<?> tracker = Class.forName("dev.xkmc.l2damagetracker.init.L2DamageTracker");
            Object entry = tracker.getField(field).get(null);
            Object attribute = entry.getClass().getMethod("get").invoke(entry);
            return attribute instanceof Attribute value ? value : null;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    /** Installed stance level (0 when absent). */
    public static int levelOf(AbstractGolemEntity<?, ?> golem) {
        return golem.getModifiers().getOrDefault(GolemUpgrades.STANCE.get(), 0);
    }

    public static boolean hasUpgrade(AbstractGolemEntity<?, ?> golem) {
        return levelOf(golem) > 0;
    }

    /** Stack cap: 3 at level 1, the 4th point unlocks at level 2. */
    public static int maxStacks(int level) {
        return level >= 2 ? 4 : 3;
    }

    public static int stacksOf(AbstractGolemEntity<?, ?> golem) {
        return golem.getPersistentData().getInt(STACKS_KEY);
    }

    /** Consumes every stack and returns how many were held (0 when the gauge had no stacks). */
    public static int consumeAll(AbstractGolemEntity<?, ?> golem) {
        CompoundTag data = golem.getPersistentData();
        int stacks = data.getInt(STACKS_KEY);
        if (stacks > 0) {
            data.putInt(STACKS_KEY, 0);
        }
        return stacks;
    }

    /** Clears leftover gauge/stacks when the stance upgrade is no longer installed. */
    public static void clearData(AbstractGolemEntity<?, ?> golem) {
        CompoundTag data = golem.getPersistentData();
        data.remove(PROGRESS_KEY);
        data.remove(STACKS_KEY);
    }

    /** Adds gauge progress; every full gauge converts into one stack up to the level cap. */
    public static void addProgress(AbstractGolemEntity<?, ?> golem, double amount) {
        if (amount <= 0) {
            return;
        }
        if (holdingShenTongStaff(golem)) {
            amount *= 1.25;
        }
        int maxStacks = maxStacks(levelOf(golem));
        if (maxStacks <= 0) {
            return;
        }
        CompoundTag data = golem.getPersistentData();
        double progress = data.getDouble(PROGRESS_KEY) + amount;
        int stacks = data.getInt(STACKS_KEY);
        double gauge = Config.STANCE_GAUGE_MAX.get();
        while (progress >= gauge) {
            if (stacks >= maxStacks) {
                progress = 0;
                break;
            }
            progress -= gauge;
            stacks++;
        }
        data.putDouble(PROGRESS_KEY, progress);
        data.putInt(STACKS_KEY, stacks);
    }

    /** Shen Tong Staff: +25% stance gauge from attacking/moving/being hurt, doubled stance magic. */
    private static boolean holdingShenTongStaff(AbstractGolemEntity<?, ?> golem) {
        return golem.getMainHandItem().getItem() instanceof ShenTongStaffItem;
    }
}
