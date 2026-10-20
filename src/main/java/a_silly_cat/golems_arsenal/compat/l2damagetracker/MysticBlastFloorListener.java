package a_silly_cat.golems_arsenal.compat.l2damagetracker;

import dev.xkmc.l2damagetracker.contents.attack.AttackCache;
import dev.xkmc.l2damagetracker.contents.attack.AttackEventHandler;
import dev.xkmc.l2damagetracker.contents.attack.AttackListener;
import dev.xkmc.l2damagetracker.contents.attack.DamageModifier;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 秘奥义「绝炎冲」5 连爆的<b>END 层保底</b>：每一爆至少打出 {@code base × 0.5}。
 *
 * <h2>为什么是 END 层（{@code nonlinearFinal}）</h2>
 * 原版 {@code LivingEntity.actuallyHurt} 的顺序是
 * {@code getDamageAfterMagicAbsorb → ForgeHooks.onLivingDamage → setHealth(getHealth() - f1)}，
 * 而 l2 的 {@code AttackCache.pushDamagePre} 把 {@code dealtDamage} 累加器的结果
 * {@code event.setAmount(damage)} 写回的正是中间那个事件 —— <b>下一句就是扣血</b>。
 * 所以 {@code END_NONLINEAR}（Order 序号最后一档）是"写回血量之前的最后一站"，保底写在这里才真的落得到血上。
 * <p>
 * <b>不能</b>写在 {@code postHurt} 或 {@code onDamageFinalized}：前者会被 {@code pushDamagePre} 的最后一句
 * 覆盖，后者撞 {@code DamageAccumulator} 的 {@code frozen} 检查（{@code modify damage only on event}）。
 *
 * <h2>为什么只认 {@code mystic_blast}</h2>
 * 5 连爆原本与落地爆共用 {@code phoenix_impact}，无法只给绝炎冲加保底，所以
 * {@code GolemPhoenixHandler.mysticBlast} 改用独立的 {@code golems_arsenal:mystic_blast}。
 * 上升段（{@code phoenix_fire}）与落地爆（{@code phoenix_impact}）<b>不吃</b>这条保底。
 *
 * <h2>为什么 priority 是 3600 而不是 9000+</h2>
 * "保底"与"限伤"本质是同一个写回点的优先级之争（见 {@code docs/BYPASS-DESIGN.md} §4.1、§6 第 8 条）：
 * {@code END_NONLINEAR} 是 NONLINEAR 类型，档内按 priority <b>从小到大</b>依次执行，后一个覆盖前一个。
 * 取 3600 表示<b>排在"每击上限"类之前</b>（潘多拉之咒 {@code CurseOfPrudence} 是 9000）——
 * 也就是说：保底负责把"被削到接近 0"的伤害拉回 50%，但<b>限伤仍然算数</b>，会把拉回来的一部分再摁回去。
 * 这是刻意的克制立场：不越过别人的限伤，更不去碰 12346 那种"绝对归零"（{@code RealityTrait}）。
 * <p>
 * 与本模组自己的 {@link DamageRestitutionListener}（同为 3600）<b>不冲突但不是同一条曲线</b>：
 * 两者都是 NONLINEAR，档内按 {@code AttackEventHandler.register} 的注册顺序排（先注册者小），
 * 而两把返还武器与凤凰天驱互斥、不会同时命中同一击，所以正常玩法下不会叠。
 * 真叠上时后跑的那个赢（返还读到的 {@code x} 已含保底，或反之）。
 *
 * <h2>它救不了的三件事（别把 0.5 当绝对量）</h2>
 * <ol>
 *   <li><b>没进管线的命中</b>：被 {@code isInvulnerableTo} 或别人的 {@code LivingAttackEvent} 取消挡下的那一击
 *       连 {@code hurt} 阶段都没跑，{@code getPreDamage()} 会抛 {@code IllegalStateException}，
 *       这里直接 return —— <b>保底对"第 1 层免疫"无效</b>，免疫只能靠 tag（见 is_fire.json 的空表）
 *       或 {@code bypasses_invulnerability}（本模组刻意不用）。</li>
 *   <li><b>无敌帧</b>：{@code hurt()} 早退就没有事件，同样没得保。5 连爆在代码里已经清过
 *       {@code invulnerableTime}（{@code GolemPhoenixHandler.mysticBlast}），所以这一条对绝炎冲不构成实际问题。</li>
 *   <li><b>血量地板与外层钳制</b>：{@code GuardedEntity} 的
 *       {@code setGuardedDataImpl(..., force=false)} 基线硬钳跑在 {@code LivingDamageEvent} <b>之后</b>，
 *       使徒那种 {@code Math.min(onLivingDamage, 20)} 更是包在整个伤害事件外面。
 *       所以这条保底是<b>"管线内的 0.5"</b>，不等于"血条必掉 0.5"。</li>
 * </ol>
 * 另外它也<b>不会超过 base</b>：{@code x >= floor} 时原样放行，"伤害本来就够高"的场合一个字节都不改。
 */
