package a_silly_cat.golems_arsenal.base.upgrade;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.base.event.GolemCritHandler;
import dev.xkmc.l2damagetracker.contents.attack.AttackCache;
import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 磨砺（Attrition）：<b>持续作战的滚雪球</b>——打不动就一路加码，直到这一击打得比自己的攻击力还狠。
 *
 * <h2>效果</h2>
 * 对同一个敌人，只要它<b>实际吃到的伤害低于傀儡的攻击力</b>，傀儡下一次打它的伤害就往上叠一层；
 * 叠到"实际伤害 ≥ 攻击力"为止就停（<b>没有上限常数</b>，上限由这个条件自然给出）。
 * 换目标不清档：<b>每名敌人各记各的</b>，所以同时打多只怪时每只都在各自累积。
 *
 * <h2>为什么要这么算（防"越叠越打不到"的死循环）</h2>
 * 加成是"加进攻击力、照常吃减伤"的，于是 {@code getPreDamageOriginal()} 与
 * {@code getDamageDealt()} 会一起变大，直接拿两者比就会永远达不到目标、无限叠下去。
 * 这里用一个关键事实绕开它：{@code getPreDamageOriginal()} 是<b>攻击阶段</b>记下的值
 * （{@code AttackCache.pushAttackPre} 里 {@code damage_pre = event.getAmount()}），
 * 而我们的加成是在<b>受击阶段</b>才加进去的 —— 所以它天生就是"不含本次加成的攻击力"。
 * 于是先把本次加成从"实际受伤"里除掉，还原出目标真正的减伤系数：
 * <pre>
 *   kept = taken / (base + applied)        // 0~1，纯粹是目标的减伤结果
 *   if (taken &lt; base) accum += (base - taken) × step      // ★ 收敛判据用"实际打进多少"
 * </pre>
 * 注意判据里的 {@code taken} 是<b>含加成</b>的实际伤害：加成把 {@code taken} 顶到
 * {@code base} 那一刻，累积自然停止（所需累积值 = {@code base × (1/kept - 1)}）。
 * 反过来若拿"没有加成时会打多少"（{@code base × kept}）当判据，只要目标有减伤就永远
 * 满足条件、每一下都补，伤害会无限上涨 —— 这个坑已经踩过并修掉了。
 *
 * <h2>破适应</h2>
 * 「适应」这类防御压的是 {@code kept}（乘区），而本机制衡量的正是 {@code kept}：
 * 它被压得越低，"还差多少"就越大，累积得越多，<b>靠一路加码硬顶到攻击力水平</b> ——
 * 不需要给这部分伤害挂任何 {@code bypasses_*} 后门，也不会被适应锁死。
 *
 * <h2>记录（挂在敌人身上）</h2>
 * {@code 目标 persistentData -> golems_arsenal_attrition -> <傀儡UUID> -> {hits, accum, last, ...}}。
 * 记录<b>独立计时</b>：{@code attrition_expiry_ticks}（默认 20 秒）内没再打这名敌人才清掉；
 * 中途去打别的怪不影响它。超过时间窗的记录会在下次交手时先作废再重新累积。
 */
public class GolemAttritionModifier extends GolemModifier {
    public static final int MAX_LEVEL = 2;

    private static final String ROOT_KEY = "golems_arsenal_attrition";
    private static final String HITS = "hits";
    private static final String ACCUM = "accum";
    private static final String LAST = "last";
    private static final String LAST_BASE = "last_base";
    private static final String LAST_TAKEN = "last_taken";

    public GolemAttritionModifier() {
        super(StatFilterType.ATTACK, MAX_LEVEL);
    }

    @Override
    public Component getTooltip(int level) {
        return Component.translatable("upgrade.golems_arsenal.attrition")
                .withStyle(ChatFormatting.DARK_AQUA);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        List<MutableComponent> list = new ArrayList<>();
        list.add(Component.translatable("upgrade.golems_arsenal.attrition.desc",
                Math.round(step(level) * 100)).withStyle(ChatFormatting.GREEN));
        list.add(Component.translatable("upgrade.golems_arsenal.attrition.desc_expiry",
                Math.round(Config.ATTRITION_EXPIRY_TICKS.get() / 20.0)).withStyle(ChatFormatting.GRAY));
        return list;
    }

    public static int levelOf(AbstractGolemEntity<?, ?> golem) {
        return golem.getModifiers().getOrDefault(GolemUpgrades.ATTRITION.get(), 0);
    }

    public static boolean hasUpgrade(AbstractGolemEntity<?, ?> golem) {
        return levelOf(golem) > 0;
    }

    /** 每次追补的比例（等级 1 / 2）。没有上限常数 —— 收敛条件就是"实际伤害追平攻击力"。 */
    public static double step(int level) {
        return level >= 2 ? Config.ATTRITION_STEP_LV2.get() : Config.ATTRITION_STEP_LV1.get();
    }

