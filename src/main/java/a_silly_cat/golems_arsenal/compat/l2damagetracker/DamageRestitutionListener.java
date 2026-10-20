package a_silly_cat.golems_arsenal.compat.l2damagetracker;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.base.event.KeySwordEventHandler;
import a_silly_cat.golems_arsenal.init.ModAttributes;
import dev.xkmc.l2damagetracker.contents.attack.AttackCache;
import dev.xkmc.l2damagetracker.contents.attack.AttackEventHandler;
import dev.xkmc.l2damagetracker.contents.attack.AttackListener;
import dev.xkmc.l2damagetracker.contents.attack.DamageModifier;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 伤害返还（damage restitution）：把已经结算完的伤害里"被减免掉的那一部分"，按比例还给这一击。
 *
 * <h2>数学</h2>
 * 设 {@code base} = 攻击方乘区之后、护甲之前的值（{@code cache.getPreDamage()}），
 * {@code x} = 全部目标侧减伤之后的值，则 {@code ratio = x / base} 就是"保留比例"：
 * <pre>
 * 返还后 = base × [1 − (1 − ratio) × (1 − p)]
 * </pre>
 * 等价于 {@code 最终 = 普通伤害 + 被减掉的伤害 × p}：{@code p = 1} 时完全无视减伤回到 {@code base}，
 * {@code p = 0} 时不做事，且永远不会超过 {@code base}（不像"保底穿透"会超射）。
 *
 * <h2>顺带就是保底伤害</h2>
 * 因为 {@code kept = 1 − (1 − ratio)(1 − p)} 随 {@code ratio} 单调递增、且 <b>恒 ≥ p</b>，
 * 所以 {@code ratio = 0}（目标把伤害完全归零）时它自然落到 {@code base × p} ——
 * <b>{@code p} 同时就是"这一击至少保住多少原始伤害"的保底比例</b>，不需要额外常数。
 * 换句话说：<b>在那把武器手里，一次攻击无论被削到什么程度，至少会打出 {@code base × p}</b>。
 * <p>
 * 它<b>不会</b>超过 {@code base}，所以武器/装备/攻击力的差别仍然完全成立 —— 这是它和
 * "绝对值保底"（每击固定 N 点）或"按目标最大生命保底"（每击 f 比例最大生命）最大的区别：
 * 后两者会让武器选择失去意义。
 *
 * <h2>它仍然解决不了的两件事</h2>
 * <ol>
 *   <li><b>取消 {@code LivingAttackEvent} 的免疫</b>（魔法免疫 / 火焰吸收）：伤害根本没进管线，
 *       没有 {@code x} 也没有 {@code base}，保底无从谈起。</li>
 *   <li><b>{@code GuardedEntity} 的血量地板</b>（{@code dynamic_reduction}）：它吃的是
 *       "已结算完但没落进血量"的那部分，保底把 {@code x} 抬得再高也一样被吃。
 *       真正能压住地板的是 {@code bypasses_invulnerability}（它会写 {@code antiHealDisableTimestamp}）。</li>
 * </ol>
 *
 * <h2>为什么写在 onDamage 而不是 onDamageFinalized</h2>
 * {@code AttackCache.pushDamagePre} 的最后一句是 {@code event.setAmount(damage)}，而
 * {@code onDamageFinalized} 是在它内部的 {@code run()} 里被调用的 —— 在那里写 {@code setAmount}
 * 会被下一句立刻覆盖；想改累加器又会撞上 {@code DamageAccumulator.addHurtModifier} 的
 * {@code if (frozen) throw new IllegalStateException("modify damage only on event.")}。
 * 只有在 {@code onDamage}（collect 阶段）注册一个 {@link DamageModifier} 才写得进去。
 *
 * <h2>生效范围（重要）</h2>
 * 它作用在 {@code base → x} 这个窗口里，所以：
 * <ul>
 *   <li><b>有效</b>：护甲、保护附魔、抗性提升、{@code l2damagetracker:damage_reduction}、
 *       {@code jerotes_village_golems:meror_defense}、以及任何用 {@code DamageModifier} 或
 *       {@code setAmount} 在这个窗口里写的减伤。</li>
 *   <li><b>无效</b>：取消 {@code LivingAttackEvent} 的免疫（魔法免疫 / 火焰吸收）、
 *       在 {@code actuallyHurt} 之前就 return 的无敌帧、吸收（在 {@code LivingDamageEvent} 之后）、
 *       以及 {@code GuardedEntity} 的血量地板（{@code dynamic_reduction}）——
 *       这些要么没进管线（没有 {@code x} 可还），要么在窗口之外。
 *       免疫那一半只能靠 tag 或语义口，无敌帧那一半只能靠 {@code bypasses_cooldown}。</li>
 * </ul>
 *
 * <h2>为什么是硬编码的</h2>
 * 这个效果会同时穿过护甲、附魔、抗性和一切 {@code DamageModifier} 减伤，属于"全局性"的强度，
 * 所以比例<b>刻意不做成配置项</b>：数值只写在这个文件里，改它必须改代码并重新编译。
 * 这样它不会变成玩家手里一个随手拉满的旋钮，调整也必须走代码评审。
 *
 * <h2>两道护栏</h2>
 * <ol>
 *   <li>{@code p} 再 clamp 一次到 [0,1]：曲线算坏了也不会出现"越返还越疼"。</li>
 *   <li>{@code x >= base} 时原样返回：这一击本来就没有净减伤（甚至被放大），
 *       照比值反推会把暴击、武器加成、其他增伤一起砍掉。</li>
 * </ol>
 */
