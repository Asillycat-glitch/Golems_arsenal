package a_silly_cat.golems_arsenal.tech.orbit;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.List;

/** 激光发射台的方块物品：只多一条用途提示。 */
public class LaserConsoleItem extends BlockItem {

    public LaserConsoleItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.golems_arsenal.laser_console.tip")
                .withStyle(ChatFormatting.GRAY));
    }
}
