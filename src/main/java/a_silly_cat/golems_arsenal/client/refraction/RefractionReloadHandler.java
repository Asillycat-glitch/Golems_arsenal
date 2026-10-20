package a_silly_cat.golems_arsenal.client.refraction;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 资源重载（F3+T）后把后处理管线标记为“需要重建”。
 *
 * <p>为什么需要这个：{@code EffectInstance} 的 program 是随资源包一起加载/释放的，
 * 如果玩家在测试时改了 {@code warp.fsh} 再按 F3+T，旧的 {@code PostPass} 里握着的是已经失效的
 * program id。这里只置一个标志，真正的关闭/重建放到渲染线程的 {@code ensure()} 里做
 * （GL 对象只能在渲染线程释放）。
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RefractionReloadHandler {

    private RefractionReloadHandler() {
    }

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new SimplePreparableReloadListener<Void>() {
            @Override
            protected Void prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
                return null;
            }

            @Override
            protected void apply(Void unused, ResourceManager resourceManager, ProfilerFiller profiler) {
                RefractionPostFx.onResourceReload();
            }
        });
    }
}
