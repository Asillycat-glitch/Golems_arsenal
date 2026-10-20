package a_silly_cat.golems_arsenal.client;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.init.ModEntities;
import a_silly_cat.golems_arsenal.init.ModItems;
import a_silly_cat.golems_arsenal.tech.energy.ItemEnergyStorage;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.xkmc.modulargolems.content.entity.metalgolem.MetalGolemRenderer;
import dev.xkmc.modulargolems.init.registrate.GolemTypes;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Client-side registrations. The key blade reuses the vanilla item renderer, which already spins
 * the item model while it flies, so no custom renderer class is needed.
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID, bus = Mod.EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT)
public final class ModClient {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        EntityRenderers.register(ModEntities.KEY_BLADE.get(), KeyBladeRenderer::new);
        EntityRenderers.register(ModEntities.PHANTOM_BLADE.get(), GenmuZeroRenderer::new);
        EntityRenderers.register(ModEntities.LIGHT_PILLAR.get(), BeaconPillarRenderer::new);
        EntityRenderers.register(ModEntities.ORBITAL_BEAM.get(), OrbitalBeamRenderer::new);
        EntityRenderers.register(ModEntities.GUIDANCE_BEAM.get(), GuidanceBeamRenderer::new);
        EntityRenderers.register(ModEntities.ORBITAL_SIGIL.get(), OrbitalSigilRenderer::new);
        EntityRenderers.register(ModEntities.ORBITAL_IMPACT.get(), OrbitalImpactRenderer::new);
        EntityRenderers.register(ModEntities.ORBITAL_PROJECTILE.get(), OrbitalProjectileRenderer::new);
        // 火箭：实体类型注册了就必须注册渲染器，漏了会在客户端渲染时 NPE 崩掉（踩过一次）。
        // 目前画的是按真实模型尺寸的线框盒，等 .bbmodel 转成 Minecraft 模型后再换。
        EntityRenderers.register(ModEntities.ROCKET.get(), RocketRenderer::new);
        // 火箭界面。用 enqueueWork 是因为 MenuScreens.register 要在客户端初始化完成后做。
        event.enqueueWork(() -> MenuScreens.register(
                a_silly_cat.golems_arsenal.tech.orbit.ModMenus.ROCKET.get(), RocketScreen::new));
        // Z 光剑：没电时用"只有把手"的模型，有电时才是完整剑刃。
        // 物品属性值只能是浮点，所以这里用 0（空） / 1（有电）两档，配合模型里的 overrides。
        event.enqueueWork(() -> ItemProperties.register(ModItems.ZERO_ENERGY_SWORD.get(),
                Golems_arsenal.id("charged"),
                (stack, level, entity, seed) -> ItemEnergyStorage.getStored(stack) > 0 ? 1.0F : 0.0F));
    }

    /**
     * 给本家的<b>大型</b>傀儡渲染器挂上三连击刀光层。
     * <p>
     * 走 Forge 的 {@code AddLayers} 而不是 mixin：这个事件专为"给已有渲染器加层"而设，
     * 而 {@code LivingEntityRenderer.addLayer} 在我们编译用的 Forge jar 里是 public，
     * 所以既不用 mixin，也不用碰本仓库那份<b>手工维护</b>的 refmap。
     * 只挂大型傀儡 → 人形 / 犬形天然不受影响。
     */
    @SubscribeEvent
    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        MetalGolemRenderer renderer = event.getRenderer(GolemTypes.ENTITY_GOLEM.get());
        if (renderer != null) {
            renderer.addLayer(new GolemComboLayer(renderer));
        }
    }

    /**
     * 火箭的模型层 —— 直接来自 Blockbench 导出的 {@link RocketModel#createBodyLayer()}。
     *
     * <p>这条路径换过三次：先是用 {@code RenderType.lines()} 画线框（Embeddium 下看不见），
     * 再试从 {@code .bbmodel} 生成 JSON（字段名与 UV 单位都错，加载失败），
     * 最后用 Blockbench 直接导出的 Java 模型代码——4 条着陆腿的旋转用 JSON 本来也表达不出来。
     */
    @SubscribeEvent
    public static void onRegisterLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(RocketModel.LAYER_LOCATION, RocketModel::createBodyLayer);
    }

    private ModClient() {
    }
}
