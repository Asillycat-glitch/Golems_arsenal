package a_silly_cat.golems_arsenal.item;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.upgrade.GolemEnergyTechModifier;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.item.equipments.MetalGolemWeaponItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class GolemEnergyKatanaItem extends MetalGolemWeaponItem {
    public static final int BASE_ENERGY_CAPACITY = 1_000_000;
    public static final int ENERGY_PER_ATTACK = 2_500;
    public static final int BASE_ATTACK_DAMAGE = 8;
    public static final float BASE_PERCENT_DAMAGE_BONUS = 0.25f;

    public static final ResourceLocation ATTACK_AMPLIFICATION_UNIT =
            new ResourceLocation(Golems_arsenal.MODID, "attack_amplification");
    public static final ResourceLocation ENERGY_CAPACITY_UNIT =
            new ResourceLocation(Golems_arsenal.MODID, "energy_capacity");

    private static final String ENERGY_TAG = "Energy";

    public GolemEnergyKatanaItem(Properties properties) {
        // Numeric bonuses use vanilla attributes; only the FE-powered percentage is event-driven.
        super(properties, BASE_ATTACK_DAMAGE, 0, 1.5f, 2.5f);
    }

    public boolean consumeAttackEnergy(ItemStack stack) {
        int cost = getEnergyPerAttack(stack);
        return stack.getCapability(ForgeCapabilities.ENERGY).map(storage -> {
            if (storage.extractEnergy(cost, true) < cost) {
                return false;
            }
            storage.extractEnergy(cost, false);
            return true;
        }).orElse(false);
    }

    public int getEnergyPerAttack(ItemStack stack) {
        return Config.ENERGY_KATANA_ATTACK_COST.get();
    }

    public int getEnergyCapacity(ItemStack stack) {
        long capacity = (long) Config.ENERGY_KATANA_CAPACITY.get()
                + (long) getUnitLevel(stack, ENERGY_CAPACITY_UNIT) * 250_000L;
        return (int) Math.min(capacity, Integer.MAX_VALUE);
    }

    /** Extra damage granted on a powered hit (energy consumed). */
    public float getPoweredHitBonus(ItemStack stack) {
        return Config.ENERGY_KATANA_SPECIAL_DAMAGE.get().floatValue();
    }

    /** ATTACK_DAMAGE attribute bonus: +5% per tech upgrade level while held. */
    public float getTechAttackPercent(int techLevel) {
        return (float) (techLevel * Config.TECH_DAMAGE_PER_LEVEL.get());
    }

    /** Extra FE consumed on top of the powered-hit cost when the lightning chain triggers. */
    public boolean consumeChainEnergy(ItemStack stack) {
        int cost = Config.ENERGY_KATANA_CHAIN_COST.get();
        return stack.getCapability(ForgeCapabilities.ENERGY).map(storage -> {
            if (storage.extractEnergy(cost, true) < cost) {
                return false;
            }
            storage.extractEnergy(cost, false);
            return true;
        }).orElse(false);
    }

    /** Lightning damage per chain strike. */
    public float getChainDamage() {
        return Config.ENERGY_KATANA_CHAIN_DAMAGE.get().floatValue();
    }

    /**
     * Lightning-chain skill condition: it is a tech-upgrade skill, so it needs the tech upgrade
     * installed plus the extra FE cost. Future katana skills should add their own {@code canXxx}
     * methods instead of reusing the tech level for gating.
     */
    public boolean canTriggerChain(AbstractGolemEntity<?, ?> golem, ItemStack stack) {
        return GolemEnergyTechModifier.hasTechUpgrade(golem) && consumeChainEnergy(stack);
    }

    public int getUnitLevel(ItemStack stack, ResourceLocation unitId) {
        return WeaponUpgradeData.getLevel(stack, unitId);
    }

    public int addUnitLevels(ItemStack stack, ResourceLocation unitId, int amount, int configuredMaxLevel) {
        return WeaponUpgradeData.addLevels(stack, unitId, amount, configuredMaxLevel);
    }

    public void setUnitLevel(ItemStack stack, ResourceLocation unitId, int level) {
        WeaponUpgradeData.setLevel(stack, unitId, level);
    }

    public int getTotalUnitLevels(ItemStack stack) {
        return WeaponUpgradeData.getTotalLevels(stack);
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return true;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return Math.round(13.0f * getStoredEnergy(stack) / getEnergyCapacity(stack));
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return Mth.hsvToRgb(0.48f, 1.0f, 1.0f);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> list, TooltipFlag flag) {
        list.add(Component.translatable("tooltip.golems_arsenal.energy",
                getStoredEnergy(stack), getEnergyCapacity(stack)).withStyle(ChatFormatting.AQUA));
        list.add(Component.translatable("tooltip.golems_arsenal.energy_katana.damage",
                Math.round(getPoweredHitBonus(stack) * 100)).withStyle(ChatFormatting.GRAY));
        list.add(Component.translatable("tooltip.golems_arsenal.energy_katana.tech_attack",
                Math.round(Config.TECH_DAMAGE_PER_LEVEL.get() * 100)).withStyle(ChatFormatting.GRAY));
        list.add(Component.translatable("tooltip.golems_arsenal.energy_katana.cost",
                getEnergyPerAttack(stack)).withStyle(ChatFormatting.DARK_GRAY));
        list.add(Component.translatable("tooltip.golems_arsenal.energy_katana.charge",
                Config.ENERGY_KATANA_CHARGE_DURATION.get() / 20).withStyle(ChatFormatting.GRAY));
        list.add(Component.translatable("tooltip.golems_arsenal.units",
                getTotalUnitLevels(stack)).withStyle(ChatFormatting.DARK_AQUA));
        super.appendHoverText(stack, level, list, flag);
    }

    @Override
    public @Nullable ICapabilityProvider initCapabilities(ItemStack stack, @Nullable CompoundTag nbt) {
        return new EnergyProvider(stack);
    }

    private static int getStoredEnergy(ItemStack stack) {
        return Math.max(0, stack.getOrCreateTag().getInt(ENERGY_TAG));
    }

    private static final class EnergyProvider implements ICapabilityProvider {
        private final LazyOptional<IEnergyStorage> energy;

        private EnergyProvider(ItemStack stack) {
            energy = LazyOptional.of(() -> new KatanaEnergyStorage(stack));
        }

        @Override
        public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> capability,
                                                          @Nullable Direction side) {
            return capability == ForgeCapabilities.ENERGY ? energy.cast() : LazyOptional.empty();
        }
    }

    private static final class KatanaEnergyStorage implements IEnergyStorage {
        private final ItemStack stack;

        private KatanaEnergyStorage(ItemStack stack) {
            this.stack = stack;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            int accepted = Math.min(Math.max(maxReceive, 0), getMaxEnergyStored() - getEnergyStored());
            if (!simulate && accepted > 0) {
                setEnergy(getEnergyStored() + accepted);
            }
            return accepted;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            int extracted = Math.min(Math.max(maxExtract, 0), getEnergyStored());
            if (!simulate && extracted > 0) {
                setEnergy(getEnergyStored() - extracted);
            }
            return extracted;
        }

        @Override
        public int getEnergyStored() {
            return GolemEnergyKatanaItem.getStoredEnergy(stack);
        }

        @Override
        public int getMaxEnergyStored() {
            return ((GolemEnergyKatanaItem) stack.getItem()).getEnergyCapacity(stack);
        }

        @Override
        public boolean canExtract() {
            return true;
        }

        @Override
        public boolean canReceive() {
            return true;
        }

        private void setEnergy(int energy) {
            stack.getOrCreateTag().putInt(ENERGY_TAG, Mth.clamp(energy, 0, getMaxEnergyStored()));
        }
    }
}
