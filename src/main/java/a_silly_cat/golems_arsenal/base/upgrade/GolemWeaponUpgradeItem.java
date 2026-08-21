package a_silly_cat.golems_arsenal.base.upgrade;

import dev.xkmc.modulargolems.content.item.upgrade.SimpleUpgradeItem;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.world.item.Item;

import java.util.function.Supplier;

/** Generic level-1 weapon upgrade item; the modifier decides which effect category is active. */
public class GolemWeaponUpgradeItem extends SimpleUpgradeItem {
    public GolemWeaponUpgradeItem(Item.Properties properties, int level, Supplier<GolemModifier> modifier) {
        super(properties, modifier, level, false);
    }
}
