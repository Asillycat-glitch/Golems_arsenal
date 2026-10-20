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


import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets melee attacks from a golem with ModularGolems' damage upgrade at level 5 crit, reusing the
 * crit attributes provided by the support libraries rather than inventing new ones:
 * <ul>
 *   <li>{@code l2damagetracker:crit_rate} / {@code l2damagetracker:crit_damage} (the mod only
 *   registers these on players, so {@code ModAttributes} adds them to the golem entity types)</li>
 *   <li>{@code cataclysm:additional_critical_damage} (percentage points, added on top)</li>
 * </ul>
 * Caps follow L2DamageTracker's own attribute ranges (rate 0..1, damage 0..1000).
 *
 * <h2>这里是全模组唯一的暴击判定与乘算</h2>
 * 指令升级的「暴击」效果<b>不</b>自己算暴击，它只是往 {@link #grantBonusCritRate} 放一笔
 * 临时暴击率，由本类统一掷骰与乘算。这样「暴击」这条规则只有一个实现，
 * 也不会出现"绕开 crit_rate 判定凭空造一次暴击"的第二套逻辑。
 *
 * <h2>优先级为什么是 HIGH</h2>
 * 指令升级的触发挂在 Modular Golems 的 {@code onHurtTarget}（默认优先级，也在
 * {@code LivingHurtEvent} 上）。本类必须排在它<b>之后</b>才能读到那一笔临时暴击率，
 * 所以用 HIGH。<b>不要改回 HIGHEST</b> —— 那样临时暴击率永远赶不上同一次命中。
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

    @SubscribeEvent(priority = EventPriority.HIGH)
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
        Attribute rateAttr = critRateAttribute();
        Attribute damageAttr = critDamageAttribute();
        if (rateAttr == null || damageAttr == null) {
            return;
        }
        // 优先级用 HIGH 而不是 HIGHEST：指令升级的触发挂在 Modular Golems 的 onHurtTarget 上
        // （默认优先级），它可能给这一击加一笔临时暴击率。暴击是本模组里唯一会「消费」
        // 那一笔的地方，所以必须排在它之后读，否则同一次命中永远读不到。
        // 这一改动不影响暴击与其它模组加成的相对顺序：本类仍然在绝大多数处理器之前。
        double bonusRate = consumeBonusCritRate(golem);
        int upgradeLevel = damageUpgradeLevel(golem);
        if (upgradeLevel < REQUIRED_UPGRADE_LEVEL && bonusRate <= 0.0) {
            return;
        }
        double base = Math.max(0.0, Math.min(CRIT_RATE_MAX, golem.getAttributeValue(rateAttr)));
        double chance = Math.max(0.0, Math.min(CRIT_RATE_MAX, base + bonusRate));
        if (chance <= 0.0 || golem.getRandom().nextDouble() >= chance) {
            return;
        }
        Attribute extraAttr = extraCritDamageAttribute();
        double rawDamage = golem.getAttributeValue(damageAttr);
        double rawExtra = extraAttr == null ? 0.0 : golem.getAttributeValue(extraAttr);
        double critDamage = Math.max(0.0, Math.min(CRIT_DAMAGE_MAX, rawDamage));
        double extra = Math.max(0.0, Math.min(CRIT_DAMAGE_MAX, rawExtra / 100.0));
        double bonusDamage = Math.min(CRIT_DAMAGE_MAX, critDamage + extra);
        float before = event.getAmount();
        float after = before * (float) (1.0 + bonusDamage);
        event.setAmount(after);
    }

    /**
     * 本模组自己的技能伤害不暴击、也不触发指令升级的抽取。
     * <p>
     * 凤凰线的三个类型也要在里面：{@code mystic_blast} 是秘奥义 5 连爆（暴击会把它抬过
     * {@code MysticBlastFloorListener} 的 base，让 0.5 保底算在错误的分母上），
     * {@code phoenix_fire} / {@code phoenix_impact} 是上升段与落地爆 —— 它们一直漏在这张表外，
     * 顺手补齐，免得"特技伤害不暴击"这条规则在凤凰身上破例。
     * <p>
     * {@code flame_fist} 是炎拳升级的追加段、{@code command_extra} 是指令升级的追击段：
     * 两者都由 {@code onHurtTarget} 自己打出，<b>必须在表内</b>，
     * 否则它们会各自触发自己（{@code command_extra} 曾经漏在这里，
     * 表现为"追击段又触发一次追击"）。
     */
    public static boolean isOwnSkillDamage(String msgId) {
        return "key_sword_magic".equals(msgId) || "lion_slash".equals(msgId)
                || "sword_rain".equals(msgId) || "stance_magic".equals(msgId)
                || "flame_magic".equals(msgId) || "genmu_zero".equals(msgId)
                || "phoenix_fire".equals(msgId) || "phoenix_impact".equals(msgId)
                || "mystic_blast".equals(msgId) || "flame_fist".equals(msgId)
                || "command_extra".equals(msgId);
    }

    // ------------------------------------------------------------------ 指令升级的暴击加成

    /**
     * 一次性暴击率加成：指令升级触发「暴击」效果时往这里放一笔，本类在判定时读掉。
     * <p>
     * 为什么用「读掉」而不是挂属性修饰符：属性修饰符是<b>持久</b>的，
     * 为一击去挂再摘既容易漏摘（留下永久暴击率），又会在属性面板上留下噪音。
     * 存一个待消费的值则天然只作用于下一击，语义也更准确 ——「这一击暴击了」。
     */
    private static final Map<UUID, Double> PENDING_CRIT_RATE = new ConcurrentHashMap<>();

    /** 给这只傀儡的<b>下一击</b>加一笔暴击率。 */
    public static void grantBonusCritRate(AbstractGolemEntity<?, ?> golem, double bonus) {
        PENDING_CRIT_RATE.merge(golem.getUUID(), bonus, Double::sum);
    }

    /** 取走并清空待消费的暴击率加成。 */
    private static double consumeBonusCritRate(AbstractGolemEntity<?, ?> golem) {
        Double v = PENDING_CRIT_RATE.remove(golem.getUUID());
        return v == null ? 0.0 : v;
    }

    /** 傀儡被移除时清掉残留，别让这张表一直长。 */
    @SubscribeEvent
    public static void onGolemRemoved(net.minecraftforge.event.entity.living.LivingDeathEvent event) {
        if (event.getEntity() instanceof AbstractGolemEntity<?, ?> golem) {
            PENDING_CRIT_RATE.remove(golem.getUUID());
        }
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
