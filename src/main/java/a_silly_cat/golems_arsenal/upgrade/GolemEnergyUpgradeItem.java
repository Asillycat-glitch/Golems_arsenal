package a_silly_cat.golems_arsenal.upgrade;

import dev.xkmc.modulargolems.content.item.upgrade.SimpleUpgradeItem;
import net.minecraft.world.item.Item;

public class GolemEnergyUpgradeItem extends SimpleUpgradeItem {
    public GolemEnergyUpgradeItem(Properties properties, int level) {
        super(properties, GolemUpgrades::modifier, level, false);
    }
}
