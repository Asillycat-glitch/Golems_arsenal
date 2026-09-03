package a_silly_cat.golems_arsenal.base.upgrade;

import dev.xkmc.modulargolems.content.item.upgrade.SimpleUpgradeItem;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import dev.xkmc.modulargolems.content.modifier.base.ModifierInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/**
 * Generic single-modifier upgrade item (level-1 weapon upgrades, the scroll upgrade, ...).
 * The linked modifier decides which effect category is active; the hover text shows the
 * modifier's tooltip and details.
 */
public class GolemWeaponUpgradeItem extends SimpleUpgradeItem {
    public GolemWeaponUpgradeItem(Item.Properties properties, int level, Supplier<GolemModifier> modifier) {
        super(properties, modifier, level, false);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> list,
                                TooltipFlag flag) {
        for (ModifierInstance ins : get()) {
            list.add(ins.mod().getTooltip(ins.level()));
            list.addAll(ins.mod().getDetail(ins.level()));
        }
    }
}
