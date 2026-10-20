package a_silly_cat.golems_arsenal.base.event;

import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.UUID;

/**
 * 让"我们自己的额外一击"走<b>傀儡的普通攻击</b>那条路。
 *
 * <p>为什么需要它：直接 {@code target.hurt(mobAttack(golem), 伤害)} 只会派发伤害事件，
 * 而一次真正的普通攻击（本家近战走的是 {@code GolemMeleeGoal → golem.doHurtTarget()}）还会经过
 * 原版 {@code Mob.doHurtTarget} 与傀儡自己的覆写，通常包括：</p>
 * <ul>
 *   <li>附魔相关：锋利/亡灵杀手/节肢杀手的伤害加成、火焰附加、击退；</li>
 *   <li>物品侧：{@code ItemStack} 的命中效果（灾变焰金握柄、莱特兰材质武器等都从这里进）；</li>
 *   <li>傀儡自己的 {@code doHurtTarget} 覆写（人形/金属傀儡各有额外逻辑）；</li>
 *   <li>原版的 {@code setLastHurtMob} / 仇恨与受击记账。</li>
 * </ul>
 * <p>具体含哪些以原版与该 mod 的实现为准 —— 本工具的价值在于<b>走同一条路</b>：
 * 别人挂在这条路上的钩子从此都会触发，而不是由我们手工拼一遍。</p>
 *
 * <p>做法：把攻击力属性<b>临时</b>乘上 {@code ratio}（{@code MULTIPLY_TOTAL} 修饰符，固定 UUID），
 * 调一次 {@code doHurtTarget}，然后立刻摘掉修饰符。这样段伤倍率仍然由我们决定，
 * 但这一击在系统眼里与普攻完全同源。</p>
 *
 * <p>调用方负责"清无敌帧"这类前置处理（额外一击通常紧跟在本家近战之后）。</p>
 */
public final class GolemMeleeHelper {

    /** 固定 UUID：同一时刻只可能有一次段伤缩放，重复使用同一个 ID 还能防止残留。 */
    private static final UUID STAGE_SCALE_ID = UUID.fromString("6f1c1a2e-8f4b-4c1d-9a3e-2b7d5c8e1f40");

    /**
     * 以普通攻击的方式打这一下。
     *
     * @param ratio 攻击力倍率（1.0 = 与普攻同伤）
     * @return {@code doHurtTarget} 的返回值（false 表示这一击被目标挡下/免疫）
     */
    public static boolean normalAttack(AbstractGolemEntity<?, ?> golem, LivingEntity target, double ratio) {
        AttributeInstance attribute = golem.getAttribute(Attributes.ATTACK_DAMAGE);
        boolean scaled = false;
        if (attribute != null && Math.abs(ratio - 1.0D) > 1.0E-4D) {
            // 先摘一次：万一上一次调用在中途异常退出，也不会把倍率叠上去。
            attribute.removeModifier(STAGE_SCALE_ID);
            attribute.addTransientModifier(new AttributeModifier(STAGE_SCALE_ID,
                    "golems_arsenal_stage_scale", ratio - 1.0D,
                    AttributeModifier.Operation.MULTIPLY_TOTAL));
            scaled = true;
        }
        try {
            return golem.doHurtTarget(target);
        } finally {
            if (scaled) {
                attribute.removeModifier(STAGE_SCALE_ID);
            }
        }
    }

    private GolemMeleeHelper() {
    }
}
