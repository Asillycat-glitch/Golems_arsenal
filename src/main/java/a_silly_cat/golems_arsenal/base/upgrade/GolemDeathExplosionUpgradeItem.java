package a_silly_cat.golems_arsenal.base.upgrade;

import dev.xkmc.modulargolems.content.item.upgrade.SimpleUpgradeItem;
import net.minecraft.world.item.Item;

public class GolemDeathExplosionUpgradeItem extends SimpleUpgradeItem {
    public GolemDeathExplosionUpgradeItem(Properties properties, int level) {
        super(properties, GolemUpgrades::deathExplosionModifier, level, false);
    }
}
