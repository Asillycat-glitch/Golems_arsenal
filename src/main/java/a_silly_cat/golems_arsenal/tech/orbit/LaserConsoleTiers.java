package a_silly_cat.golems_arsenal.tech.orbit;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.base.upgrade.GolemOrbitalGuidanceModifier;

/**
 * 激光发射台的档位常量 —— 发射台方块实体、全局记录、GUI 共用同一份。
 *
 * <p>档位倍率故意写死在代码里：改一处就能整体平移，不需要额外配置项。
 * 新增档位时只改这里的数组和 {@code *_COUNT}。
 */
public final class LaserConsoleTiers {

    public static final int ENERGY_COUNT = 3;
    public static final int COOLDOWN_COUNT = 3;

    /** 能量档 0/1/2：经济 / 标准 / 超载。 */
    private static final double[] ENERGY_COST = {0.60D, 1.00D, 1.60D};
    private static final double[] ENERGY_DAMAGE = {0.75D, 1.00D, 1.40D};
    /** 冷却档 0/1/2：快速 / 标准 / 蓄能。乘在 orbital_guidance.cooldown 上。 */
    private static final double[] COOLDOWN = {0.70D, 1.00D, 1.50D};

    /** 安全区半径选项（格）。0 = 关闭。 */
    public static final int[] SAFE_RADII = {0, 8, 16, 32};

    public static double energyCostMultiplier(int tier) {
        return ENERGY_COST[clampTier(tier, ENERGY_COUNT)];
    }

    public static double energyDamageMultiplier(int tier) {
        return ENERGY_DAMAGE[clampTier(tier, ENERGY_COUNT)];
    }

    public static double cooldownMultiplier(int tier) {
        return COOLDOWN[clampTier(tier, COOLDOWN_COUNT)];
    }

    /** 两发之间的实际间隔（tick）：基础冷却 × 冷却档倍率。 */
    public static int effectiveCooldownTicks(int tier) {
        return Math.max(1, (int) Math.round(
                Config.ORBITAL_BEAM_COOLDOWN.get() * cooldownMultiplier(tier)));
    }

    /** GUI 展示用：满级引导升级的一发成本（含能量档倍率）。 */
    public static int displayCost(int energyTier) {
        return (int) Math.round(GolemOrbitalGuidanceModifier.costOf(
                GolemOrbitalGuidanceModifier.MAX_LEVEL) * energyCostMultiplier(energyTier));
    }

    /** GUI 展示用：满级引导升级的一发基础伤害（含能量档倍率）。 */
    public static float displayDamage(int energyTier) {
        return (float) (GolemOrbitalGuidanceModifier.damageOf(
                GolemOrbitalGuidanceModifier.MAX_LEVEL) * energyDamageMultiplier(energyTier));
    }

    public static int clampTier(int value, int count) {
        return Math.max(0, Math.min(count - 1, value));
    }

    /** 循环到下一个安全区半径；当前值不在选项里时从第一项开始。 */
    public static int nextSafeRadius(int current) {
        for (int i = 0; i < SAFE_RADII.length; i++) {
            if (SAFE_RADII[i] == current) {
                return SAFE_RADII[(i + 1) % SAFE_RADII.length];
            }
        }
        return SAFE_RADII[0];
    }

    private LaserConsoleTiers() {
    }
}
