package a_silly_cat.golems_arsenal.tech.upgrade;

import a_silly_cat.golems_arsenal.base.upgrade.GolemUpgrades;
import dev.xkmc.modulargolems.content.item.upgrade.SimpleUpgradeItem;
import net.minecraft.world.item.Item;

public class GolemEnergyUpgradeItem extends SimpleUpgradeItem {
    public GolemEnergyUpgradeItem(Properties properties, int level) {
        super(properties, GolemUpgrades::modifier, level, false);
    }
}
