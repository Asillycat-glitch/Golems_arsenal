package a_silly_cat.golems_arsenal.base.event;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.base.upgrade.GolemUpgradeLevels;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;


/**
 * Lets melee attacks from a golem with ModularGolems' damage upgrade at level 5 crit, reusing the
 * crit attributes provided by the support libraries rather than inventing new ones:
 * <ul>
 *   <li>{@code l2damagetracker:crit_rate} / {@code l2damagetracker:crit_damage} (the mod only
 *   registers these on players, so {@code ModAttributes} adds them to the golem entity types)</li>
 *   <li>{@code cataclysm:additional_critical_damage} (percentage points, added on top)</li>
 * </ul>
 * Caps follow L2DamageTracker's own attribute ranges (rate 0..1, damage 0..1000). The roll is
 * applied at {@link EventPriority#HIGHEST} so it multiplies the incoming melee damage before the
 * flat/percentage bonuses of this mod (onslaught, Gilded conversion, execute) are added, which
 * keeps those flat bonuses from being multiplied and prevents exponential scaling.
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID)
public final class GolemCritHandler {

    /** Level of ModularGolems' damage modifier required to unlock crit (that modifier's max level). */
    private static final int REQUIRED_UPGRADE_LEVEL = 5;
    /** L2DamageTracker attribute limits. */
    private static final double CRIT_RATE_MAX = 1.0;
    private static final double CRIT_DAMAGE_MAX = 1000.0;

    private static Attribute critRateCache;
    private static Attribute critDamageCache;
    private static Attribute extraCritDamageCache;

    private GolemCritHandler() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onGolemMeleeCrit(LivingHurtEvent event) {
        if (event.getEntity().level().isClientSide) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        // Melee only: the golem itself must be the direct attacker, and projectiles / gun shots
        // are excluded.
        if (event.getSource().getDirectEntity() != golem
                || event.getSource().is(DamageTypeTags.IS_PROJECTILE)) {
            return;
        }
        if (isOwnSkillDamage(event.getSource().getMsgId())) {
            return;
        }
        int upgradeLevel = damageUpgradeLevel(golem);
        Attribute rateAttr = critRateAttribute();
        Attribute damageAttr = critDamageAttribute();
        if (upgradeLevel < REQUIRED_UPGRADE_LEVEL || rateAttr == null || damageAttr == null) {
            return;
        }
        double chance = Math.max(0.0, Math.min(CRIT_RATE_MAX, golem.getAttributeValue(rateAttr)));
        if (chance <= 0.0 || golem.getRandom().nextDouble() >= chance) {
            return;
        }
        Attribute extraAttr = extraCritDamageAttribute();
        double rawDamage = golem.getAttributeValue(damageAttr);
        double rawExtra = extraAttr == null ? 0.0 : golem.getAttributeValue(extraAttr);
        double critDamage = Math.max(0.0, Math.min(CRIT_DAMAGE_MAX, rawDamage));
        double extra = Math.max(0.0, Math.min(CRIT_DAMAGE_MAX, rawExtra / 100.0));
        double bonus = Math.min(CRIT_DAMAGE_MAX, critDamage + extra);
        float before = event.getAmount();
        float after = before * (float) (1.0 + bonus);
        event.setAmount(after);
    }

    /** True for damage this mod applies itself; skill damage never crits. */
    private static boolean isOwnSkillDamage(String msgId) {
        return "key_sword_magic".equals(msgId) || "lion_slash".equals(msgId)
                || "sword_rain".equals(msgId) || "stance_magic".equals(msgId)
                || "flame_magic".equals(msgId) || "genmu_zero".equals(msgId);
    }

    /** Effective level of {@code modulargolems:damage_up} installed as upgrade items. */
    private static int damageUpgradeLevel(AbstractGolemEntity<?, ?> golem) {
        return GolemUpgradeLevels.levelOf(golem, "modulargolems:damage_up");
    }


    private static Attribute critRateAttribute() {
        if (critRateCache == null) {
            critRateCache = attribute("l2damagetracker", "crit_rate");
        }
        return critRateCache;
    }

    private static Attribute critDamageAttribute() {
        if (critDamageCache == null) {
            critDamageCache = attribute("l2damagetracker", "crit_damage");
        }
        return critDamageCache;
    }

    private static Attribute extraCritDamageAttribute() {
        if (extraCritDamageCache == null) {
            extraCritDamageCache = attribute("cataclysm", "additional_critical_damage");
        }
        return extraCritDamageCache;
    }

    private static Attribute attribute(String namespace, String path) {
        return ForgeRegistries.ATTRIBUTES.getValue(new ResourceLocation(namespace, path));
    }
}
