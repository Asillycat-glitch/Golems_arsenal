package a_silly_cat.golems_arsenal.init;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.compat.golemmagicka.GolemMagickaCompat;
import a_silly_cat.golems_arsenal.base.item.ExampleWeapon;
import a_silly_cat.golems_arsenal.tech.item.GolemEnergyKatanaItem;
import a_silly_cat.golems_arsenal.tech.item.GolemEnergyHammerItem;
import a_silly_cat.golems_arsenal.tech.item.GolemTrackingMechanicalBowItem;
import a_silly_cat.golems_arsenal.tech.upgrade.GolemEnergyTechUpgradeItem;
import a_silly_cat.golems_arsenal.tech.upgrade.GolemEnergyUpgradeItem;
import a_silly_cat.golems_arsenal.base.upgrade.GolemUpgrades;
import a_silly_cat.golems_arsenal.base.upgrade.GolemWeaponUpgradeItem;
import a_silly_cat.golems_arsenal.base.upgrade.RepeatableExpansionItem;
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
            () -> new ExampleWeapon(new Item.Properties().stacksTo(1), 7, 0.2, 1.5f, 2.0f));

    public static final RegistryObject<GolemEnergyKatanaItem> GOLEM_ENERGY_KATANA = ITEMS.register(
            "golem_energy_katana",
            () -> new GolemEnergyKatanaItem(new Item.Properties().stacksTo(1).fireResistant()));

    public static final RegistryObject<GolemEnergyHammerItem> GOLEM_ENERGY_HAMMER = ITEMS.register(
            "golem_energy_hammer",
            () -> new GolemEnergyHammerItem(new Item.Properties().stacksTo(1).fireResistant()));

    public static final RegistryObject<GolemTrackingMechanicalBowItem> GOLEM_TRACKING_BOW = ITEMS.register(
            "golem_tracking_mechanical_bow",
            () -> new GolemTrackingMechanicalBowItem(new Item.Properties().stacksTo(1).fireResistant()));

    public static final RegistryObject<GolemEnergyUpgradeItem> GOLEM_ENERGY_UPGRADE = ITEMS.register(
            "golem_energy_upgrade",
            () -> new GolemEnergyUpgradeItem(new Item.Properties().stacksTo(64), 1));

    public static final RegistryObject<GolemEnergyTechUpgradeItem> GOLEM_ENERGY_TECH_UPGRADE = ITEMS.register(
            "golem_energy_tech_upgrade",
            () -> new GolemEnergyTechUpgradeItem(new Item.Properties().stacksTo(64), 1));

    /** Hidden legacy item keeping old saves with the pre-rename id working. Behaves identically to the tech upgrade. */
    public static final RegistryObject<GolemEnergyTechUpgradeItem> LEGACY_GOLEM_ENERGY_HEAL_UPGRADE = ITEMS.register(
            "golem_energy_heal_upgrade",
            () -> new GolemEnergyTechUpgradeItem(new Item.Properties().stacksTo(64), 1));

    public static final RegistryObject<GolemWeaponUpgradeItem> GOLEM_MAIN_WEAPON_UPGRADE = ITEMS.register(
            "golem_main_weapon_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::mainWeaponModifier));

    public static final RegistryObject<GolemWeaponUpgradeItem> GOLEM_ALT_WEAPON_UPGRADE = ITEMS.register(
            "golem_alt_weapon_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::altWeaponModifier));

    public static final RegistryObject<GolemWeaponUpgradeItem> GOLEM_RANGED_WEAPON_UPGRADE = ITEMS.register(
            "golem_ranged_weapon_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::rangedWeaponModifier));

    public static final RegistryObject<GolemWeaponUpgradeItem> GOLEM_SHIELD_WEAPON_UPGRADE = ITEMS.register(
            "golem_shield_weapon_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::shieldWeaponModifier));

    public static final RegistryObject<GolemWeaponUpgradeItem> GOLEM_FULL_ONSLAUGHT_UPGRADE = ITEMS.register(
            "golem_full_onslaught_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::onslaughtModifier));

    /** Tech expansion template: forge it onto a golem holder repeatedly in a smithing table. */
    public static final RegistryObject<RepeatableExpansionItem> TECH_EXPANSION_TEMPLATE = ITEMS.register(
            "tech_expansion_template",
            () -> new RepeatableExpansionItem(new Item.Properties().stacksTo(64), GolemUpgrades::techExpansionModifier));

    /**
     * Hidden legacy alias for the old id used before the rename to tech_expansion_template.
     * Old golem holders / JEI bookmarks that stored {@code golems_arsenal:golem_armory_expansion}
     * would otherwise resolve to AirItem and crash Modular Golems' collectModifiers while rendering.
     */
    public static final RegistryObject<RepeatableExpansionItem> LEGACY_TECH_EXPANSION_TEMPLATE = ITEMS.register(
            "golem_armory_expansion",
            () -> new RepeatableExpansionItem(new Item.Properties().stacksTo(64), GolemUpgrades::techExpansionModifier));

    public static final RegistryObject<CreativeModeTab> TAB = CREATIVE_TABS.register(
            "golems_arsenal",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.golems_arsenal"))
                    .icon(() -> new ItemStack(GOLEM_ENERGY_KATANA.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(GOLEM_ENERGY_KATANA.get());
                        output.accept(GOLEM_ENERGY_HAMMER.get());
                        output.accept(GOLEM_TRACKING_BOW.get());
                        output.accept(EXAMPLE_WEAPON.get());
                        output.accept(GOLEM_ENERGY_UPGRADE.get());
                        output.accept(GOLEM_ENERGY_TECH_UPGRADE.get());
                        output.accept(GOLEM_MAIN_WEAPON_UPGRADE.get());
                        output.accept(GOLEM_ALT_WEAPON_UPGRADE.get());
                        output.accept(GOLEM_RANGED_WEAPON_UPGRADE.get());
                        output.accept(GOLEM_SHIELD_WEAPON_UPGRADE.get());
                        output.accept(GOLEM_FULL_ONSLAUGHT_UPGRADE.get());
                        output.accept(TECH_EXPANSION_TEMPLATE.get());
                        if (ModList.get().isLoaded("golemmagicka")) {
                            output.accept(GolemMagickaCompat.GOLEM_SCROLL_UPGRADE.get());
                        }
                    })
                    .build());

    private ModItems() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
    }
}
