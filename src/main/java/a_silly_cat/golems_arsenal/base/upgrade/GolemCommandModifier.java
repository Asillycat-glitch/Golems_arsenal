package a_silly_cat.golems_arsenal.base.upgrade;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.base.command.CommandDamageTypes;
import a_silly_cat.golems_arsenal.base.event.GolemCritHandler;
import a_silly_cat.golems_arsenal.init.CommandEffects;
import dev.xkmc.l2library.init.events.GeneralEventHandler;
import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 指令升级（Command）：傀儡进入战斗时，从九个「指令效果」里获得若干个（带时长）。
 *
 * <h2>它做什么</h2>
 * 攻击命中时，若不在冷却中：
 * <ol>
 *   <li>从九个效果的池子里<b>抽 N 个</b>（N = 升级等级）；</li>
 *   <li>每个抽中的效果<b>各自掷一次自己的授予概率</b>，没过的这一轮不给；</li>
 *   <li>过了的挂到实体身上，带各自的时长 —— 增强傀儡的挂傀儡，削弱目标的挂目标。</li>
 * </ol>
 * 效果到期就没了，<b>不会自动续期</b>：要重新获得必须再命中一次，所以它天然是
 * 「打着打着进入状态」而不是「永久常驻」。
 *
 * <h2>这套效果从哪来</h2>
 * 原本是已删除的「军械手套」九个武器格的效果。手套那套设计（把武器换到主手上、
 * 靠 NBT 标记找回手套）从根上是单向的，已经整体删除；效果本身提炼成了这个升级。
 *
 * <h2>形态变了一次，为什么</h2>
 * 第一版做成「命中时立刻结算一次伤害/减益」。那是错的：那样每个效果都是一次性的一击，
 * 既看不出"获得了什么"，也没法做"同时拥有几个"。现在全部改成<b>带时长的增益/减益</b>，
 * 于是「获得 N 个效果」这件事在玩家面板上是可见的。
 *
 * <h2>增伤 / 穿透抗性 / 追击为什么不是纯属性</h2>
 * 属性盘面上<b>没有</b>「造成伤害乘区」这一项，所以这三个效果靠
 * {@link CommandEffects#AMPLIFY} / {@link CommandEffects#PIERCE} / {@link CommandEffects#EXTRA_HIT}
 * 三个标记，由本类在命中时读取后处理。其余六个都是标准属性修饰符。
 */
public class GolemCommandModifier extends GolemModifier {

    /** 池子大小，同时也是等级上限。 */
    public static final int POOL_SIZE = CommandEffects.POOL_SIZE;

    /**
     * 实际生效的等级上限。
     *
     * <h2>为什么需要它（踩过的坑）</h2>
     * 构造器拿 {@link #POOL_SIZE}（= 9）当 maxLevel，因为修饰符的 maxLevel 是 final 的、
     * 读不了配置。而本家会把同一个升级装多份的等级<b>累加</b>（装三个 I 级就是 3 级），
     * 所以「被封顶在 9」这件事不能替代 {@code max_level}：
     * <b>配置里写 3、实际却能叠到 5</b>，那个配置项就成了摆设。
     * <p>
     * 所以真正封顶在这里做，抽签和 tooltip 都走它。
     */
    private static int effectiveLevel(int level) {
        int cap = Math.max(1, Math.min(POOL_SIZE, Config.COMMAND_MAX_LEVEL.get()));
        return Math.max(1, Math.min(level, cap));
    }

    private static final String COOLDOWN_KEY = "ga_command_cd";

    public GolemCommandModifier() {
        super(StatFilterType.ATTACK, POOL_SIZE);
    }

    @Override
    public Component getTooltip(int level) {
        return Component.translatable("upgrade.golems_arsenal.command")
                .withStyle(ChatFormatting.GOLD);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        List<MutableComponent> list = new ArrayList<>();
        list.add(Component.translatable("upgrade.golems_arsenal.command.desc",
                effectiveLevel(level),
                String.format("%.1f", Config.COMMAND_COOLDOWN.get() / 20.0))
                .withStyle(ChatFormatting.GREEN));
        list.add(Component.translatable("upgrade.golems_arsenal.command.slots")
                .withStyle(ChatFormatting.AQUA));
        return list;
    }

    // ------------------------------------------------------------------ 触发

    @Override
    public void onHurtTarget(AbstractGolemEntity<?, ?> golem, LivingHurtEvent event, int level) {
        if (golem.level().isClientSide) {
            return;
        }
        // 本模组自己的技能伤害不再触发：否则追击段会自己触发自己。
        if (GolemCritHandler.isOwnSkillDamage(event.getSource().getMsgId())) {
            return;
        }
        if (event.getSource().is(DamageTypeTags.IS_PROJECTILE)) {
            return;
        }
        if (!(golem.level() instanceof ServerLevel server)) {
            return;
        }
        // 内置冷却：效果是带时长的，所以这里是「多久能重新抽一次」，不是「多久能打一次」。
        long now = server.getGameTime();
        long ready = golem.getPersistentData().getLong(COOLDOWN_KEY);
        if (now < ready) {
            applyMarkedEffects(golem, event.getEntity(), event, server, now);
            return;
        }
        golem.getPersistentData().putLong(COOLDOWN_KEY, now + Config.COMMAND_COOLDOWN.get());
        grant(golem, event.getEntity(), level);
        applyMarkedEffects(golem, event.getEntity(), event, server, now);
    }

    /**
     * 从池子里抽 {@code level} 个效果并全部授予。
     * <p>
     * 「抽 N 个」是不放回抽样：同一轮不会把同一个效果抽中两次，所以抽 3 个就一定拿到
     * 3 个<b>各不相同</b>的效果。
     *
     * <h2>为什么没有「每个效果各自判定」这一步</h2>
     * 第一版给每个效果加了一条授予概率。叠在抽取之上之后，抽 3 个实际平均只拿到 1.5 个
     * （默认 50%），玩家看到的是「我 3 级怎么只生效 1 个」——
     * 那和「等级 = 能同时拥有几个」这条设计陈述直接矛盾。
     * 所以现在<b>抽中即授予</b>：随机性体现在「是哪几个」，而不体现在「给不给」。
     */
    private static int grant(AbstractGolemEntity<?, ?> golem, LivingEntity target, int level) {
        int count = effectiveLevel(level);
        int granted = 0;
        List<Integer> pool = new ArrayList<>(POOL_SIZE);
        for (int i = 1; i <= POOL_SIZE; i++) {
            pool.add(i);
        }
        for (int n = 0; n < count && !pool.isEmpty(); n++) {
            int index = pool.remove(golem.getRandom().nextInt(pool.size()));
            MobEffect effect = CommandEffects.byIndex(index);
            // 时长跟等级走：等级越高，获得的效果维持得越久。
            int duration = CommandEffects.durationTicks(index, count);
            if (effect == null || duration <= 0) {
                continue;
            }
            // 减益挂目标（目标可能已经死了，那就没意义）；增益挂傀儡。
            LivingEntity receiver = CommandEffects.targetsEnemy(index) ? target : golem;
            if (receiver == null || !receiver.isAlive()) {
                continue;
            }
            receiver.addEffect(new MobEffectInstance(effect, duration, 0, false, true));
            granted++;
        }
        return granted;
    }

    // ------------------------------------------------------------------ 三个靠标记的效果

    /**
     * 处理「没有对应属性」的那三个效果。
     * <p>
     * 它们在傀儡身上表现为一个带时长的 {@code MobEffect}，本方法每此命中检查一次，
     * 有就生效。这样做的好处是「获得了什么」在玩家面板上完全可见，
     * 而不是一个藏在代码里的隐含状态。
     */
    private static void applyMarkedEffects(AbstractGolemEntity<?, ?> golem, LivingEntity target,
                                           LivingHurtEvent event, ServerLevel server, long now) {
        if (target == null || !target.isAlive()) {
            return;
        }
        float amount = event.getAmount();

        // 增伤：直接乘在这一次的伤害上。
        if (golem.hasEffect(CommandEffects.AMPLIFY.get())) {
            event.setAmount(amount * (1.0F + (float) CommandEffects.STR_AMPLIFY_BONUS));
        }

        // 穿透抗性：这一步不动伤害数值 —— 它给这一击的伤害类型开 l2 的 BYPASS 通道，
        // 让目标的「受到伤害」乘区被跳过。真正的开关在 modifySource 里（见下）。
        // 这里只负责追击段。
        if (golem.hasEffect(CommandEffects.EXTRA_HIT.get())) {
            scheduleExtraHit(golem, target, valueAfter(event, amount), server, now);
        }
    }

    /** 取增伤之后、追击应该按比例跟随的那一份伤害。 */
    private static float valueAfter(LivingHurtEvent event, float before) {
        return event.getAmount();
    }

    /**
     * 穿透抗性：给这一击的伤害类型开 {@code BYPASS_MAGIC}（l2 管线的「无视减伤乘区」通道）。
     * <p>
     * 走 {@code CreateSourceEvent} 而不是自己改数值，是因为那是 l2 官方的状态通道：
     * 目标那一层的减伤会真的被跳过，而不是我在事件里硬乘一个数。
     */
    @Override
    public void modifySource(AbstractGolemEntity<?, ?> golem,
                             dev.xkmc.l2damagetracker.contents.attack.CreateSourceEvent event,
                             int level) {
        if (!golem.hasEffect(CommandEffects.PIERCE.get())) {
            return;
        }
        var result = event.getResult();
        if (result != null && result.validState(
                dev.xkmc.l2damagetracker.contents.damage.DefaultDamageState.BYPASS_MAGIC)) {
            event.enable(dev.xkmc.l2damagetracker.contents.damage.DefaultDamageState.BYPASS_MAGIC);
        }
    }

    /**
     * 追击：延迟 1 tick 的追加伤害段。
     * <p>
     * 为什么必须延迟：这一击本身已经在 {@code LivingHurtEvent} 里，同 tick 再调
     * {@code target.hurt()} 会因为目标正处于无敌帧而被原版直接丢掉。
     * 推迟一 tick 让 {@code invulnerableTime} 先落地。
     * <p>
     * 伤害类型用 {@code command_extra}，它在数据包里进了 {@code bypasses_cooldown}，
     * 所以这一段的语义是「无视无敌帧的追击」，而不是「无视抗性的魔法」——
     * 上一版把两者混在一个效果里，结果两个易伤都对它无效。
     */
    private static void scheduleExtraHit(LivingEntity attacker, LivingEntity target,
                                         float amount, ServerLevel server, long now) {
        float bonus = amount * (float) CommandEffects.STR_EXTRA_RATIO;
        if (bonus <= 0.0F) {
            return;
        }
        // "只跑一跳"的保证：这个任务是 schedulePersistent 注册的，那是<b>每 tick 都会调用</b>
        // 直到返回 true 才移除的调度器。正常情况下时间一到就返回 true，一次就结束。
        // 但如果哪天有别的东西让 getGameTime 的判断失效，它会变成每 tick 打一次伤害 ——
        // 实测过一次（追击段没进自伤豁免名单、自己触发自己），表现是"傀儡收回了还在无限跳伤害数字"。
        // 所以这里加一道硬护栏：跑第二跳就直接退出，宁可少打一段也不要无限打。
        boolean[] fired = {false};
        GeneralEventHandler.schedulePersistent(() -> {
            if (server.getGameTime() < now + 1) {
                return false;
            }
            if (fired[0]) {
                return true;
            }
            fired[0] = true;
            if (target.isAlive() && !target.isRemoved()) {
                var source = CommandDamageTypes.extra(server, attacker);
                if (source != null) {
                    target.hurt(source, bonus);
                }
            }
            return true;
        });
    }
}
