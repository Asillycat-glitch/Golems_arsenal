package a_silly_cat.golems_arsenal.init;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.compat.golemmagicka.GolemMagickaCompat;
import a_silly_cat.golems_arsenal.base.item.ExampleWeapon;
import a_silly_cat.golems_arsenal.base.item.KeySwordItem;
import a_silly_cat.golems_arsenal.base.item.ShenTongStaffItem;
import a_silly_cat.golems_arsenal.tech.item.GolemEnergyKatanaItem;
import a_silly_cat.golems_arsenal.tech.item.GolemEnergyHammerItem;
import a_silly_cat.golems_arsenal.tech.item.GolemZeroSwordItem;
import a_silly_cat.golems_arsenal.tech.item.GolemTrackingMechanicalBowItem;
import a_silly_cat.golems_arsenal.tech.energy.MinionChargeLinkItem;
import a_silly_cat.golems_arsenal.base.upgrade.GolemUpgrades;
import a_silly_cat.golems_arsenal.base.upgrade.GolemWeaponUpgradeItem;
import a_silly_cat.golems_arsenal.base.upgrade.RepeatableExpansionItem;
import a_silly_cat.golems_arsenal.base.upgrade.SpecialMoveUpgradeItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.fml.ModList;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Golems_arsenal.MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Golems_arsenal.MODID);

    public static final RegistryObject<ExampleWeapon> EXAMPLE_WEAPON = ITEMS.register(
            "example_weapon",
            () -> new ExampleWeapon(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<ShenTongStaffItem> SHEN_TONG_STAFF = ITEMS.register(
            "shen_tong_staff",
            () -> new ShenTongStaffItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<KeySwordItem> KEY_SWORD = ITEMS.register(
            "key_sword",
            () -> new KeySwordItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<GolemEnergyKatanaItem> GOLEM_ENERGY_KATANA = ITEMS.register(
            "golem_energy_katana",
            () -> new GolemEnergyKatanaItem(new Item.Properties().stacksTo(1).fireResistant()));

    public static final RegistryObject<GolemEnergyHammerItem> GOLEM_ENERGY_HAMMER = ITEMS.register(
            "golem_energy_hammer",
            () -> new GolemEnergyHammerItem(new Item.Properties().stacksTo(1).fireResistant()));

    public static final RegistryObject<GolemZeroSwordItem> ZERO_ENERGY_SWORD = ITEMS.register(
            "zero_energy_sword",
            () -> new GolemZeroSwordItem(new Item.Properties().stacksTo(1).fireResistant()));

    public static final RegistryObject<GolemTrackingMechanicalBowItem> GOLEM_TRACKING_BOW = ITEMS.register(
            "golem_tracking_mechanical_bow",
            () -> new GolemTrackingMechanicalBowItem(new Item.Properties().stacksTo(1).fireResistant()));

    /**
     * 幻梦零剑气的**模型载体**：不进创造栏、没有配方，只是为了让剑气模型走物品渲染管线
     * （物品渲染管线比 RegisterAdditional 那条路稳，我们键刃也是这么渲染的）。
     */
    public static final RegistryObject<Item> GENMU_ZERO_BLADE = ITEMS.register(
            "genmu_zero_blade",
            () -> new Item(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<GolemWeaponUpgradeItem> GOLEM_ENERGY_UPGRADE = ITEMS.register(
            "golem_energy_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::modifier));

    public static final RegistryObject<GolemWeaponUpgradeItem> GOLEM_ENERGY_TECH_UPGRADE = ITEMS.register(
            "golem_energy_tech_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::techModifier));

    /**
     * 指令升级。
     * <p>
     * 等级上限由 {@code Config.ARMORY_MAX_LEVEL} 决定，所以这里注册三个等级的物品：
     * 玩家可以分别装上它们来选等级（本家升级物品的等级是烘在物品上的，不是读 NBT）。
     * 效果数量 = 等级，所以 1 级只解锁 1 个、3 级解锁 3 个。
     */
    public static final RegistryObject<GolemWeaponUpgradeItem> COMMAND_UPGRADE_1 = ITEMS.register(
            "command_upgrade_1",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::commandModifier));

    public static final RegistryObject<GolemWeaponUpgradeItem> COMMAND_UPGRADE_2 = ITEMS.register(
            "command_upgrade_2",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 2, GolemUpgrades::commandModifier));

    public static final RegistryObject<GolemWeaponUpgradeItem> COMMAND_UPGRADE_3 = ITEMS.register(
            "command_upgrade_3",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 3, GolemUpgrades::commandModifier));

    public static final RegistryObject<GolemWeaponUpgradeItem> BACKUP_ENERGY_UPGRADE = ITEMS.register(
            "backup_energy_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::backupEnergyModifier));

    public static final RegistryObject<GolemWeaponUpgradeItem> MAIN_WEAPON_UPGRADE = ITEMS.register(
            "main_weapon_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::mainWeaponModifier));

    public static final RegistryObject<GolemWeaponUpgradeItem> ALT_WEAPON_UPGRADE = ITEMS.register(
            "alt_weapon_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::altWeaponModifier));

    public static final RegistryObject<GolemWeaponUpgradeItem> RANGED_WEAPON_UPGRADE = ITEMS.register(
            "ranged_weapon_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::rangedWeaponModifier));

    public static final RegistryObject<GolemWeaponUpgradeItem> SHIELD_WEAPON_UPGRADE = ITEMS.register(
            "shield_weapon_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::shieldWeaponModifier));

    public static final RegistryObject<GolemWeaponUpgradeItem> FULL_ONSLAUGHT_UPGRADE = ITEMS.register(
            "full_onslaught_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::onslaughtModifier));

    /** Cavalry upgrade: speed-scaled damage while the golem rides a golem mount. */
    public static final RegistryObject<GolemWeaponUpgradeItem> CAVALRY_UPGRADE = ITEMS.register(
            "cavalry_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::cavalryModifier));

    public static final RegistryObject<GolemWeaponUpgradeItem> STANCE_UPGRADE = ITEMS.register(
            "stance_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::stanceModifier));

    public static final RegistryObject<GolemWeaponUpgradeItem> STANCE_SUB_UPGRADE = ITEMS.register(
            "stance_sub_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::stanceSubModifier));

    public static final RegistryObject<GolemWeaponUpgradeItem> GRAZE_UPGRADE = ITEMS.register(
            "graze_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::grazeModifier));

    /** E 罐：存储修复材料，战斗中自动消耗补血（可叠两级 → 容量 100% / 200% 最大生命）。 */
    public static final RegistryObject<GolemWeaponUpgradeItem> E_TANK_UPGRADE = ITEMS.register(
            "e_tank_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::eTankModifier));

    /** 光柱护体：受击召唤 4 道光柱，2 秒无敌，光柱本身造成伤害。 */
    public static final RegistryObject<GolemWeaponUpgradeItem> LIGHT_PILLAR_UPGRADE = ITEMS.register(
            "light_pillar_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::lightPillarModifier));

    /** 减震：抗击退（每级 +0.5，两级免疫击退）。 */
    public static final RegistryObject<GolemWeaponUpgradeItem> SHOCK_BUFFER_UPGRADE = ITEMS.register(
            "shock_buffer_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::shockBufferModifier));

    /** 节约能源：FE 消耗每级 -25%。 */
    public static final RegistryObject<GolemWeaponUpgradeItem> ENERGY_SAVER_UPGRADE = ITEMS.register(
            "energy_saver_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::energySaverModifier));

    /** W 罐：把 E 罐里的修复材料换成傀儡 FE。 */
    public static final RegistryObject<GolemWeaponUpgradeItem> W_TANK_UPGRADE = ITEMS.register(
            "w_tank_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::wTankModifier));

    /** Example special-move upgrade: throws / orbits the key blade (needs the key sword). */
    public static final RegistryObject<SpecialMoveUpgradeItem> KEY_BLADE_SPIN_UPGRADE = ITEMS.register(
            "key_blade_spin_upgrade",
            () -> new SpecialMoveUpgradeItem(new Item.Properties().stacksTo(64),
                    GolemUpgrades::keyBladeSpinModifier));

    /** Example special-move upgrade: forward flip + downward slam AoE (no weapon requirement). */
    public static final RegistryObject<SpecialMoveUpgradeItem> LION_SLASH_UPGRADE = ITEMS.register(
            "lion_slash_upgrade",
            () -> new SpecialMoveUpgradeItem(new Item.Properties().stacksTo(64),
                    GolemUpgrades::lionSlashModifier));

    /** Example special-move upgrade: sword rain (needs the key sword). */
    public static final RegistryObject<SpecialMoveUpgradeItem> SWORD_RAIN_UPGRADE = ITEMS.register(
            "sword_rain_upgrade",
            () -> new SpecialMoveUpgradeItem(new Item.Properties().stacksTo(64),
                    GolemUpgrades::swordRainModifier));

    /** Example special-move upgrade: charge (dash behind the target; weapon changes the form). */
    public static final RegistryObject<SpecialMoveUpgradeItem> CHARGE_UPGRADE = ITEMS.register(
            "charge_upgrade",
            () -> new SpecialMoveUpgradeItem(new Item.Properties().stacksTo(64),
                    GolemUpgrades::chargeModifier));

    /** Special-move upgrade: phoenix rush (backward leap with a backflip, then a dive + fire impact). */
    public static final RegistryObject<SpecialMoveUpgradeItem> PHOENIX_UPGRADE = ITEMS.register(
            "phoenix_upgrade",
            () -> new SpecialMoveUpgradeItem(new Item.Properties().stacksTo(64),
                    GolemUpgrades::phoenixModifier));

    /**
     * Special-move upgrade: three-hit combo. Lowers the golem's attack speed (longer interval),
     * and every melee attack advances one stage of the combo, each with its own arc hit.
     */
    public static final RegistryObject<SpecialMoveUpgradeItem> COMBO_UPGRADE = ITEMS.register(
            "combo_upgrade",
            () -> new SpecialMoveUpgradeItem(new Item.Properties().stacksTo(64),
                    GolemUpgrades::comboModifier));

    /**
     * 炎拳（跨 mod 联动，最高 2 级）：命中带有灾变「炽热烙印」的目标时追加多段伤害。
     * 单件给 1 级，装第二件升到 2 级（与 E 罐等两级升级一致）；没装灾变时它只是一件装饰品。
     */
    public static final RegistryObject<GolemWeaponUpgradeItem> FLAME_FIST_UPGRADE = ITEMS.register(
            "flame_fist_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1,
                    GolemUpgrades::flameFistModifier));

    /**
     * 莫厄余烬：联动 JVG「莫厄充能」——每层增伤、必定穿甲、较低的 END 返还（单件 1 级，两件 2 级）。
     */
    public static final RegistryObject<GolemWeaponUpgradeItem> MEROR_AFTERGLOW_UPGRADE = ITEMS.register(
            "meror_afterglow_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1,
                    GolemUpgrades::merorAfterglowModifier));

    /**
     * 磨砺（反适应）：对同一个敌人久攻不下时，下一次攻击的伤害逐步提高（记录挂在敌人身上）。
     */
    public static final RegistryObject<GolemWeaponUpgradeItem> ATTRITION_UPGRADE = ITEMS.register(
            "attrition_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1,
                    GolemUpgrades::attritionModifier));

    /** Tech expansion template: forge it onto a golem holder repeatedly in a smithing table. */
    public static final RegistryObject<RepeatableExpansionItem> TECH_EXPANSION_TEMPLATE = ITEMS.register(
            "tech_expansion_template",
            () -> new RepeatableExpansionItem(new Item.Properties().stacksTo(64), GolemUpgrades::techExpansionModifier));

    /**
     * 激光发射台的方块物品。方块本身无条件下沉（它不引用任何 MEK 类），
     * 但创造栏里只在 MEK 在场时显示——没有 MEK 就没有火箭平台，它只是一台不能开火的终端。
     */
    public static final RegistryObject<Item> LASER_CONSOLE = ITEMS.register(
            "laser_console",
            () -> new a_silly_cat.golems_arsenal.tech.orbit.LaserConsoleItem(
                    ModBlocks.LASER_CONSOLE.get(), new Item.Properties()));

    /**
     * 仆从充能链接：玩家背包里的 FE 中转卡。被 Powah / 通量网络 / MEK 充电台充电后，
     * 自动把 FE 转发给附近属于自己的仆从（模块化傀儡、MEK 机器人等）。
     */
    public static final RegistryObject<MinionChargeLinkItem> MINION_CHARGE_LINK = ITEMS.register(
            "minion_charge_link",
            () -> new MinionChargeLinkItem(new Item.Properties()));

    public static final RegistryObject<CreativeModeTab> TAB = CREATIVE_TABS.register(
            "golems_arsenal",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.golems_arsenal"))
                    .icon(() -> new ItemStack(GOLEM_ENERGY_KATANA.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(GOLEM_ENERGY_KATANA.get());
                        output.accept(ZERO_ENERGY_SWORD.get());
                        output.accept(GOLEM_ENERGY_HAMMER.get());
                        output.accept(GOLEM_TRACKING_BOW.get());
                        output.accept(EXAMPLE_WEAPON.get());
                        output.accept(SHEN_TONG_STAFF.get());
                        output.accept(KEY_SWORD.get());
                        output.accept(GOLEM_ENERGY_UPGRADE.get());
                        output.accept(GOLEM_ENERGY_TECH_UPGRADE.get());
                        output.accept(MINION_CHARGE_LINK.get());
                        output.accept(COMMAND_UPGRADE_1.get());
                        output.accept(COMMAND_UPGRADE_2.get());
                        output.accept(COMMAND_UPGRADE_3.get());
                        output.accept(BACKUP_ENERGY_UPGRADE.get());
                        output.accept(MAIN_WEAPON_UPGRADE.get());
                        output.accept(ALT_WEAPON_UPGRADE.get());
                        output.accept(RANGED_WEAPON_UPGRADE.get());
                        output.accept(SHIELD_WEAPON_UPGRADE.get());
                        output.accept(FULL_ONSLAUGHT_UPGRADE.get());
                        output.accept(CAVALRY_UPGRADE.get());
                        output.accept(STANCE_UPGRADE.get());
                        output.accept(STANCE_SUB_UPGRADE.get());
                        output.accept(GRAZE_UPGRADE.get());
                        output.accept(E_TANK_UPGRADE.get());
                        output.accept(SHOCK_BUFFER_UPGRADE.get());
                        output.accept(ENERGY_SAVER_UPGRADE.get());
                        output.accept(W_TANK_UPGRADE.get());
                        output.accept(KEY_BLADE_SPIN_UPGRADE.get());
                        output.accept(LION_SLASH_UPGRADE.get());
                        output.accept(SWORD_RAIN_UPGRADE.get());
                        output.accept(CHARGE_UPGRADE.get());
                        output.accept(PHOENIX_UPGRADE.get());
                        output.accept(COMBO_UPGRADE.get());
                        output.accept(FLAME_FIST_UPGRADE.get());
                        output.accept(MEROR_AFTERGLOW_UPGRADE.get());
                        output.accept(ATTRITION_UPGRADE.get());
                        output.accept(TECH_EXPANSION_TEMPLATE.get());
                        if (ModList.get().isLoaded("golemmagicka")) {
                            output.accept(GolemMagickaCompat.SCROLL_UPGRADE.get());
                        }
                        if (ModList.get().isLoaded("mekanism")) {
                            output.accept(LASER_CONSOLE.get());
                            acceptMekanismEntries(output);
                        }
                    })
                    .build());

    /**
     * 把 Mekanism 联动的五个条目（3 件合金能源 + 3 件辐射发电 + 3 件辐射屏蔽）加进创造栏。
     *
     * <p><b>为什么用反射而不是直接 {@code MekanismCompat.XXX.get()}。</b>直接引用会把
     * {@code MekanismCompat} 写进 {@code ModItems} 的常量池，于是"没装 MEK 时能否安全加载
     * {@code ModItems}"就取决于 JVM 的解析时机（解析是惰性的，但一旦哪天被提前触发就是
     * NoClassDefFoundError）。既然门控已经判过一次模组存在性，这里多花一次反射换来确定性。
     *
     * <p>注意 MekanismCompat 那边的物品注册本身是按模组门控的（{@code CompatDispatch}），
     * 所以这里只负责"往栏里放"，不负责"注册"。
     */
    private static void acceptMekanismEntries(CreativeModeTab.Output output) {
        try {
            Class<?> compat = Class.forName("a_silly_cat.golems_arsenal.compat.mekanism.MekanismCompat");
            for (String field : new String[]{
                    "INFUSED_ALLOY_ENERGY_UPGRADE",
                    "ELITE_ALLOY_ENERGY_UPGRADE",
                    "ULTIMATE_ALLOY_ENERGY_UPGRADE",
                    "INFUSED_ALLOY_GUIDANCE_UPGRADE",
                    "RADIATION_GENERATOR_UPGRADE_1",
                    "RADIATION_GENERATOR_UPGRADE_2",
                    "RADIATION_GENERATOR_UPGRADE_3",
                    "ROCKET"}) {
                Object entry;
                try {
                    entry = compat.getField(field).get(null);
                } catch (NoSuchFieldException missing) {
                    // 某个条目被移除时不要静默拖垮整批（2026-10-20：删两档引导后，
                    // 该异常导致辐射三档和火箭全都没进创造栏）
                    continue;
                }
                if (entry instanceof RegistryObject<?> reg && reg.get() instanceof Item item) {
                    output.accept(item);
                }
            }
        } catch (ReflectiveOperationException e) {
            // 装了 MEK 却解析不到条目，说明联动代码与创造栏列表不同步——宁可少显示，
            // 也不要因为一个额外条目让整个创造栏构建失败。
            Golems_arsenal.LOGGER.warn("Mekanism compat items could not be added to the creative tab", e);
        }
    }

    private ModItems() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
    }
}
