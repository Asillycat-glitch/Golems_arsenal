package a_silly_cat.golems_arsenal.base.upgrade;

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
 * Repeatable expansion template item, mirroring Modular Golems' {@code AddSlotTemplate}:
 * <ul>
 *   <li>{@link #consumesSlot()} is {@code false} - installing it does not consume an upgrade slot</li>
 *   <li>{@link #canBeRemoved()} is {@code false} - it cannot be taken off the golem again</li>
 *   <li>{@link #get()} returns one level of the linked modifier per application</li>
 * </ul>
 * Applying the same item multiple times raises the modifier's total level (capped at maxLevel),
 * which is what makes the expansion repeatable.
 */
public class RepeatableExpansionItem extends Item implements IUpgradeItem {

    private final Supplier<? extends GolemModifier> sup;

    public RepeatableExpansionItem(Properties properties, Supplier<? extends GolemModifier> sup) {
        super(properties);
        this.sup = sup;
    }

    // NOTE: consumesSlot/canBeRemoved were added to IUpgradeItem in Modular Golems 2.7.x.
    // The project compiles against 2.6.34 (where they do not exist yet), so no @Override here;
    // at runtime on 2.7.x these correctly override the interface default methods.
    public boolean consumesSlot() {
        return false;
    }

    public boolean canBeRemoved() {
        return false;
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
