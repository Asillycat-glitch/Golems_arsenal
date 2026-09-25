package a_silly_cat.golems_arsenal.client;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.base.item.ShenTongStaffItem;
import a_silly_cat.golems_arsenal.init.ModEnchantments;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;
import net.minecraftforge.api.distmarker.Dist;

/**
 * Client HUD for the player's stance gauge (Black Monkey stance enchantment on the Shen Tong
 * Staff): a progress bar with one cell per stance stack, filled from server-synced data.
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID, bus = Bus.MOD, value = Dist.CLIENT)
public final class StanceHudOverlay {
    private StanceHudOverlay() {
    }

    @SubscribeEvent
    public static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.EXPERIENCE_BAR.id(), "stance",
                StanceHudOverlay::render);
    }

    private static void render(ForgeGui gui, GuiGraphics graphics, float partialTick,
                               int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        ItemStack stack = mc.player.getMainHandItem();
        if (!(stack.getItem() instanceof ShenTongStaffItem)) {
            return;
        }
        int level = stack.getEnchantmentLevel(ModEnchantments.STANCE.get());
        if (level <= 0) {
            return;
        }
        int stacks = ClientStanceData.stacks;
        double progress = ClientStanceData.progress;
        boolean charged = ClientStanceData.charged;
        if (stacks <= 0 && progress <= 0 && !charged) {
            return;
        }
        int maxStacks = level >= 2 ? 4 : 3;
        double gauge = Config.STANCE_GAUGE_MAX.get();
        int barWidth = 82;
        int barHeight = 4;
        int x0 = 4;
        int y = height - 32;
        graphics.fill(x0, y, x0 + barWidth, y + barHeight, 0x80000000);
        int fill = (int) (barWidth * Math.min(1.0, progress / Math.max(1, gauge)));
        graphics.fill(x0 + 1, y + 1, x0 + 1 + fill, y + barHeight - 1,
                charged ? 0xFFE04040 : 0xFFE0B000);
        int cell = 6;
        int gap = 2;
        int total = maxStacks * cell + (maxStacks - 1) * gap;
        int cx = width / 2 - total / 2;
        for (int i = 0; i < maxStacks; i++) {
            int x = cx + i * (cell + gap);
            graphics.fill(x, y - cell - 2, x + cell, y - 2,
                    i < stacks ? 0xFFE0B000 : 0x60000000);
        }
    }
}
