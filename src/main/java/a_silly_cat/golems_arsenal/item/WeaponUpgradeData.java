package a_silly_cat.golems_arsenal.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Shared, unbounded per-unit level storage for future upgrade items. */
public final class WeaponUpgradeData {
    private static final String UNIT_LEVELS_TAG = "UnitLevels";

    private WeaponUpgradeData() {
    }

    public static int getLevel(ItemStack stack, ResourceLocation unitId) {
        return Math.max(0, stack.getOrCreateTag().getCompound(UNIT_LEVELS_TAG).getInt(unitId.toString()));
    }

    public static int addLevels(ItemStack stack, ResourceLocation unitId, int amount, int configuredMaxLevel) {
        int oldLevel = getLevel(stack, unitId);
        if (amount <= 0) {
            return oldLevel;
        }
        long requested = (long) oldLevel + amount;
        int maxLevel = configuredMaxLevel <= 0 ? Integer.MAX_VALUE : configuredMaxLevel;
        int newLevel = (int) Math.min(requested, maxLevel);
        setLevel(stack, unitId, newLevel);
        return newLevel;
    }

    public static void setLevel(ItemStack stack, ResourceLocation unitId, int level) {
        CompoundTag root = stack.getOrCreateTag();
        CompoundTag levels = root.getCompound(UNIT_LEVELS_TAG);
        if (level <= 0) {
            levels.remove(unitId.toString());
        } else {
            levels.putInt(unitId.toString(), level);
        }
        root.put(UNIT_LEVELS_TAG, levels);
    }

    public static int getTotalLevels(ItemStack stack) {
        CompoundTag levels = stack.getOrCreateTag().getCompound(UNIT_LEVELS_TAG);
        long total = 0;
        for (String key : levels.getAllKeys()) {
            total += Math.max(0, levels.getInt(key));
        }
        return (int) Math.min(total, Integer.MAX_VALUE);
    }
}
