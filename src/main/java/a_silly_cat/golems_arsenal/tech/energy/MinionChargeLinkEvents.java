package a_silly_cat.golems_arsenal.tech.energy;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 每 10 tick 扫一遍玩家背包/副手/盔甲槽里的仆从充能链接。
 *
 * <p>为什么不用 {@code Item#inventoryTick}：它不覆盖盔甲槽，而 Powah/通量网络都可能
 * 给盔甲槽里的物品充电。统一扫 41 个槽位，代价很小（每 10 tick / 每个玩家一次）。
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MinionChargeLinkEvents {

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Player player = event.player;
        if (player.level().isClientSide || player.tickCount % 10 != 0) {
            return;
        }
        for (ItemStack stack : player.getInventory().items) {
            tick(stack, player);
        }
        for (ItemStack stack : player.getInventory().offhand) {
            tick(stack, player);
        }
        for (ItemStack stack : player.getInventory().armor) {
            tick(stack, player);
        }
    }

    private static void tick(ItemStack stack, Player player) {
        if (!stack.isEmpty() && stack.getItem() instanceof MinionChargeLinkItem) {
            MinionChargeLinkItem.distribute(stack, player);
        }
    }

    private MinionChargeLinkEvents() {
    }
}
