package a_silly_cat.golems_arsenal.init;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.compat.golemmagicka.GolemMagickaCompat;
import a_silly_cat.golems_arsenal.base.item.ExampleWeapon;
import a_silly_cat.golems_arsenal.base.item.KeySwordItem;
import a_silly_cat.golems_arsenal.base.item.ShenTongStaffItem;
import a_silly_cat.golems_arsenal.tech.item.GolemEnergyKatanaItem;
import a_silly_cat.golems_arsenal.tech.item.GolemEnergyHammerItem;
import a_silly_cat.golems_arsenal.tech.item.GolemTrackingMechanicalBowItem;
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

    public static final RegistryObject<GolemTrackingMechanicalBowItem> GOLEM_TRACKING_BOW = ITEMS.register(
            "golem_tracking_mechanical_bow",
            () -> new GolemTrackingMechanicalBowItem(new Item.Properties().stacksTo(1).fireResistant()));

    public static final RegistryObject<GolemWeaponUpgradeItem> GOLEM_ENERGY_UPGRADE = ITEMS.register(
            "golem_energy_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::modifier));

    public static final RegistryObject<GolemWeaponUpgradeItem> GOLEM_ENERGY_TECH_UPGRADE = ITEMS.register(
            "golem_energy_tech_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::techModifier));

    /** Hidden legacy item keeping old saves with the pre-rename id working. Behaves identically to the tech upgrade. */
    public static final RegistryObject<GolemWeaponUpgradeItem> LEGACY_GOLEM_ENERGY_HEAL_UPGRADE = ITEMS.register(
            "golem_energy_heal_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::techModifier));
    public static final RegistryObject<GolemWeaponUpgradeItem> GOLEM_BACKUP_ENERGY_UPGRADE = ITEMS.register(
            "golem_backup_energy_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::backupEnergyModifier));

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

    public static final RegistryObject<GolemWeaponUpgradeItem> GOLEM_DEATH_EXPLOSION_UPGRADE = ITEMS.register(
            "golem_death_explosion_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::deathExplosionModifier));

    public static final RegistryObject<GolemWeaponUpgradeItem> GOLEM_STANCE_UPGRADE = ITEMS.register(
            "golem_stance_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::stanceModifier));

    public static final RegistryObject<GolemWeaponUpgradeItem> GOLEM_STANCE_SUB_UPGRADE = ITEMS.register(
            "golem_stance_sub_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::stanceSubModifier));

    public static final RegistryObject<GolemWeaponUpgradeItem> GOLEM_GRAZE_UPGRADE = ITEMS.register(
            "golem_graze_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::grazeModifier));

    /** Example special-move upgrade: throws / orbits the key blade (needs the key sword). */
    public static final RegistryObject<GolemWeaponUpgradeItem> GOLEM_KEY_BLADE_SPIN_UPGRADE = ITEMS.register(
            "golem_key_blade_spin_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1,
                    GolemUpgrades::keyBladeSpinModifier));

    /**
     * Hidden legacy alias for the old train-upgrade item id, kept so holders/saved data from
     * earlier builds resolve instead of producing unidentified mappings. Behaves like the current
     * datapack-granted train modifier.
     */
    public static final RegistryObject<GolemWeaponUpgradeItem> LEGACY_GOLEM_TRAIN_UPGRADE = ITEMS.register(
            "golem_train_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1, GolemUpgrades::trainModifier));

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
                        output.accept(SHEN_TONG_STAFF.get());
                        output.accept(KEY_SWORD.get());
                        output.accept(GOLEM_ENERGY_UPGRADE.get());
                        output.accept(GOLEM_ENERGY_TECH_UPGRADE.get());
                        output.accept(GOLEM_BACKUP_ENERGY_UPGRADE.get());
                        output.accept(GOLEM_MAIN_WEAPON_UPGRADE.get());
                        output.accept(GOLEM_ALT_WEAPON_UPGRADE.get());
                        output.accept(GOLEM_RANGED_WEAPON_UPGRADE.get());
                        output.accept(GOLEM_SHIELD_WEAPON_UPGRADE.get());
                        output.accept(GOLEM_FULL_ONSLAUGHT_UPGRADE.get());
                        output.accept(GOLEM_DEATH_EXPLOSION_UPGRADE.get());
                        output.accept(GOLEM_STANCE_UPGRADE.get());
                        output.accept(GOLEM_STANCE_SUB_UPGRADE.get());
                        output.accept(GOLEM_GRAZE_UPGRADE.get());
                        output.accept(GOLEM_KEY_BLADE_SPIN_UPGRADE.get());
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
