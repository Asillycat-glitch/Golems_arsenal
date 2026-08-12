package a_silly_cat.golems_arsenal.enchantment;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;

/**
 * Player counterpart of the golem full-onslaught upgrade: while wearing an enchanted chestplate,
 * armor above the threshold grants melee attack and percentage TACZ gun damage. Not obtainable
 * from the enchanting table, loot or villager trades; added to chestplates by applying a
 * meme-upgrade item on an anvil.
 */
public class FullOnslaughtEnchantment extends Enchantment {
    public FullOnslaughtEnchantment() {
        super(Rarity.VERY_RARE, EnchantmentCategory.ARMOR_CHEST, new EquipmentSlot[]{EquipmentSlot.CHEST});
    }

    @Override
    public int getMinCost(int level) {
        return 30;
    }

    @Override
    public int getMaxCost(int level) {
        return 60;
    }

    @Override
    public int getMaxLevel() {
        return 1;
    }

    @Override
    public boolean isTreasureOnly() {
        return true;
    }

    @Override
    public boolean isTradeable() {
        return false;
    }

    @Override
    public boolean isDiscoverable() {
        return false;
    }
}
