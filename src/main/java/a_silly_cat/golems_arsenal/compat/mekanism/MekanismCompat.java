package a_silly_cat.golems_arsenal.compat.mekanism;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.base.upgrade.GolemUpgrades;
import a_silly_cat.golems_arsenal.base.upgrade.GolemWeaponUpgradeItem;
import a_silly_cat.golems_arsenal.tech.orbit.RocketItem;
import a_silly_cat.golems_arsenal.tech.upgrade.AlloyChains;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Mekanism 联动物品，仅在装了 Mekanism 时注册。照 {@code GolemMagickaCompat} 的软依赖模式：
 * {@code CompatDispatch} 里用 {@code ModList} 门控，这个类直接引用对方 API（不用反射）。
 *
 * <p>本类目前管三件事：
 * <ol>
 *   <li>合金能源链（三档）；</li>
 *   <li>合金引导链（**一档**，天基激光）；</li>
 *   <li>辐射发电机升级（三档）与天基火箭物品。</li>
 * </ol>
 *
 * <p><b>2026-10-20 修</b>：此文件曾被误删（工作时未提交，git checkout 覆盖了工作区），
 * 现按编译产物的成员清单重建。重建时顺带把引导链收敛为一档。
 */
public final class MekanismCompat {

    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Golems_arsenal.MODID);

    // ------------------------------------------------------------------
    // 合金能源链
    //
    // 灌注（入口，无前置）→ 精英（要灌注）→ 终极（要精英）
    // 每档各往同一个 alloy_energy 修饰符上加 2 / 4 / 8 点等级。
    // 三件都**不占槽位**（AlloyChainUpgradeItem.consumesSlot() == false），
    // 但每件都要一个升级槽位的**前置**在链上。
    // ------------------------------------------------------------------

    /** 灌注合金能源：+2 级（系数 1）。前置 = 基础电量升级。 */
    public static final RegistryObject<GolemWeaponUpgradeItem> INFUSED_ALLOY_ENERGY_UPGRADE = ITEMS.register(
            "infused_alloy_energy_upgrade",
            () -> AlloyChains.energyInfused(new Item.Properties().stacksTo(64),
                    GolemUpgrades::modifier));

    /** 精英合金能源：+4 级（系数 2）。前置 = 灌注。 */
    public static final RegistryObject<GolemWeaponUpgradeItem> ELITE_ALLOY_ENERGY_UPGRADE = ITEMS.register(
            "elite_alloy_energy_upgrade",
            () -> AlloyChains.energyElite(new Item.Properties().stacksTo(64),
                    GolemUpgrades::modifier));

    /** 终极合金能源：+8 级（系数 4）。前置 = 精英。 */
    public static final RegistryObject<GolemWeaponUpgradeItem> ULTIMATE_ALLOY_ENERGY_UPGRADE = ITEMS.register(
            "ultimate_alloy_energy_upgrade",
            () -> AlloyChains.energyUltimate(new Item.Properties().stacksTo(64),
                    GolemUpgrades::modifier));

    // ------------------------------------------------------------------
    // 合金引导链（天基激光）
    //
    // **只有一件**：2026-10-20 由三档收敛为一档，一次给出全部 14 点等级。
    // 收敛理由：三档的差别只是等级数值，玩起来等于"要凑齐三件才完整"，
    // 属于内容注水；天基激光的强度主要取决于打击半径与冷却，不在等级档位上。
    //
    // 仍然不占槽位；打击要扣傀儡的 FE，所以实际玩起来和能源链互相需要（设计如此）。
    // ------------------------------------------------------------------

    /** 合金引导：**唯一一档**，无前置，+14 级。 */
    public static final RegistryObject<GolemWeaponUpgradeItem> INFUSED_ALLOY_GUIDANCE_UPGRADE = ITEMS.register(
            "infused_alloy_guidance_upgrade",
            () -> AlloyChains.guidanceInfused(new Item.Properties().stacksTo(64),
                    GolemUpgrades::orbitalGuidanceModifier));

    // ------------------------------------------------------------------
    // 辐射发电机升级（三档）
    // ------------------------------------------------------------------

    public static final RegistryObject<GolemWeaponUpgradeItem> RADIATION_GENERATOR_UPGRADE_1 = ITEMS.register(
            "radiation_generator_upgrade_1",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1,
                    GolemUpgrades::radiationGeneratorModifier));

    public static final RegistryObject<GolemWeaponUpgradeItem> RADIATION_GENERATOR_UPGRADE_2 = ITEMS.register(
            "radiation_generator_upgrade_2",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 2,
                    GolemUpgrades::radiationGeneratorModifier));

    public static final RegistryObject<GolemWeaponUpgradeItem> RADIATION_GENERATOR_UPGRADE_3 = ITEMS.register(
            "radiation_generator_upgrade_3",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 4,
                    GolemUpgrades::radiationGeneratorModifier));

    // ------------------------------------------------------------------
    // 天基火箭（发射平台）
    // ------------------------------------------------------------------

    public static final RegistryObject<Item> ROCKET = ITEMS.register(
            "rocket",
            () -> new RocketItem(new Item.Properties().stacksTo(16)));

    private MekanismCompat() {
    }

    public static void register(IEventBus bus) {
        ModRecipeSerializers.register(bus);
        ITEMS.register(bus);
    }
}
