package a_silly_cat.golems_arsenal.base.upgrade;

import dev.xkmc.modulargolems.content.item.upgrade.UpgradeItem;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import dev.xkmc.modulargolems.content.modifier.base.ModifierInstance;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.function.Supplier;

/**
 * Special-move upgrade item (mutually exclusive + slot-free).
 * <ul>
 *   <li>{@link #consumesSlot()} is {@code false}, following Modular Golems' own slot template
 *   pattern ({@code AddSlotTemplate}), so special moves never eat into the golem's upgrade
 *   slots.</li>
 *   <li>Only one special-move upgrade can be installed at a time; installing a second one is
 *   rejected by {@code GolemUpgradeHandlerMixin} in the golem workbench.</li>
 *   <li>Stays removable like a normal upgrade ({@code UpgradeItem}), so players can swap
 *   special moves.</li>
 * </ul>
 */
public class SpecialMoveUpgradeItem extends UpgradeItem {
    private final Supplier<? extends GolemModifier> sup;

    public SpecialMoveUpgradeItem(Item.Properties properties,
                                  Supplier<? extends GolemModifier> sup) {
        super(properties, false);
        this.sup = sup;
    }

    @Override
    public List<ModifierInstance> get() {
        return List.of(new ModifierInstance(sup.get(), 1));
    }

    @Override
    public boolean consumesSlot() {
        return false;
    }
}
