package a_silly_cat.golems_arsenal;

import a_silly_cat.golems_arsenal.init.ModItems;
import a_silly_cat.golems_arsenal.init.ModBlocks;
import a_silly_cat.golems_arsenal.init.ModBlockEntities;
import a_silly_cat.golems_arsenal.init.ModEntities;
import a_silly_cat.golems_arsenal.init.GolemEffects;
import a_silly_cat.golems_arsenal.init.ModAttributes;
import a_silly_cat.golems_arsenal.init.ModEnchantments;
import a_silly_cat.golems_arsenal.init.ModRecipeSerializers;
import a_silly_cat.golems_arsenal.init.ModNetwork;
import a_silly_cat.golems_arsenal.compat.CompatDispatch;
import a_silly_cat.golems_arsenal.base.upgrade.GolemUpgrades;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Golems_arsenal.MODID)
public class Golems_arsenal {
    public static final String MODID = "golems_arsenal";
    public static final Logger LOGGER = LoggerFactory.getLogger(Golems_arsenal.class);

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MODID, path);
    }

    public Golems_arsenal() {
        // 早鸟调用：这里还读不到配置文件（registerConfig 在本构造函数末尾，Forge 加载文件更晚），
        // 所以 install() 只会记一行 defer 然后返回，真正的安装在 ModConfigEvent 里做。
        // 保留这一句是为了"配置刚好已经加载"的边缘情况（热重载/测试环境）。
        a_silly_cat.golems_arsenal.guard.sanctuary.AgentBootstrap.install();
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        GolemUpgrades.register();
        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModItems.register(modEventBus);
        ModEntities.register(modEventBus);
        // 火箭界面。注册本身不引用 MEK 的类，所以无条件注册是安全的；
        // 燃料槽里的 MEK 判断在 RocketFuels 里，那个类的加载受门控。
        a_silly_cat.golems_arsenal.tech.orbit.ModMenus.register(modEventBus);
        GolemEffects.register(modEventBus);
        // 指令升级的九个效果：它们和 GolemEffects 分开注册，因为生命周期不同 ——
        // GolemEffects 里的是"某把武器打出来的"减益，这里是"傀儡获得了什么"的状态。
        a_silly_cat.golems_arsenal.init.CommandEffects.register(modEventBus);
        ModAttributes.register(modEventBus);
        ModEnchantments.register(modEventBus);
        ModRecipeSerializers.register(modEventBus);
        ModNetwork.register();
        modEventBus.addListener(ModAttributes::modifyAttributes);
        CompatDispatch.registerCommon(modEventBus);
        // fantasy_ending 的 true damage 不走 Forge 伤害事件；只有装了 fantasy 时才加载
        // 这个引用 CatchActuallyHurt0Event 的适配器类，避免无 fantasy 环境类加载失败。
        if (net.minecraftforge.fml.ModList.get().isLoaded("fantasy_ending")) {
            try {
                Class.forName("a_silly_cat.golems_arsenal.compat.fantasy.FantasyTrueDamageAdapter")
                        .getMethod("register")
                        .invoke(null);
            } catch (Throwable t) {
                Golems_arsenal.LOGGER.warn("fantasy true-damage adapter not registered: {}", t.toString());
            }
        }
        // Z 光剑的近战穿甲：向 l2damagetracker 注册一个监听器（和傀儡地牢遗迹锻锤同一条通道）。
        // l2damagetracker 由本家/莱特兰以 jarjar 形式提供，理论上一定在；仍然兜一层，避免它不是
        // 独立依赖时把整个 mod 加载带崩。
        try {
            a_silly_cat.golems_arsenal.compat.l2damagetracker.ZeroSwordAttackListener.register();
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("Z sword armor-pierce listener not registered: {}", t.toString());
        }
        // 伤害减伤诊断探针：只读，绝不修改任何伤害。用来量"打不动"到底是哪一层削掉的。
        try {
            a_silly_cat.golems_arsenal.compat.l2damagetracker.DamageReductionProbe.register();
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("damage-reduction probe not registered: {}", t.toString());
        }
        // 伤害返还：主手武器由数据包授予返还比例（data/<ns>/golems_arsenal_restitution/*.json），
        // 傀儡自身的 end_restitution 属性再往上加，统一夹到 0.8。
        try {
            a_silly_cat.golems_arsenal.compat.l2damagetracker.RestitutionData.register();
            a_silly_cat.golems_arsenal.compat.l2damagetracker.DamageRestitutionListener.register();
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("damage-restitution listener not registered: {}", t.toString());
        }
        // 绝炎冲保底：秘奥义 5 连爆（damage type = mystic_blast）每一爆至少打出 base × 0.5。
        // priority 取在"每击上限"类之前，所以保底不顶穿限伤；详见该类注释。
        try {
            a_silly_cat.golems_arsenal.compat.l2damagetracker.MysticBlastFloorListener.register();
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("mystic-blast floor listener not registered: {}", t.toString());
        }
        // 莫厄伤害联动（路线 b）：装了「莫厄余烬」的傀儡，攻击被标记为莫厄伤害。
        // 走反射注册 jerotes 的 JerotesMerorDamageEvent（它不是编译期依赖），
        // 因此这里只按"mod 在不在场"决定要不要尝试。
        if (net.minecraftforge.fml.ModList.get().isLoaded("jerotes")) {
            a_silly_cat.golems_arsenal.compat.jerotes.MerorDamageCompat.register();
        }
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

}
