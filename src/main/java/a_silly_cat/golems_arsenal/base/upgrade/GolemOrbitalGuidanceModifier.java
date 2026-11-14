package a_silly_cat.golems_arsenal.base.upgrade;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.tech.mekanism.OrbitalImpact;
import a_silly_cat.golems_arsenal.tech.orbit.LaserConsoleNetwork;
import a_silly_cat.golems_arsenal.tech.orbit.OrbitalStrike;
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
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * 天基激光**引导升级**（合金链）。
 *
 * <p>傀儡不再自己开火，而是当"前沿观察员"：它标定**一个**敌人，由在轨平台打下一发
 * 完整的激光炮（六束瞄准 → 弹体 → 落点冲击，见 {@link OrbitalStrike}）。
 *
 * <p><b>为什么是单目标。</b>2026-10-21 用户定稿：原先"等级越高可同时标定越多目标、目标少时
 * 段数全砸首目标"的多段锁定是留给未来「增强升级」的；现在没有增加锁定数的需求，
 * 所以这一档就是**一发**——等级只负责单发伤害与 FE 消耗，不再堆目标数。
 *
 * <p>三条游戏约束（都已在代码里生效）：
 * <ul>
 *   <li><b>贴身目标引导不了</b>：{@link Config#ORBITAL_BEAM_MIN_RANGE}，防"变成额外近战伤害"；</li>
 *   <li><b>被方块挡住就打不到</b>：{@link #canSee} 做视线检测，掩体有意义；</li>
 *   <li><b>每发扣傀儡的 FE</b>：由合金能源链决定打得起几发，打不起就静默停火。</li>
 * </ul>
 *
 * <p><b>发射台是必需的地面控制端</b>：傀儡所在维度里必须有一个已启用、且已绑定在轨平台的
 * {@link LaserConsoleBlockEntity}。发射台决定能量档 / 冷却档 / 友军保护 / 安全区；
 * 没有发射台或平台未入轨时，这一发不会打出去。
 */
public class GolemOrbitalGuidanceModifier extends GolemModifier {

    /** 等级上限 = 2 + 4 + 8（三档之和；这条链**没有核心件**，最低档灌注即入口）。 */
    public static final int MAX_LEVEL = 14;

    public static final String ID = "golems_arsenal:orbital_guidance";

    /** 当前标定的目标（单个 UUID）。存 NBT，读档后继续。 */
    private static final String TAG_TARGET = "GolemsArsenalGuidanceTarget";
    /** 当前标记的批次（时间戳），用于调试观察"标定是否在刷新"。 */
    private static final String TAG_STAMP = "GolemsArsenalGuidanceStamp";
    /** 最近一次"处于战斗"的游戏时间（tick）。0 = 从未。 */
    private static final String TAG_COMBAT = "GolemsArsenalGuidanceCombat";
    /** 最近一次成功开火的游戏时间（tick）。0 = 从未。 */
    private static final String TAG_LAST_SHOT = "GolemsArsenalGuidanceLastShot";
    /** 最近一次提示"目标在安全区内"的游戏时间，避免每 tick 刷屏。 */
    private static final String TAG_SAFE_WARN = "GolemsArsenalGuidanceSafeWarn";
    /** 最近一次提示"目标上方有遮挡"的游戏时间。 */
    private static final String TAG_COVERED_WARN = "GolemsArsenalGuidanceCoveredWarn";

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
     * 单发基础伤害（不含发射台的能量档倍率）。
     *
     * <p>2026-10-21 起从"每 tick 光柱伤害"改成"一发炮的冲击伤害"：默认值比旧的光柱
     * 高得多，因为现在一轮只有一发，而不是最多 15 段。
     */
    public static float damageOf(int level) {
        return (float) (Config.ORBITAL_GUIDANCE_IMPACT_DAMAGE.get()
                + level * Config.ORBITAL_GUIDANCE_IMPACT_DAMAGE_PER_LEVEL.get());
    }

    /** 单发基础 FE 消耗（不含发射台的能量档倍率）。 */
    public static int costOf(int level) {
        return (int) Math.min(Integer.MAX_VALUE,
                Config.ORBITAL_BEAM_COST_BASE.get() + (long) level * Config.ORBITAL_BEAM_COST_PER_LEVEL.get());
    }

    /** 发射台的能量档把基础伤害/消耗乘上倍率。 */
    public static int costFor(int level, LaserConsoleNetwork.Console console) {
        return (int) Math.min(Integer.MAX_VALUE,
                Math.round(costOf(level) * console.energyCostMultiplier()));
    }

    public static float damageFor(int level, LaserConsoleNetwork.Console console) {
        return (float) (damageOf(level) * console.energyDamageMultiplier());
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
        boolean inCombat = trackCombat(golem);

        // 不在战斗中就不引导：避免它对着路过的野兔放天基炮。
        if (Config.ORBITAL_BEAM_COMBAT_ONLY.get() && !inCombat) {
            return;
        }

        // 标定节拍：每 scan_interval tick 重新挑一个目标
        if (golem.tickCount % Math.max(1, Config.ORBITAL_BEAM_SCAN_INTERVAL.get()) == 0) {
            identify(golem);
        }
        // 打击节拍：真正的间隔由发射台的冷却档决定，这里每 tick 只做一次廉价检查。
        tryFire(golem, lv);
    }

    /**
     * 记录/查询"最近是否在战斗"。
     *
     * <p>判据取三种**真实威胁**信号，任一成立就刷新时间戳：
     * <ol>
     *   <li>傀儡自己有攻击目标（{@code getTarget()}）；</li>
     *   <li>它最近打过人或挨过打（{@code getLastHurtMob()} / {@code getLastHurtByMob()}）；</li>
     *   <li>附近有怪**正盯着它或它的主人**。</li>
     * </ol>
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

    /**
     * 标定：挑**一个**目标（傀儡当前目标优先，否则最近的合法威胁）。
     *
     * <p>合法性 = 活着、不是自己人、距离在 {@code [min_range, range]} 内、视线没有被方块挡住。
     * 另外只认"真有敌意"的目标：{@link Enemy}，或已经锁定傀儡/主人的 {@link Mob}；
     * 这样不会把路过的牛羊当成敌人（当前目标例外——傀儡自己已经决定打它）。
     */
    private static void identify(AbstractGolemEntity<?, ?> golem) {
        LivingEntity best = null;

        // 傀儡当前正在打的目标优先（让平台配合它的攻击节奏）
        LivingEntity current = golem.getTarget();
        if (current != null && golem.level() instanceof ServerLevel server
                && !OrbitalImpact.canHitFromOrbit(golem.level(), current)) {
            // 地底/室内的目标：能看见、也能打近战，但天基光柱够不到。提示一次，不标定。
            warnCovered(golem, server);
        }
        if (current != null && isValidTarget(golem, current)) {
            best = current;
        }

        if (best == null) {
            double range = Config.ORBITAL_BEAM_GUIDANCE_RANGE.get();
            AABB box = golem.getBoundingBox().inflate(range);
            double bestDist = Double.MAX_VALUE;
            for (LivingEntity candidate : golem.level().getEntitiesOfClass(LivingEntity.class, box)) {
                if (!isValidTarget(golem, candidate)) {
                    continue;
                }
                double dist = golem.distanceToSqr(candidate);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = candidate;
                }
            }
        }

        CompoundTag data = golem.getPersistentData();
        data.putLong(TAG_STAMP, golem.level().getGameTime());
        if (best == null) {
            data.remove(TAG_TARGET);
        } else {
            data.putUUID(TAG_TARGET, best.getUUID());
        }
    }

    /** 开火：对当前标定的唯一目标打一发；间隔、伤害、能耗由发射台的档位决定。 */
    private static void tryFire(AbstractGolemEntity<?, ?> golem, int lv) {
        if (!(golem.level() instanceof ServerLevel level)) {
            return;
        }
        CompoundTag data = golem.getPersistentData();
        long now = level.getGameTime();

        LaserConsoleNetwork.Console console = LaserConsoleNetwork.findBest(level, golem.position());
        if (console == null) {
            return;
        }
        long last = data.getLong(TAG_LAST_SHOT);
        if (last != 0L && now - last < console.effectiveCooldownTicks()) {
            return;
        }

        LivingEntity target = readTarget(golem);
        // 目标可能是在标定之后才躲进地底的：先单独判一次，给出明确提示，再清目标。
        if (target != null && !OrbitalImpact.canHitFromOrbit(level, target)) {
            warnCovered(golem, level);
            data.remove(TAG_TARGET);
            return;
        }
        if (target == null || !isValidTarget(golem, target)) {
            data.remove(TAG_TARGET);
            return;
        }

        Vec3 impact = OrbitalImpact.impactPoint(level, target);
        // 发射台周围是安全区：不往自己家门口落炮。
        if (LaserConsoleNetwork.isInSafeZone(level, impact)) {
            warnSafeZone(golem, level);
            return;
        }

        int cost = costFor(lv, console);
        if (!payCost(golem, cost)) {
            return;
        }

        OrbitalStrike.cast(level, impact,
                Config.ORBITAL_GUIDANCE_STRIKE_RADIUS.get().floatValue(),
                damageFor(lv, console),
                Config.ORBITAL_BEAM_RESIDUAL_DAMAGE.get().floatValue(),
                Config.ORBITAL_BEAM_WIDTH.get().floatValue(),
                golem,
                Config.ORBITAL_BEAM_ALTITUDE.get().floatValue(),
                console.friendlyProtection());
        data.putLong(TAG_LAST_SHOT, now);
    }

    /**
     * 目标落在发射台安全区里时提示主人。
     *
     * <p>这条以前是**完全静默**的：玩家只看到傀儡不发射，看不出是安全区挡的。
     * 现在每 5 秒最多发一条 action bar（不刷聊天栏）。
     */
    private static void warnSafeZone(AbstractGolemEntity<?, ?> golem, ServerLevel level) {
        CompoundTag data = golem.getPersistentData();
        long now = level.getGameTime();
        if (now - data.getLong(TAG_SAFE_WARN) < 100L) {
            return;
        }
        data.putLong(TAG_SAFE_WARN, now);
        UUID ownerId = golem.getOwnerUUID();
        if (ownerId == null) {
            return;
        }
        if (level.getPlayerByUUID(ownerId) instanceof net.minecraft.server.level.ServerPlayer player) {
            player.displayClientMessage(Component.translatable(
                    "message.golems_arsenal.orbital_safe_zone")
                    .withStyle(ChatFormatting.RED), true);
        }
    }

    /**
     * 目标头顶有遮挡、光柱够不到时提示主人。
     *
     * <p>这是"地底作战一直召唤、却打不到"的主要反馈：地底/室内的目标会被
     * {@link OrbitalImpact#canHitFromOrbit} 判为不可标定，既不标定也不扣 FE；
     * 如果目标是标定后才躲进去的，这里会提示一次并立刻清掉目标。
     */
    private static void warnCovered(AbstractGolemEntity<?, ?> golem, ServerLevel level) {
        CompoundTag data = golem.getPersistentData();
        long now = level.getGameTime();
        if (now - data.getLong(TAG_COVERED_WARN) < 200L) {
            return;
        }
        data.putLong(TAG_COVERED_WARN, now);
        UUID ownerId = golem.getOwnerUUID();
        if (ownerId == null) {
            return;
        }
        if (level.getPlayerByUUID(ownerId) instanceof net.minecraft.server.level.ServerPlayer player) {
            player.displayClientMessage(Component.translatable(
                    "message.golems_arsenal.orbital_covered")
                    .withStyle(ChatFormatting.RED), true);
        }
    }

    /** 从傀儡自己的 FE 池扣费；不够就返回 false。 */
    private static boolean payCost(AbstractGolemEntity<?, ?> golem, int cost) {
        if (cost <= 0) {
            return true;
        }
        return golem.getCapability(a_silly_cat.golems_arsenal.tech.energy.GolemEnergyProvider.CAPABILITY)
                .map(storage -> {
                    if (storage.getEnergyStored() < cost) {
                        return false;
                    }
                    return storage.extractEnergy(cost, false) >= cost;
                }).orElse(false);
    }

    /** 把标定的目标读回来（已死/已消失就返回 null）。 */
    private static LivingEntity readTarget(AbstractGolemEntity<?, ?> golem) {
        CompoundTag data = golem.getPersistentData();
        if (!data.hasUUID(TAG_TARGET)) {
            return null;
        }
        if (!(golem.level() instanceof ServerLevel server)) {
            return null;
        }
        UUID uuid = data.getUUID(TAG_TARGET);
        return server.getEntity(uuid) instanceof LivingEntity living && living.isAlive()
                ? living : null;
    }

    /** 目标是否合法：活着、不是自己人、距离合适、视线没被挡住、并且确实有敌意。 */
    private static boolean isValidTarget(AbstractGolemEntity<?, ?> golem, LivingEntity target) {
        if (target == golem || !target.isAlive()) {
            return false;
        }
        UUID master = golem.getOwnerUUID();
        if (GolemFriendlyFire.isFriendly(golem, target, golem.getUUID(), master)) {
            return false;
        }
        double dist = golem.distanceTo(target);
        double range = Config.ORBITAL_BEAM_GUIDANCE_RANGE.get();
        if (dist < Config.ORBITAL_BEAM_MIN_RANGE.get() || dist > range) {
            return false;
        }
        if (!canSee(golem, target)) {
            return false;
        }
        // 地底/室内目标：头顶有遮挡时落点会跑到遮挡物顶面，光柱从落点往上打，够不到目标。
        // 直接判为不可标定，避免傀儡反复召唤、白扣 FE。
        if (!OrbitalImpact.canHitFromOrbit(golem.level(), target)) {
            return false;
        }
        if (target == golem.getTarget()) {
            return true;
        }
        if (target instanceof Enemy) {
            return true;
        }
        if (target instanceof Mob mob) {
            LivingEntity mobTarget = mob.getTarget();
            return mobTarget == golem
                    || (master != null && mobTarget != null && master.equals(mobTarget.getUUID()));
        }
        return false;
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
                        damageOf(lv), costOf(lv)).withStyle(ChatFormatting.GREEN),
                Component.translatable("upgrade.golems_arsenal.orbital_guidance.pad")
                        .withStyle(ChatFormatting.AQUA),
                Component.translatable("upgrade.golems_arsenal.orbital_guidance.note")
                        .withStyle(ChatFormatting.DARK_AQUA));
    }

    /** tooltip 传进来的是 modifier 的等级；这里做个下限保护，避免展示 0 级数据。 */
    private static int levelOf2Detail(int level) {
        return Math.max(1, Math.min(MAX_LEVEL, level));
    }
}