    /** 把累积值乘进这一击（受击阶段 = "加进攻击力"，因此照常吃护甲/抗性/限伤）。 */
    @Override
    public void onHurtTarget(AbstractGolemEntity<?, ?> golem, LivingHurtEvent event, int level) {
        LivingEntity target = event.getEntity();
        if (golem.level().isClientSide
                || event.getSource().is(DamageTypeTags.IS_PROJECTILE)
                || GolemCritHandler.isOwnSkillDamage(event.getSource().getMsgId())
                || isFriendly(golem, target)) {
            return;
        }
        Record record = read(target, golem.getUUID(), golem.level().getGameTime());
        if (record == null || record.accum() <= 0.0D) {
            return;
        }
        event.setAmount((float) (event.getAmount() + record.accum()));
    }

    /**
     * 结算完成后记账：用"不含本次加成的攻击力"还原目标的减伤系数，再补上差口的一部分。
     * 差口为 0（已经追平）时不再累积 —— 这就是自然上限。
     */
    @Override
    public void finalizeHurtTarget(AttackCache cache, AbstractGolemEntity<?, ?> golem, int level) {
        LivingEntity target = cache.getAttackTarget();
        if (target == null || target.level().isClientSide || isFriendly(golem, target)) {
            return;
        }
        float base;
        float taken;
        try {
            base = cache.getPreDamageOriginal();
            taken = cache.getDamageDealt();
        } catch (RuntimeException e) {
            return;
        }
        if (!(base > 0.0F)) {
            return;
        }
        long now = golem.level().getGameTime();
        Record record = read(target, golem.getUUID(), now);
        double applied = record == null ? 0.0D : record.accum();
        int hits = (record == null ? 0 : record.hits()) + 1;
        // ★ 收敛判据必须用"这一击实际打进多少"（base + 我们的加成），而不是"没有加成时会打多少"。
        //   写成后者的话，只要目标有任何减伤（kept < 1）条件就永远成立 —— 每一下都补 0.4 倍差口，
        //   于是伤害无限往上叠（这正是"伤害一直在叠加"的成因）。
        double denominator = base + applied;
        double kept = denominator <= 0.0D ? 0.0D : Math.max(0.0D, Math.min(1.0D, taken / denominator));
        double accum = applied;
        if (taken < base) {
            // 还没追平攻击力：补上"还差多少"的一部分（差口按实际伤害算，step ≈ 0.4 时逐步逼近）。
            accum += (base - taken) * step(level);
        }
        write(target, golem.getUUID(), new Record(hits, accum, now, base, taken));
    }

    // ---------------------------------------------------------------- 记录存取

    /** 一名敌人身上、针对某台傀儡的记录快照。{@code accum} 是下一击要加的绝对伤害。 */
    public record Record(int hits, double accum, long last, float lastBase, float lastTaken) {
    }

    private static CompoundTag table(LivingEntity target, boolean create) {
        CompoundTag data = target.getPersistentData();
        if (!data.contains(ROOT_KEY)) {
            if (!create) {
                return null;
            }
            data.put(ROOT_KEY, new CompoundTag());
        }
        return data.getCompound(ROOT_KEY);
    }

    /** 读记录；超过时间窗（默认 20 秒没打它）就当作不存在（此时不清理，写回时会覆盖）。 */
    private static Record read(LivingEntity target, UUID attacker, long now) {
        CompoundTag table = table(target, false);
        if (table == null || !table.contains(attacker.toString())) {
            return null;
        }
        CompoundTag tag = table.getCompound(attacker.toString());
        if (now - tag.getLong(LAST) > Config.ATTRITION_EXPIRY_TICKS.get()) {
            return null;
        }
        return new Record(tag.getInt(HITS), tag.getDouble(ACCUM), tag.getLong(LAST),
                tag.getFloat(LAST_BASE), tag.getFloat(LAST_TAKEN));
    }

    private static void write(LivingEntity target, UUID attacker, Record record) {
        CompoundTag table = table(target, true);
        CompoundTag tag = new CompoundTag();
        tag.putInt(HITS, record.hits());
        tag.putDouble(ACCUM, record.accum());
        tag.putLong(LAST, record.last());
        tag.putFloat(LAST_BASE, record.lastBase());
        tag.putFloat(LAST_TAKEN, record.lastTaken());
        table.put(attacker.toString(), tag);
    }

    /** 诊断用：{@code [hits, accum, lastGameTime, lastBase, lastTaken]}；没有有效记录时返回 null。 */
    public static double[] snapshot(LivingEntity target, UUID attacker, long now) {
        Record record = read(target, attacker, now);
        return record == null ? null : new double[]{
                record.hits(), record.accum(), record.last(), record.lastBase(), record.lastTaken()};
    }

    /** 不打自己人：玩家（含主人）、友军、同主傀儡。 */
    private static boolean isFriendly(AbstractGolemEntity<?, ?> golem, LivingEntity target) {
        if (target instanceof Player || target == golem) {
            return true;
        }
        if (target.isAlliedTo(golem) || golem.isAlliedTo(target)) {
            return true;
        }
        UUID master = golem.getOwnerUUID();
        if (master == null) {
            return false;
        }
        if (master.equals(target.getUUID())) {
            return true;
        }
        return target instanceof OwnableEntity other && master.equals(other.getOwnerUUID());
    }
}
