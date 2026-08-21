package a_silly_cat.golems_arsenal.upgrade;

import dev.xkmc.modulargolems.content.item.upgrade.UpgradeItem;
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
 * Scroll upgrade item. Only registered when Golem Magicka is loaded. The recorded spell itself
 * is stored on the golem (Modular Golems only keeps upgrade item ids on installation), so this
 * item is a plain marker upgrade that enables recording/casting. Extends {@link UpgradeItem}
 * (not just {@code IUpgradeItem}) so it can be installed in the golem workbench upgrade slots.
 */
public class GolemScrollUpgradeItem extends UpgradeItem {
    private final Supplier<? extends GolemModifier> sup;

    public GolemScrollUpgradeItem(Properties properties, Supplier<? extends GolemModifier> sup) {
        super(properties, false);
        this.sup = sup;
    }

    @Override
    public List<ModifierInstance> get() {
        return List.of(new ModifierInstance(sup.get(), 1));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> list, TooltipFlag flag) {
        for (ModifierInstance ins : get()) {
            list.add(ins.mod().getTooltip(ins.level()));
            list.addAll(ins.mod().getDetail(ins.level()));
        }
    }
}
