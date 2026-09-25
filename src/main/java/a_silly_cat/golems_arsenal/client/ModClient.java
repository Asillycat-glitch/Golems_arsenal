package a_silly_cat.golems_arsenal.client;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.init.ModEntities;
import a_silly_cat.golems_arsenal.init.ModItems;
import a_silly_cat.golems_arsenal.tech.energy.ItemEnergyStorage;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraftforge.api.distmarker.Dist;
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
        // Z 光剑：没电时用"只有把手"的模型，有电时才是完整剑刃。
        // 物品属性值只能是浮点，所以这里用 0（空） / 1（有电）两档，配合模型里的 overrides。
        event.enqueueWork(() -> ItemProperties.register(ModItems.ZERO_ENERGY_SWORD.get(),
                Golems_arsenal.id("charged"),
                (stack, level, entity, seed) -> ItemEnergyStorage.getStored(stack) > 0 ? 1.0F : 0.0F));
    }

    private ModClient() {
    }
}
