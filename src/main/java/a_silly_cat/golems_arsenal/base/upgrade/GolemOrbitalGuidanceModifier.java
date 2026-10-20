package a_silly_cat.golems_arsenal.base.upgrade;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.base.entity.OrbitalBeamEntity;
import a_silly_cat.golems_arsenal.tech.energy.GolemEnergyProvider;
import a_silly_cat.golems_arsenal.tech.mekanism.OrbitalImpact;
import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 天基激光**引导升级**（合金链）。
 *
 * <p>傀儡不再自己开火，而是当"前沿观察员"：它把自己看到的敌人**标定**给在轨的天基平台，
 * 由平台从天上打下来。所以：
 *
 * <ul>
 *   <li><b>引导不了近身目标</b>：太近的敌人来不及标定（{@link Config#ORBITAL_BEAM_MIN_RANGE}），
 *       这条同时限制了它在贴身混战里的强度，并给了"贴脸"一个反制手段。</li>
 *   <li><b>被挡住就失效</b>：目标被方块遮挡时打不到（{@link #canSee}），掩体因此有意义。</li>
 *   <li><b>不吃护甲</b>：伤害走 MEK 的激光伤害类型（见 {@code OrbitalBeamEntity}），
 *       是"定点清除"而不是"额外的一段输出"。</li>
 * </ul>
 *
 * <p><b>等级即倍率</b>（与合金能源链同一套步长制）：
 * <pre>
 *   总等级 L = 2（灌注）+ 4（精英）+ 8（终极）   满配 14（这条链没有核心件）
 *   可同时引导数  = 1 + (L/2 − 1) × step    ← 只装灌注就是 1 个目标
 *   单次打击段数  = 同上（目标少 → 段数全砸在首目标上，单挑最猛）
 *   单次伤害      = damage_base + level × damage_per_level
 *   单次 FE 消耗  = cost_base   + level × cost_per_level     ← 从傀儡自己的池里扣
 * </pre>
 * 伤害或 FE 任一项不满足就不开火——**代价是"打得起"**，不是冷却。
 */
public class GolemOrbitalGuidanceModifier extends GolemModifier {

    /** 等级上限 = 2 + 4 + 8（三档之和；这条链**没有核心件**，最低档灌注即入口）。 */
    public static final int MAX_LEVEL = 14;

    public static final String ID = "golems_arsenal:orbital_guidance";

    /** 各档的"档位系数 − 1"：只装灌注（等级 2）→ 1，再装精英（6）→ 3，三件齐（14）→ 7。 */
    private static final int[] WEIGHTED_BONUS = {0, 1, 3, 7};

    /** 待打击的目标列表（UUID）。存 NBT，读档后继续。 */
    private static final String TAG_TARGETS = "GolemsArsenalGuidanceTargets";
    /** 当前标记的批次（时间戳），用于调试观察"标定是否在刷新"。 */
    private static final String TAG_STAMP = "GolemsArsenalGuidanceStamp";
    /** 最近一次"处于战斗"的游戏时间（tick）。0 = 从未。 */
    private static final String TAG_COMBAT = "GolemsArsenalGuidanceCombat";

    public GolemOrbitalGuidanceModifier() {
        super(StatFilterType.ATTACK, MAX_LEVEL);
    }

    // ------------------------------------------------------------------
    // 等级与曲线
    // ------------------------------------------------------------------

    /** 傀儡身上引导升级的总等级（三档之和，封顶 {@link #MAX_LEVEL}）。 */
    public static int levelOf(AbstractGolemEntity<?, ?> golem) {
        return Math.min(MAX_LEVEL, GolemUpgradeLevels.levelOf(golem, ID));
    }

    /**
     * "合金加成"的等价量 = 总等级 ÷ 入口档的等级系数。
     *
     * <p><b>这条链没有核心件</b>（是 MEK 附属联动），所以最低档就是灌注（贡献 2 级）。
     * 于是：只装灌注 → 1，加精英 → 1+2=3，再加终极 → 1+2+4=7，三件齐 → 15。
     * 这个形状与能源链的 {@code alloyBonus} 一致（那里是 0/2/6/14，因为多一个 +1 的核心档），
     * 差别只在这条链的起点是 2 而不是 1。
     */
    public static int alloyBonus(int level) {
        return Math.max(0, level) / 2;
    }

    /** 可同时标定的目标数。0 级 = 没装 = 0 个。 */
    public static int maxTargets(int level) {
        if (level <= 0) {
            return 0;
        }
        return 1 + alloyBonus(level) * Config.ORBITAL_BEAM_GUIDANCE_STEP.get();
    }

    /** 单次打击的段数：目标少时火力集中（单目标最猛）。 */
    public static int volleys(int level) {
        return maxTargets(level);
    }

    /** 单段伤害。 */
    public static float damageOf(int level) {
        return (float) (Config.ORBITAL_BEAM_DAMAGE.get() + level * Config.ORBITAL_BEAM_DAMAGE_PER_LEVEL.get());
    }

    /** 单段 FE 消耗。 */
    public static int costOf(int level) {
        return (int) Math.min(Integer.MAX_VALUE,
                Config.ORBITAL_BEAM_COST_BASE.get() + (long) level * Config.ORBITAL_BEAM_COST_PER_LEVEL.get());
    }

    // ------------------------------------------------------------------
    // AI tick
    // ------------------------------------------------------------------

    @Override
    public void onAiStep(AbstractGolemEntity<?, ?> golem, int level) {
        if (golem.level().isClientSide) {
            return;
        }
        int lv = levelOf(golem);
        if (lv <= 0) {
            return;
        }
        // 记忆"最近是否处于战斗"：只要它自己挨打/打人/被某只怪盯上，就记一笔时间戳。
        // 这个判定必须在开火之前做——它同时决定"要不要点火"和"冷却能不能推进"。
        boolean inCombat = trackCombat(golem);

        // 不在战斗中就不引导：既避免它对着路过的野兔放天基炮，也让光束不会一直在同一片
        // 地形上犁（破坏是累积的，停火就等于停止破坏）。
        if (Config.ORBITAL_BEAM_COMBAT_ONLY.get() && !inCombat) {
            // 停火时把冷却一并冻结：这样"重新进入战斗"不会立刻白送一发。
            return;
        }

        // 标定节拍：每 scan_interval tick 重新挑一批目标
        if (golem.tickCount % Math.max(1, Config.ORBITAL_BEAM_SCAN_INTERVAL.get()) == 0) {
            identify(golem, lv);
        }
        // 打击节拍：每 cooldown tick 打一轮
        if (golem.tickCount % Math.max(1, Config.ORBITAL_BEAM_COOLDOWN.get()) == 0) {
            fire(golem, lv);
        }
    }

    /**
     * 记录/查询"最近是否在战斗"。
     *
     * <p>判据取三种**真实威胁**信号，任一成立就刷新时间戳：
     * <ol>
     *   <li>傀儡自己有攻击目标（{@code getTarget()}）；</li>
     *   <li>它最近打过人或挨过打（{@code getLastHurtMob()} / {@code getLastHurtByMob()}）；</li>
     *   <li>附近有怪**正盯着它或它的主人**（这是"前沿观察员"该有的嗅觉，
     *       也是唯一能在"还没开打"时就成立的条件）。</li>
     * </ol>
     *
     * <p>时间戳存 {@code persistentData}，读档后仍然有效（存档里战斗记忆不该丢）。
     */
    private static boolean trackCombat(AbstractGolemEntity<?, ?> golem) {
        CompoundTag data = golem.getPersistentData();
        long now = golem.level().getGameTime();
        boolean threat = golem.getTarget() != null
                || golem.getLastHurtMob() != null
                || golem.getLastHurtByMob() != null
                || hasHostileAttention(golem);
        if (threat) {
            data.putLong(TAG_COMBAT, now);
            return true;
        }
        long last = data.getLong(TAG_COMBAT);
        int memory = Math.max(0, Config.ORBITAL_BEAM_COMBAT_MEMORY.get());
        return last != 0L && now - last <= memory;
    }

    /** 附近有没有怪正把"傀儡或它的主人"当目标。 */
    private static boolean hasHostileAttention(AbstractGolemEntity<?, ?> golem) {
        UUID master = golem.getOwnerUUID();
        double range = Math.min(Config.ORBITAL_BEAM_GUIDANCE_RANGE.get(), 24.0);
        AABB box = golem.getBoundingBox().inflate(range);
        for (Mob mob : golem.level().getEntitiesOfClass(Mob.class, box)) {
            if (!mob.isAlive() || GolemFriendlyFire.isFriendly(golem, mob, golem.getUUID(), master)) {
                continue;
            }
            LivingEntity mobTarget = mob.getTarget();
            if (mobTarget == golem) {
                return true;
            }
            if (master != null && mobTarget != null && master.equals(mobTarget.getUUID())) {
                return true;
            }
        }
        return false;
    }

    /** 标定：把自己看到的敌意目标记进待打列表（最多 {@link #maxTargets} 个，就近优先）。 */
    private static void identify(AbstractGolemEntity<?, ?> golem, int lv) {
        double range = Config.ORBITAL_BEAM_GUIDANCE_RANGE.get();
        double minRange = Config.ORBITAL_BEAM_MIN_RANGE.get();
        AABB box = golem.getBoundingBox().inflate(range);

        UUID master = golem.getOwnerUUID();
        List<LivingEntity> candidates = new ArrayList<>();
        for (LivingEntity target : golem.level().getEntitiesOfClass(LivingEntity.class, box)) {
            if (!target.isAlive() || target == golem) {
                continue;
            }
            if (GolemFriendlyFire.isFriendly(golem, target, golem.getUUID(), master)) {
                continue;
            }
            double d = golem.distanceTo(target);
            if (d < minRange || d > range) {
                continue;
            }
            // 被方块挡住的打不到：掩体有意义，也避免"穿墙击杀"的观感问题
            if (!canSee(golem, target)) {
                continue;
            }
            candidates.add(target);
        }
        if (candidates.isEmpty()) {
            return;
        }
        candidates.sort((a, b) -> Double.compare(golem.distanceTo(a), golem.distanceTo(b)));

        List<UUID> picked = new ArrayList<>();
        for (LivingEntity target : candidates) {
            // 傀儡当前正在打的目标优先（让平台配合它的攻击节奏）
            if (target == golem.getTarget()) {
                picked.add(target.getUUID());
            }
        }
        for (LivingEntity target : candidates) {
            if (picked.size() >= maxTargets(lv)) {
                break;
            }
            if (!picked.contains(target.getUUID())) {
                picked.add(target.getUUID());
            }
        }

        CompoundTag data = golem.getPersistentData();
        data.putLong(TAG_STAMP, golem.level().getGameTime());
        // 只存完整 UUID：int 摘要还原不出实体，没有意义。
        CompoundTag full = new CompoundTag();
        full.putInt("n", picked.size());
        for (int i = 0; i < picked.size(); i++) {
            full.putUUID("t" + i, picked.get(i));
        }
        data.put(TAG_TARGETS, full);
    }

    /** 开火：对当前标定的目标各打一段（目标少于上限时，差额转成对首目标的额外段数）。 */
    private static void fire(AbstractGolemEntity<?, ?> golem, int lv) {
        if (!(golem.level() instanceof ServerLevel level)) {
            return;
        }
        List<LivingEntity> targets = readTargets(golem);
        if (targets.isEmpty()) {
            return;
        }
        int volleyCount = volleys(lv);
        int perTargetVolleys = Math.max(1, volleyCount - (targets.size() - 1));
        float damage = damageOf(lv);
        int cost = costOf(lv);

        for (LivingEntity target : targets) {
            if (!target.isAlive()) {
                continue;
            }
            int strikes = target == targets.get(0) ? volleyCount : perTargetVolleys;
            for (int i = 0; i < strikes; i++) {
                if (!payCost(golem, cost)) {
                    // 打不起了：直接停火（下一轮冷却后若充上电会继续）
                    return;
                }
                fireOne(level, golem, target, damage);
            }
        }
    }

    private static void fireOne(ServerLevel level, AbstractGolemEntity<?, ?> golem,
                                LivingEntity target, float damage) {
        // 落点走 OrbitalImpact：目标脚下的地表。
        Vec3 impact = OrbitalImpact.impactPoint(level, target);
        // 基础伤害 = 20 + 傀儡攻击力；灼烧固定 1 秒（20 tick）
        float base = 20.0F + (float) golem.getAttributeValue(
                net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        OrbitalBeamEntity.fire(level, impact, base,
                Config.ORBITAL_BEAM_RESIDUAL_DAMAGE.get().floatValue(),
                Config.ORBITAL_BEAM_HIT_RADIUS.get(), 20,
                Config.ORBITAL_BEAM_WIDTH.get().floatValue(),
                golem, Config.ORBITAL_BEAM_ALTITUDE.get().floatValue());
    }

    /** 从傀儡自己的 FE 池扣费；不够就返回 false。 */
    private static boolean payCost(AbstractGolemEntity<?, ?> golem, int cost) {
        if (cost <= 0) {
            return true;
        }
        return golem.getCapability(GolemEnergyProvider.CAPABILITY).map(storage -> {
            if (storage.getEnergyStored() < cost) {
                return false;
            }
            return storage.extractEnergy(cost, false) >= cost;
        }).orElse(false);
    }

    /** 把标定列表读回来（丢弃已死/已消失的目标）。 */
    private static List<LivingEntity> readTargets(AbstractGolemEntity<?, ?> golem) {
        CompoundTag data = golem.getPersistentData();
        if (!data.contains(TAG_TARGETS, CompoundTag.TAG_COMPOUND)) {
            return List.of();
        }
        CompoundTag full = data.getCompound(TAG_TARGETS);
        int n = full.getInt("n");
        if (n <= 0) {
            return List.of();
        }
        List<LivingEntity> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (!full.hasUUID("t" + i)) {
                continue;
            }
            UUID uuid = full.getUUID("t" + i);
            if (golem.level() instanceof ServerLevel server
                    && server.getEntity(uuid) instanceof LivingEntity living
                    && living.isAlive()) {
                out.add(living);
            }
        }
        return out;
    }

    /**
     * 傀儡能不能"看到"目标：对视线做一次方块碰撞检测。
     * 用 {@code clip} 而不是直接连线，是为了让墙、地形真的能挡住天基打击。
     */
    private static boolean canSee(AbstractGolemEntity<?, ?> golem, LivingEntity target) {
        Vec3 from = golem.getEyePosition();
        Vec3 to = target.getEyePosition();
        var hit = golem.level().clip(new ClipContext(from, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, golem));
        return hit.getType() == HitResult.Type.MISS;
    }

    // ------------------------------------------------------------------
    // 文案
    // ------------------------------------------------------------------

    @Override
    public Component getTooltip(int level) {
        return Component.translatable("upgrade.golems_arsenal.orbital_guidance")
                .withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        int lv = levelOf2Detail(level);
        return List.of(
                Component.translatable("upgrade.golems_arsenal.orbital_guidance.desc",
                        maxTargets(lv), damageOf(lv), costOf(lv)).withStyle(ChatFormatting.GREEN),
                Component.translatable("upgrade.golems_arsenal.orbital_guidance.volley",
                        volleys(lv)).withStyle(ChatFormatting.GREEN),
                Component.translatable("upgrade.golems_arsenal.orbital_guidance.note")
                        .withStyle(ChatFormatting.DARK_AQUA));
    }

    /** tooltip 传进来的是 modifier 的等级；这里做个下限保护，避免展示 0 级数据。 */
    private static int levelOf2Detail(int level) {
        return Math.max(1, Math.min(MAX_LEVEL, level));
    }
}