public final class DamageRestitutionListener implements AttackListener {

    // ---------------------------------------------------------------- 数值来源

    /**
     * 返还比例的<b>硬上限</b>。数值来自两处相加：数据包给这把武器注册的值
     * （{@link RestitutionData}，{@code data/<ns>/golems_arsenal_restitution/*.json}）
     * 加上傀儡自身的 {@code golems_arsenal:end_restitution} 属性（升级件 / 材质 / 其它 mod 都能给），
     * 合计之后再夹到这个上限 —— 所以"多个 END 层返还来源"永远不可能叠到 1.0（等于无视所有减伤）。
     */
    private static final double END_RESTITUTION_CAP = 0.8D;

    /**
     * 这次攻击是否走返还管线：<b>只有傀儡走</b>。
     * <p>
     * 玩家原本也不走，理由是"过于霸道"——玩家会拿它去打 boss 和 PVP，
     * 而傀儡的强度本来就被 AI、攻速和目标选择限制住了。
     * <p>
     * 曾经为已删除的「军械手套」第 8 格临时打开过玩家这一路（那个效果需要一个
     * "手上这把武器也吃 END 保底"的入口）。现在那个效果是指令升级的
     * {@code command_end_floor}，它挂在<b>傀儡</b>身上、提升傀儡自己的
     * {@code end_restitution}，所以玩家这一路不再需要，恢复成只给傀儡。
     */
    private static boolean appliesTo(LivingEntity attacker) {
        return attacker instanceof AbstractGolemEntity<?, ?>;
    }

    // ---------------------------------------------------------------- 注册

    /** 监听器注册号；只影响 collect 阶段的调用顺序，真正的"最后"由下面的 END_NONLINEAR 决定。 */
    private static final int PRIORITY = 3600;

    /**
     * END_NONLINEAR 是最后一档（Order 序号 7），档内再按这个数排序 —— 取大值，
     * 保证本模组的返还排在别人的 END_NONLINEAR 之后，读到的 {@code x} 就是终值。
     */
    private static final int ORDER_PRIORITY = 9000;

    /** 这次命中用的返还数据：{@code pre} 是返还前的终值，{@code percent} 是实际用的返还比例。 */
    public record RestitutionInfo(float pre, float percent) {
    }

    /** 交给 {@link DamageReductionProbe} 做对照；读到即清除。 */
    private static final Map<UUID, RestitutionInfo> PRE_RESTITUTION = new ConcurrentHashMap<>();

    public static void register() {
        AttackEventHandler.register(PRIORITY, new DamageRestitutionListener());
    }

    private DamageRestitutionListener() {
    }

    // ---------------------------------------------------------------- 主逻辑

