package a_silly_cat.golems_arsenal.tech.upgrade;

import a_silly_cat.golems_arsenal.base.upgrade.GolemUpgrades;
import a_silly_cat.golems_arsenal.init.ModTags;
import dev.xkmc.modulargolems.content.item.upgrade.IUpgradeItem;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * 合金链的注册表 —— **"有哪些合金链、各档要什么前置"的唯一真相来源**。
 *
 * <p>两条链都是三档（灌注 / 精英 / 终极，各贡献 2 / 4 / 8 点等级），差别只在前置：
 *
 * <table border="1">
 *   <tr><th>链</th><th>入口档</th><th>逐级前置</th></tr>
 *   <tr><td>能源</td><td>灌注（要求"基础电量升级"）</td><td>灌注 → 精英 → 终极</td></tr>
 *   <tr><td>引导</td><td><b>灌注本身</b>（无前置）</td><td>灌注 → 精英 → 终极</td></tr>
 * </table>
 *
 * <p><b>引导链没有核心件</b>：它是 MEK 的附属联动（没有 Mekanism 就不注册这几件物品），
 * 玩法上也只是一条"傀儡标定 + 平台打击"的附加线路，不该再占一个槽。所以灌注就是入口。
 *
 * <p>装配路径（升级界面 mixin、可重复锻造配方）只遍历 {@link #ALL}，所以新增一条链时
 * 只要在这个类里加一项，两处装配逻辑都会自动跟上。
 */
public final class AlloyChains {

    // ------------------------------------------------------------------
    // 各档的标签
    // ------------------------------------------------------------------

    private static final TagKey<Item> ENERGY_INFUSED = ModTags.ENERGY_ALLOY_UPGRADES;
    private static final TagKey<Item> ENERGY_ELITE = tag("energy_elite_upgrades");
    private static final TagKey<Item> ENERGY_ULTIMATE = tag("energy_ultimate_upgrades");

    private static final TagKey<Item> GUIDANCE_INFUSED = ModTags.GUIDANCE_ALLOY_UPGRADES;
    private static final TagKey<Item> GUIDANCE_ELITE = tag("guidance_elite_upgrades");
    private static final TagKey<Item> GUIDANCE_ULTIMATE = tag("guidance_ultimate_upgrades");

    // ------------------------------------------------------------------
    // 链定义
    // ------------------------------------------------------------------

    /**
     * 能源链：入口是灌注，但要先有基础电量升级（它承担"核心件"的语义，占 1 槽）。
     */
    public static final AlloyChainUpgradeItem.Chain ENERGY = new AlloyChainUpgradeItem.Chain(
            "energy",
            List.of(
                    new AlloyChainUpgradeItem.Tier(ENERGY_INFUSED, ModTags.ENERGY_BASE_UPGRADES),
                    new AlloyChainUpgradeItem.Tier(ENERGY_ELITE, ENERGY_INFUSED),
                    new AlloyChainUpgradeItem.Tier(ENERGY_ULTIMATE, ENERGY_ELITE)));

    /**
     * 引导链：**灌注即入口**（无前置），逐级向上。三件都不占槽位，所以给傀儡装齐的成本是三个槽。
     */
    public static final AlloyChainUpgradeItem.Chain GUIDANCE = new AlloyChainUpgradeItem.Chain(
            "guidance",
            List.of(
                    new AlloyChainUpgradeItem.Tier(GUIDANCE_INFUSED, null),
                    new AlloyChainUpgradeItem.Tier(GUIDANCE_ELITE, GUIDANCE_INFUSED),
                    new AlloyChainUpgradeItem.Tier(GUIDANCE_ULTIMATE, GUIDANCE_ELITE)));

    /** 全部合金链。新增链只改这一行。 */
    public static final List<AlloyChainUpgradeItem.Chain> ALL = List.of(ENERGY, GUIDANCE);

    // ------------------------------------------------------------------
    // 物品构造入口
    // ------------------------------------------------------------------

    /** 能源链的灌注档（等级 +2，要求基础电量升级）。 */
    public static AlloyChainUpgradeItem energyInfused(Item.Properties properties,
                                                      Supplier<GolemModifier> modifier) {
        return new AlloyChainUpgradeItem(properties, 2, modifier, ENERGY_INFUSED, ModTags.ENERGY_BASE_UPGRADES);
    }

    /** 能源链的精英档（等级 +4，要求灌注）。 */
    public static AlloyChainUpgradeItem energyElite(Item.Properties properties,
                                                    Supplier<GolemModifier> modifier) {
        return new AlloyChainUpgradeItem(properties, 4, modifier, ENERGY_ELITE, ENERGY_INFUSED);
    }

    /** 能源链的终极档（等级 +8，要求精英）。 */
    public static AlloyChainUpgradeItem energyUltimate(Item.Properties properties,
                                                       Supplier<GolemModifier> modifier) {
        return new AlloyChainUpgradeItem(properties, 8, modifier, ENERGY_ULTIMATE, ENERGY_ELITE);
    }

    /** 引导链的灌注档（等级 +2，**无前置**，是这条链的入口）。 */
    public static AlloyChainUpgradeItem guidanceInfused(Item.Properties properties,
                                                        Supplier<GolemModifier> modifier) {
        return new AlloyChainUpgradeItem(properties, 14, modifier, GUIDANCE_INFUSED, null);
    }

    /** 引导链的精英档（等级 +4，要求灌注）。 */
    public static AlloyChainUpgradeItem guidanceElite(Item.Properties properties,
                                                      Supplier<GolemModifier> modifier) {
        return new AlloyChainUpgradeItem(properties, 4, modifier, GUIDANCE_ELITE, GUIDANCE_INFUSED);
    }

    /** 引导链的终极档（等级 +8，要求精英）。 */
    public static AlloyChainUpgradeItem guidanceUltimate(Item.Properties properties,
                                                         Supplier<GolemModifier> modifier) {
        return new AlloyChainUpgradeItem(properties, 8, modifier, GUIDANCE_ULTIMATE, GUIDANCE_ELITE);
    }

    // ------------------------------------------------------------------
    // 装配期校验（供 mixin 与锻造配方共用）
    // ------------------------------------------------------------------

    /**
     * 跨链校验：{@code incoming} 属于哪条已知合金链的哪一档，就按那一档的前置规则校验。
     * 返回 {@code null} 表示放行（含"不属于任何合金链"的情况）。
     */
    public static AlloyChainUpgradeItem.Rejection rejectionForAny(
            Collection<? extends IUpgradeItem> installed, IUpgradeItem incoming) {
        return AlloyChainUpgradeItem.rejectionForAny(installed, incoming, ALL);
    }

    private static TagKey<Item> tag(String path) {
        return TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                new net.minecraft.resources.ResourceLocation("golems_arsenal", path));
    }

    private AlloyChains() {
    }
}
