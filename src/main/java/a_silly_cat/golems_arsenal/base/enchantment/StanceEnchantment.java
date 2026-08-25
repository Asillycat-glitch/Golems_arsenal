package a_silly_cat.golems_arsenal.base.enchantment;

import a_silly_cat.golems_arsenal.base.item.ShenTongStaffItem;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;

/**
 * Player counterpart of the Black Monkey stance upgrade: while a player holds a Shen Tong Staff
 * enchanted with this, their own stance gauge fills from attacking/moving/being hurt, and right-
 * clicking charges all stacks to empower the next melee attack. No effect on golems. Added only
 * to the Shen Tong Staff by applying the meme-upgrade item on an anvil; not obtainable from the
 * enchanting table, loot or villager trades.
 */
public class StanceEnchantment extends Enchantment {
    public StanceEnchantment() {
        super(Rarity.VERY_RARE, EnchantmentCategory.WEAPON, new EquipmentSlot[]{EquipmentSlot.MAINHAND});
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
        return 2;
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

    @Override
    public boolean canApplyAtEnchantingTable(ItemStack stack) {
        return false;
    }

    @Override
    public boolean canEnchant(ItemStack stack) {
        return stack.getItem() instanceof ShenTongStaffItem;
    }
}
