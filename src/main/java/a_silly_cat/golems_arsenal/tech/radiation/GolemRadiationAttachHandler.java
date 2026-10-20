package a_silly_cat.golems_arsenal.tech.radiation;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

/**
 * 给傀儡挂上 Mekanism 的 {@code IRadiationEntity} 能力（只在装了 Mekanism 时生效）。
 *
 * <p>这解决"辐射屏蔽升级没有意义"的根本问题：实测 Mekanism 只把该能力挂给玩家
 * （{@code DefaultRadiationEntity.Provider} 仅被 {@code CommonPlayerTracker} 引用），
 * 所以普通生物与傀儡默认不吃辐射。不挂这个能力，屏蔽升级就只剩"降低发电机出力"这一个作用，
 * 是个纯负收益的升级。
 *
 * <p><b class="note">为什么用事件而不是 mixin 的 {@code implements}。</b>
 * Mekanism 的 {@code CommonPlayerTracker} 也监听同一个事件并只处理 {@code Player}，
 * 而我们只处理 {@code AbstractGolemEntity}，两边不相交、没有优先级冲突。
 * 相比之下"在 mixin 配置里写 {@code implements: IRadiationEntity}"要求 Mixin 的
 * 条件求值恰好发生在能力接口可加载之后，风险大得多。
 *
 * <p><b class="note">门控方式。</b>类加载发生在第一次执行到引用处，所以
 * {@link RadiationEntityProvider} 只在 {@code isLoaded("mekanism")} 为真时才会被解析——
 * 没装 MEK 时这个方法在第一步就返回了，JVM 永远不会去加载那个引用了 Mekanism 类的类型。
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID)
public final class GolemRadiationAttachHandler {

    private static final ResourceLocation RADIATION_ID =
            new ResourceLocation(Golems_arsenal.MODID, "radiation");

    @SubscribeEvent
    public static void onAttachCapabilities(AttachCapabilitiesEvent<net.minecraft.world.entity.Entity> event) {
        if (!(event.getObject() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        // 没装 Mekanism 时直接退出：下面那行引用 RadiationEntityProvider，类加载就发生在这里。
        if (!ModList.get().isLoaded("mekanism")) {
            return;
        }
        attach(golem, event);
    }

    private static void attach(AbstractGolemEntity<?, ?> golem, AttachCapabilitiesEvent<?> event) {
        // Already irradiated (another addon, or a capability copied over): leave it alone.
        if (golem.getCapability(mekanism.common.capabilities.Capabilities.RADIATION_ENTITY).isPresent()) {
            return;
        }
        RadiationEntityProvider provider = new RadiationEntityProvider();
        event.addCapability(RADIATION_ID, provider);
        // Standard Forge cleanup, mirroring how WeaponEventHandler.attachEnergy does it for FE.
        event.addListener(provider::invalidate);
    }

    private GolemRadiationAttachHandler() {
    }
}