public final class MysticBlastFloorListener implements AttackListener {

    // ---------------------------------------------------------------- 硬编码数值

    /**
     * 绝炎冲每一爆的保底比例：{@code 攻击力 × 1.5 × 加成 × 6 / 5} 的 50%。
     * <p>
     * 与 {@link DamageRestitutionListener} 同一哲学：数值写在代码里、<b>不做配置项</b>，
     * 改它必须改代码并重新编译，免得变成玩家手里一个随手拉满的旋钮。
     * （参考量级：攻击力 30 的傀儡一击 base ≈ 54，保底 ≈ 27 × 5 爆。）
     */
    private static final float FLOOR_PERCENT = 0.5F;

    /**
     * 监听器注册号。只影响 collect 阶段的调用顺序；真正决定"在限伤之前还是之后"的，
     * 是下面 {@code nonlinearFinal} 的 priority。
     */
    private static final int PRIORITY = 3600;

    /**
     * END_NONLINEAR 档内的 priority。<b>刻意取小</b>：9000 那档是"每击上限"类
     * （潘多拉之咒 {@code CurseOfPrudenceItem:140}），保底排在它前面 → 限伤仍然生效。
     */
    private static final int ORDER_PRIORITY = 3600;

    /** 只认这一个伤害类型（绝炎冲 5 连爆）。 */
    private static final String MYSTIC_BLAST_ID = "mystic_blast";

    /** 这次命中用的保底数据：{@code pre} 是保底前的终值，{@code floor} 是这一爆的保底线。 */
    public record FloorInfo(float pre, float floor, float post) {
    }

    /** 交给诊断探针做对照；读到即清除。 */
    private static final Map<UUID, FloorInfo> PRE_FLOOR = new ConcurrentHashMap<>();

    public static void register() {
        AttackEventHandler.register(PRIORITY, new MysticBlastFloorListener());
    }

    private MysticBlastFloorListener() {
    }

    @Override
    public void onDamage(AttackCache cache, ItemStack weapon) {
        LivingEntity attacker = cache.getAttacker();
        LivingEntity target = cache.getAttackTarget();
        if (!(attacker instanceof AbstractGolemEntity<?, ?>) || target == null || attacker == target) {
            return;
        }
        DamageSource source = cache.getLivingDamageEvent() == null
                ? null : cache.getLivingDamageEvent().getSource();
        if (source == null || !MYSTIC_BLAST_ID.equals(source.getMsgId())) {
            return;
        }
        // 无条件清掉上一击的残留：否则探针会读到上一条命中留下的旧值。
        UUID id = target.getUUID();
        PRE_FLOOR.remove(id);

        // getPreDamage() 依赖 hurt 阶段跑过；没跑过（被免疫/无敌帧挡在 hurt 之前）会抛，这里就是不保。
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
        // 防御性 clamp：断言范围，改坏了也不会出现"保底比原伤害还高"的越界。
        final float floor = base * Mth.clamp(FLOOR_PERCENT, 0.0F, 1.0F);

        cache.addDealtModifier(DamageModifier.nonlinearFinal(ORDER_PRIORITY, x -> {
            PRE_FLOOR.put(id, new FloorInfo(x, floor, Math.max(x, floor)));
            // 只增不减：这一爆本来就打够了就走原值。
            return x >= floor ? x : floor;
        }));
    }

    /**
     * 取走这一爆的保底数据，供诊断探针打印对照。没有记录时返回 {@code null}
     * （表示这次命中不是绝炎冲，或者被挡在 hurt 之前根本没得保）。
     */
    public static FloorInfo takePreFloor(LivingEntity target) {
        return PRE_FLOOR.remove(target.getUUID());
    }
}
