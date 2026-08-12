package a_silly_cat.golems_arsenal.upgrade;

import dev.xkmc.modulargolems.content.item.upgrade.SimpleUpgradeItem;
import net.minecraft.world.item.Item;

public class GolemEnergyTechUpgradeItem extends SimpleUpgradeItem {
    public GolemEnergyTechUpgradeItem(Properties properties, int level) {
        super(properties, GolemUpgrades::techModifier, level, false);
    }
}
