package a_silly_cat.golems_arsenal.upgrade;

import dev.xkmc.modulargolems.content.item.upgrade.IUpgradeItem;
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
 * item is a plain marker upgrade that enables recording/casting.
 */
public class GolemScrollUpgradeItem extends Item implements IUpgradeItem {
    private final Supplier<? extends GolemModifier> sup;

    public GolemScrollUpgradeItem(Properties properties, Supplier<? extends GolemModifier> sup) {
        super(properties);
        this.sup = sup;
    }

    @Override
    public List<ModifierInstance> get() {
        return List.of(new ModifierInstance(sup.get(), 1));
    }

    // consumesSlot/canBeRemoved were added to IUpgradeItem in Modular Golems 2.7.x; the project
    // compiles against 2.6.34 where they do not exist yet, so no @Override here.
    public boolean consumesSlot() {
        return true;
    }

    public boolean canBeRemoved() {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> list, TooltipFlag flag) {
        for (ModifierInstance ins : get()) {
            list.add(ins.mod().getTooltip(ins.level()));
            list.addAll(ins.mod().getDetail(ins.level()));
        }
    }
}
