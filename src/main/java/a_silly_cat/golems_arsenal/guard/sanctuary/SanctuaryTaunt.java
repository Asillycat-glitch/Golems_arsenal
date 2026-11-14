package a_silly_cat.golems_arsenal.guard.sanctuary;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 拦截成功时塞给相关玩家一张命名纸——纯提示，不带任何数值。
 *
 * <p>2026-11-07：按"检测到这类攻击和清除时给一张命名纸（已有就不再给）"实现。</p>
 */
public final class SanctuaryTaunt {

    public static final String PHRASE = "你可以试试下界合金剑或者更弱的武器的，反正它们都伤不到你";

    private SanctuaryTaunt() {
    }

    public static void give(ServerPlayer player) {
        try {
            if (player == null) {
                return;
            }
            for (ItemStack stack : player.getInventory().items) {
                if (stack.is(Items.PAPER) && stack.hasCustomHoverName()
                        && PHRASE.equals(stack.getHoverName().getString())) {
                    return;
                }
            }
            ItemStack paper = new ItemStack(Items.PAPER);
            paper.setHoverName(Component.literal(PHRASE));
            if (!player.getInventory().add(paper)) {
                player.drop(paper, false);
            }
        } catch (Throwable ignored) {
        }
    }
}
