package a_silly_cat.golems_arsenal.compat.golemmagicka;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.base.upgrade.GolemFlagModifier;
import a_silly_cat.golems_arsenal.base.upgrade.GolemUpgrades;
import a_silly_cat.golems_arsenal.base.upgrade.GolemWeaponUpgradeItem;
import com.tterrag.registrate.util.entry.RegistryEntry;
import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.init.registrate.GolemTypes;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Golem Magicka (奥法魔像) integration, registered only when that mod is loaded. Follows the same
 * soft-dependency pattern as Modular Golems itself: ModList gate + dedicated dispatch class that
 * references the optional mod's API directly (no reflection).
 */
public final class GolemMagickaCompat {
    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Golems_arsenal.MODID);

    public static final RegistryEntry<GolemFlagModifier> SCROLL = GolemUpgrades.REGISTRATE
            .generic(GolemTypes.MODIFIERS, "scroll",
                    () -> new GolemFlagModifier(StatFilterType.MASS, "upgrade.golems_arsenal.scroll"))
            .defaultLang()
            .register();

    public static final RegistryObject<GolemWeaponUpgradeItem> SCROLL_UPGRADE = ITEMS.register(
            "scroll_upgrade",
            () -> new GolemWeaponUpgradeItem(new Item.Properties().stacksTo(64), 1,
                    () -> SCROLL.get()));

    private GolemMagickaCompat() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        MinecraftForge.EVENT_BUS.register(GolemScrollRecordHandler.class);
    }
}
