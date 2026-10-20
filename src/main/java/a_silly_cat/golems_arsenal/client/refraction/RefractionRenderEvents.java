package a_silly_cat.golems_arsenal.client.refraction;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 把「几何特效」和「屏幕空间后处理」接到两个正确的时机上。
 *
 * <p><b>AFTER_PARTICLES</b>：世界渲染还没结束、深度仍然有效（fabulous 下 AFTER_LEVEL 的深度
 * 已被透明链覆盖）。球壳/粒子流在这里画：既能用真实深度挡住地面以下的部分，
 * 又不依赖"AFTER_LEVEL 时 modelview 还在不在"。
 *
 * <p><b>AFTER_LEVEL</b>：画面已经合成完毕，只跑折射后处理。
 * 几何在它之前画，所以会一起被轻微折射，读起来像"在空间里"，而不是盖在上面的一张图。
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID, value = Dist.CLIENT)
public final class RefractionRenderEvents {

    private RefractionRenderEvents() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!RefractionFx.active()) {
            return;
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            RefractionWorldFx.render(event);
            RefractionPostFx.captureDepth(event);
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            RefractionPostFx.render(event);
        }
    }
}
