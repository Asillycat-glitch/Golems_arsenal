package a_silly_cat.golems_arsenal.tech.orbit;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 火箭界面的 {@code MenuType} 注册。
 *
 * <p>用原版 Forge 的 {@code DeferredRegister} 而不是 l2 的 REGISTRATE 构建器：
 * 那套构建器需要一整套 registrate 基础设施（l2backpack 有，我们没有），
 * 而这里只需要一个菜单类型，不值得为它引入那套东西。l2 的框架只要求
 * {@code BaseContainerMenu} 的构造器收到一个 {@code MenuType}，怎么注册它并不关心。
 *
 * <p>{@link IForgeMenuType} 让服务端可以在打开界面时往网络包里塞额外数据——
 * 我们塞的是火箭实体的 id，客户端据此找回同一个实体。
 */
public final class ModMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, Golems_arsenal.MODID);

    public static final RegistryObject<MenuType<RocketMenu>> ROCKET =
            MENUS.register("rocket", () -> IForgeMenuType.create(RocketMenu::new));

    /** 激光发射台的配置界面（无物品槽，纯配置终端）。 */
    public static final RegistryObject<MenuType<LaserConsoleMenu>> LASER_CONSOLE =
            MENUS.register("laser_console", () -> IForgeMenuType.create(LaserConsoleMenu::new));

    private ModMenus() {
    }

    public static void register(IEventBus modEventBus) {
        MENUS.register(modEventBus);
    }
}
