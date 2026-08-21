package a_silly_cat.golems_arsenal.compat.golemmagicka;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.upgrade.GolemScrollModifier;
import a_silly_cat.golems_arsenal.upgrade.GolemScrollUpgradeItem;
import a_silly_cat.golems_arsenal.upgrade.GolemUpgrades;
import com.tterrag.registrate.util.entry.RegistryEntry;
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

    public static final RegistryEntry<GolemScrollModifier> SCROLL = GolemUpgrades.REGISTRATE
            .generic(GolemTypes.MODIFIERS, "golem_scroll", GolemScrollModifier::new)
            .defaultLang()
            .register();

    public static final RegistryObject<GolemScrollUpgradeItem> GOLEM_SCROLL_UPGRADE = ITEMS.register(
            "golem_scroll_upgrade",
            () -> new GolemScrollUpgradeItem(new Item.Properties().stacksTo(64),
                    () -> SCROLL.get()));

    private GolemMagickaCompat() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        MinecraftForge.EVENT_BUS.register(GolemScrollRecordHandler.class);
    }
}