    @Override
    public void onDamage(AttackCache cache, ItemStack weapon) {
        LivingEntity attacker = cache.getAttacker();
        LivingEntity target = cache.getAttackTarget();
        if (attacker == null || target == null || attacker == target) {
            return;
        }
        if (!appliesTo(attacker)) {
            return;
        }
        // 无条件清掉上一击的残留：否则主手不是返还武器时，探针会读到上一条命中留下的旧值。
        UUID id = target.getUUID();
        PRE_RESTITUTION.remove(id);

        double p = restitutionOf(attacker, target);
        if (!(p > 0.0)) {
            return;
        }
        final float pen = (float) Mth.clamp(p, 0.0, 1.0);

        DamageSource source = cache.getLivingDamageEvent() == null
                ? null : cache.getLivingDamageEvent().getSource();
        if (source != null && ("thorns".equals(source.getMsgId())
                || source.is(DamageTypeTags.IS_PROJECTILE))) {
            // 反伤与弹射物不算"这把武器打出的伤害"，不参与返还。
            // 注意：键刃的三段技能（火球/闪电/霜爆）走的是自定义伤害源，不受这条影响。
            return;
        }

        // getPreDamage() 依赖 hurt 阶段跑过；没跑过会抛 IllegalStateException。
        if (cache.getLivingHurtEvent() == null) {
            return;
        }
        float base;
        try {
            base = cache.getPreDamage();
        } catch (IllegalStateException e) {
            return;
        }
        if (!(base > 0.0F)) {
            return;
        }

        cache.addDealtModifier(DamageModifier.nonlinearFinal(ORDER_PRIORITY, x -> {
            PRE_RESTITUTION.put(id, new RestitutionInfo(x, pen));
            // 只增不减：这一击本来就没有净减伤（甚至被放大）就原样放行。
            if (x >= base) {
                return x;
            }
            // 保底：ratio = 0（被完全归零、甚至 <= 0）时公式自然给出 kept = pen，
            // 也就是"无论被削到什么程度，这一击至少保住 pen 比例的原始伤害"。
            // 因为 kept 随 ratio 单调递增且恒 >= pen，所以这条保底对 x > 0 永远不额外生效，
            // 它只负责把"原本被跳过的那一类（x <= 0）"拉回同一条曲线。
            float ratio = x > 0.0F ? x / base : 0.0F;
            float kept = 1.0F - (1.0F - ratio) * (1.0F - pen);
            return base * kept;
        }));
    }

    /**
     * 取走这次命中的返还数据，供诊断探针打印对照。没有记录时返回 {@code null}
     * （表示这一击没有走返还，例如主手不是这两把武器）。
     */
    public static RestitutionInfo takePreRestitution(LivingEntity target) {
        return PRE_RESTITUTION.remove(target.getUUID());
    }

    // ---------------------------------------------------------------- 数值来源

    /**
     * 本次返还比例 = 数据包里这把武器的值 + 傀儡的 {@code end_restitution} 属性，
     * 合计后夹到 {@link #END_RESTITUTION_CAP}。
     *
     * <p>数据包那份支持"随处决进度从 value 升到 max"（键刃用的就是这条曲线，见
     * {@code data/golems_arsenal/golems_arsenal_restitution/default.json}）；
     * 属性那份是给升级件 / 材质 / 其它 mod 用的加法入口。</p>
     */
    private static double restitutionOf(LivingEntity attacker, LivingEntity target) {
        double fromWeapon = RestitutionData.valueFor(attacker.getMainHandItem(), target);
        double fromAttribute = 0.0D;
        Attribute attribute = ModAttributes.END_RESTITUTION.get();
        if (attacker.getAttribute(attribute) != null) {
            fromAttribute = attacker.getAttributeValue(attribute);
        }
        // 「莫厄余烬」这类升级的返还走代码侧相加：mod 没有"升级被卸下"的回调，
        // 用属性修饰符会残留，所以升级自带的份额在这里现算，属性仍然是对外统一入口。
        double fromUpgrades = 0.0D;
        if (attacker instanceof AbstractGolemEntity<?, ?> golem) {
            int afterglow = a_silly_cat.golems_arsenal.base.upgrade.GolemMerorAfterglowModifier.levelOf(golem);
            if (afterglow > 0) {
                fromUpgrades += a_silly_cat.golems_arsenal.base.upgrade.GolemMerorAfterglowModifier
                        .restitutionBonus(afterglow);
            }
        }
        return Mth.clamp(fromWeapon + fromAttribute + fromUpgrades, 0.0D, END_RESTITUTION_CAP);
    }
}
