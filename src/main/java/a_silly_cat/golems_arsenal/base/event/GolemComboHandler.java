package a_silly_cat.golems_arsenal.base.event;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.base.upgrade.GolemFlagModifier;
import a_silly_cat.golems_arsenal.base.upgrade.GolemUpgrades;
import a_silly_cat.golems_arsenal.client.GolemComboStages;
import a_silly_cat.golems_arsenal.init.ModNetwork;
import a_silly_cat.golems_arsenal.network.ClientboundComboPacket;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

/**
 * 三连击的服务端部分。
 * <p>
 * <b>一次近战攻击 → 整段三连击</b>：攻击只负责"起手"，之后三段由这里按 tick 依次推进
 * （每段之间隔 {@link #LINK_TICKS}），每段自己带一次范围判定，并把"谁、第几段"发给客户端去演。
 * <p>
 * <b>攻击速度惩罚也在这里挂</b>：本家 {@code GolemMeleeGoal#getMeleeInterval()} 读的是
 * {@code Attributes.ATTACK_SPEED}，间隔 ≈ {@code ceil(20 / 攻速)}。之前把惩罚写在词条的
 * {@code onAiStep} 里，实机没有生效（那个钩子没被稳定调到），所以改到每 tick 的
 * {@link #onGolemTick} 里幂等地挂/摘 —— 这样读档、换装、卸下升级都能自愈。
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID)
public final class GolemComboHandler {

    /**
     * <b>开段间隔</b>（tick）：从起手那一刻起，每隔这么多 tick 开下一段 ——
     * 不等上一段动画播完，所以相邻两段<b>会重叠</b>（场上同时出现两三把刀）。
     * 调大 = 三下摊得更开；调小 = 更密集。
     */
    public static final int LINK_TICKS = 5;

    /**
     * 要把傀儡的攻速压到的目标值。
     * <p>
     * ⚠️ 本家的近战间隔公式是 {@code ceil(20 / min(1.0, 攻速))} ——
     * <b>攻速 ≥ 1.0 时 min 取 1.0，间隔恒为 20 tick</b>，所以"降低攻速"必须把它压到 <b>1.0 以下</b>
     * 才有效（这就是之前按百分比挂乘区完全没反应的原因）。
     * {@code 0.6 → 34 tick}，默认是 20 tick。
     */
    public static final double TARGET_ATTACK_SPEED = 0.6;

    /** 本家公式下的近战间隔（tick），给 tooltip 用。 */
    public static int attackIntervalTicks() {
        return (int) Math.ceil(20.0 / Math.min(1.0, TARGET_ATTACK_SPEED));
    }

    private static final UUID SPEED_PENALTY_ID =
            UUID.nameUUIDFromBytes("golems_arsenal:combo_attack_speed".getBytes());

    /** 每段判定的伤害 = 傀儡攻击力 × 该系数。 */
    private static final double STAGE_DAMAGE_RATIO = 0.5;

    /**
     * 判定半径（格，从傀儡向外）。<b>调大就是打得更远</b>：连击那把刀是放大画的（全局 ×2.5 再乘每段
     * 1.1~1.5），所以视觉上的刀锋能扫到 3~4 格，这里给 4.5 是为了让"看着扫到了"和"打到了"对得上。
     */
    private static final double HIT_RADIUS = 4.5;

    /**
     * 正前方扇形：目标方向与傀儡朝向的点积下限。<b>越小越宽</b>：
     * {@code 0.2 ≈ 前后 ±78°}、{@code 0 = ±90°}、{@code -0.5 ≈ ±120°}。
     */
    private static final double HIT_ARC_DOT = 0.2;

    /** 连击状态：当前段号；{@code -1} = 已起手、等这一 tick 开第一段。 */
    private static final String STAGE_KEY = "GolemsArsenalComboStage";
    /** 当前段的开始时刻（gameTime）。 */
    private static final String STAGE_TIME_KEY = "GolemsArsenalComboStageTime";

    private GolemComboHandler() {
    }

    /** 傀儡发起了一次近战攻击：起手整段连击（已经有一段在演就忽略）。 */
    public static void onGolemAttack(AbstractGolemEntity<?, ?> golem) {
        if (golem.level().isClientSide || !golem.isAlive() || golem.isInRangedMode()) {
            return;
        }
        if (golem.getMainHandItem().isEmpty()) {
            return; // 空手没有可挥的模型，整段演出不成立
        }
        CompoundTag tag = golem.getPersistentData();
        if (tag.contains(STAGE_KEY)) {
            return; // 上一套还没演完
        }
        tag.putInt(STAGE_KEY, -1);
    }

    /** 每 tick：维护攻速惩罚 + 推进连击段位。 */
    @SubscribeEvent
    public static void onGolemTick(LivingEvent.LivingTickEvent event) {
        if (event.getEntity().level().isClientSide
                || !(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        CompoundTag tag = golem.getPersistentData();
        boolean installed = GolemFlagModifier.hasUpgrade(golem, GolemUpgrades.COMBO.get());
        maintainSpeedPenalty(golem, installed);
        if (!installed || !tag.contains(STAGE_KEY)) {
            return;
        }
        advance(golem, tag, golem.level().getGameTime());
    }

    /**
     * 幂等地把攻速压到 {@link #TARGET_ATTACK_SPEED}。
     * <p>
     * 用 {@code ADDITION} 直接补差值（而不是乘区），因为本家那条公式对 ≥1.0 的值是"免疫"的，
     * 只有绝对落点才有意义；每 tick 顺带校正一次，免得装备/词条变动把落点顶回 1.0 以上。
     */
    private static void maintainSpeedPenalty(AbstractGolemEntity<?, ?> golem, boolean installed) {
        AttributeInstance speed = golem.getAttribute(Attributes.ATTACK_SPEED);
        if (speed == null) {
            return;
        }
        AttributeModifier existing = speed.getModifier(SPEED_PENALTY_ID);
        if (!installed) {
            if (existing != null) {
                speed.removeModifier(SPEED_PENALTY_ID);
            }
            return;
        }
        // 去掉我们自己那一条之后的攻速
        double without = speed.getValue() - (existing == null ? 0.0 : existing.getAmount());
        if (existing != null && Math.abs(without + existing.getAmount() - TARGET_ATTACK_SPEED) <= 0.01) {
            return; // 已经落在目标上
        }
        if (existing != null) {
            speed.removeModifier(SPEED_PENALTY_ID);
        }
        speed.addTransientModifier(new AttributeModifier(SPEED_PENALTY_ID,
                "golems_arsenal.combo_attack_speed", TARGET_ATTACK_SPEED - without,
                AttributeModifier.Operation.ADDITION));
    }

    /**
     * 推进连击：<b>从起手那一刻起按固定节奏排</b> —— 每 {@link #LINK_TICKS} tick 开下一段，
     * 不等上一段动画播完。所以相邻两段会重叠（场上同时出现两三把刀），衔接更连贯；
     * 最后一段开完就收手（那一段的动画由客户端自己播完）。
     */
    private static void advance(AbstractGolemEntity<?, ?> golem, CompoundTag tag, long now) {
        int stage = tag.getInt(STAGE_KEY);
        if (stage < 0) {
            beginStage(golem, tag, now, 0);
            return;
        }
        if (now < tag.getLong(STAGE_TIME_KEY) + LINK_TICKS) {
            return;
        }
        int next = stage + 1;
        if (next < GolemComboStages.stageCount()) {
            beginStage(golem, tag, now, next);
        } else {
            tag.remove(STAGE_KEY);
            tag.remove(STAGE_TIME_KEY);
        }
    }

    /** 开一段：记状态、通知客户端演这一段的动画、做这一段的判定。 */
    private static void beginStage(AbstractGolemEntity<?, ?> golem, CompoundTag tag, long now, int stage) {
        tag.putInt(STAGE_KEY, stage);
        tag.putLong(STAGE_TIME_KEY, now);
        ModNetwork.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> golem),
                new ClientboundComboPacket(golem.getId(), stage));
        hitArc(golem);
    }

    /**
     * 一段的判定：正前方扇形内的敌人各吃一次<b>普通攻击</b>。
     * <p>
     * ⚠️ 这一次伤害紧跟在本家的近战结算之后，目标身上还有受击无敌帧，
     * 所以必须先清 {@code invulnerableTime} 再出手（本仓库的处决/凤凰小火都是这么做的），
     * 否则这一段的伤害会被自己的前一击吞掉。
     * <p>
     * 出手方式走 {@link GolemMeleeHelper#normalAttack}：临时把攻击力乘上 {@code STAGE_DAMAGE_RATIO}
     * 后调用 {@code golem.doHurtTarget(target)} —— 也就是<b>和本家近战同一条路</b>。
     * 这样三段动画伤害都会照常触发"普通攻击才触发"的一切：附魔（锋利/火焰附加/击退）、
     * 物品 on-hit 与耐久、傀儡自己的 {@code doHurtTarget} 覆写、以及所有挂在
     * {@code LivingHurtEvent}/l2 管线上的"命中时"升级（莫厄余烬、磨砺、炎拳…）。
     */
    private static void hitArc(AbstractGolemEntity<?, ?> golem) {
        if (golem.getAttributeValue(Attributes.ATTACK_DAMAGE) <= 0.0) {
            return;
        }
        Vec3 look = golem.getLookAngle();
        Vec3 forward = new Vec3(look.x, 0.0, look.z);
        forward = forward.lengthSqr() < 1.0E-6 ? new Vec3(0.0, 0.0, 1.0) : forward.normalize();

        AABB box = golem.getBoundingBox().inflate(HIT_RADIUS)
                .expandTowards(forward.x * HIT_RADIUS, 0.0, forward.z * HIT_RADIUS);
        List<LivingEntity> targets = golem.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive() && e != golem && !e.isAlliedTo(golem) && !golem.isAlliedTo(e));
        for (LivingEntity target : targets) {
            Vec3 flat = new Vec3(target.getX() - golem.getX(), 0.0, target.getZ() - golem.getZ());
            if (flat.lengthSqr() > 1.0E-6 && flat.normalize().dot(forward) < HIT_ARC_DOT) {
                continue; // 不在正前方扇形里
            }
            target.invulnerableTime = 0;
            GolemMeleeHelper.normalAttack(golem, target, STAGE_DAMAGE_RATIO);
        }
    }
}
