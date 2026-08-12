package a_silly_cat.golems_arsenal.item;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.upgrade.GolemEnergyTechModifier;
import com.google.common.collect.ImmutableMultimap;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.entity.metalgolem.MetalGolemEntity;
import dev.xkmc.modulargolems.content.item.ranged.MetalGolemMechaBowItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
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
import java.util.UUID;
import java.util.function.Consumer;

public class GolemTrackingMechanicalBowItem extends MetalGolemMechaBowItem {
    public static final int BASE_ENERGY_CAPACITY = 1_000_000;
    public static final int BASE_DRAW_STRENGTH = 20;
    public static final ResourceLocation TRACKING_UNIT =
            new ResourceLocation(Golems_arsenal.MODID, "tracking");
    public static final ResourceLocation ENERGY_CAPACITY_UNIT =
            new ResourceLocation(Golems_arsenal.MODID, "energy_capacity");

    private static final String ENERGY_TAG = "Energy";

    public GolemTrackingMechanicalBowItem(Properties properties) {
        super(properties, BASE_DRAW_STRENGTH, 0, projectileAttributes());
    }

    private static Consumer<ImmutableMultimap.Builder<Attribute, AttributeModifier>> projectileAttributes() {
        return builder -> {
            Attribute projectileAttribute = resolveAttribute("BOW_STRENGTH");
            if (projectileAttribute != null) {
                double base = Config.TRACKING_BOW_PROJECTILE_DAMAGE.get();
                builder.put(projectileAttribute, new AttributeModifier(
                        UUID.nameUUIDFromBytes("golems_arsenal:tracking_bow_projectile".getBytes()),
                        "Tracking bow projectile damage",
                        base,
                        AttributeModifier.Operation.MULTIPLY_BASE));
            }
        };
    }

    /** L2lib explosion damage attribute, granted to the holder only while the tech upgrade is installed. */
    @Nullable
    public static Attribute explosionAttribute() {
        return resolveAttribute("EXPLOSION_FACTOR");
    }

    /** Explosive arrows are a tech-upgrade skill; they unlock when the tech upgrade is installed. */
    public static boolean canExplode(AbstractGolemEntity<?, ?> golem) {
        return GolemEnergyTechModifier.hasTechUpgrade(golem);
    }

    /**
     * The golem's held bow renders through Modular Golems' special bow model, which reads its texture
     * from {@code textures/equipments/<id>.png}. Until a custom texture exists, reuse the netherite
     * mecha bow texture; to use your own, drop the files into
     * {@code assets/golems_arsenal/textures/equipments/golem_tracking_mechanical_bow.png} and
     * {@code ..._pulling.png}, then point this method at them.
     */
    @Override
    public ResourceLocation getModelTexture(MetalGolemEntity entity, ItemStack stack, InteractionHand hand) {
        String suffix = shouldPlayAnimation(entity, stack, hand) ? "_pulling.png" : ".png";
        return new ResourceLocation("modulargolems:textures/equipments/netherite_mecha_bow" + suffix);
    }

    @Nullable
    private static Attribute resolveAttribute(String field) {
        try {
            Class<?> tracker = Class.forName("dev.xkmc.l2damagetracker.init.L2DamageTracker");
            Object entry = tracker.getField(field).get(null);
            Object attribute = entry.getClass().getMethod("get").invoke(entry);
            return attribute instanceof Attribute value ? value : null;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    public boolean consumeTrackingEnergy(ItemStack stack) {
        int cost = Config.TRACKING_BOW_ATTACK_COST.get();
        return stack.getCapability(ForgeCapabilities.ENERGY).map(storage -> {
            if (storage.extractEnergy(cost, true) < cost) {
                return false;
            }
            storage.extractEnergy(cost, false);
            return true;
        }).orElse(false);
    }

    public int getEnergyCapacity(ItemStack stack) {
        long capacity = (long) Config.TRACKING_BOW_CAPACITY.get()
                + (long) WeaponUpgradeData.getLevel(stack, ENERGY_CAPACITY_UNIT) * 250_000L;
        return (int) Math.min(capacity, Integer.MAX_VALUE);
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

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> list, TooltipFlag flag) {
        super.appendHoverText(stack, level, list, flag);
        list.add(Component.translatable("tooltip.golems_arsenal.tracking_bow.tracking")
                .withStyle(ChatFormatting.GRAY));
        list.add(Component.translatable("tooltip.golems_arsenal.tracking_bow.projectile",
                Math.round(Config.TECH_PROJECTILE_PER_LEVEL.get() * 100)).withStyle(ChatFormatting.GRAY));
        list.add(Component.translatable("tooltip.golems_arsenal.energy",
                getStoredEnergy(stack), getEnergyCapacity(stack)).withStyle(ChatFormatting.AQUA));
        list.add(Component.translatable("tooltip.golems_arsenal.tracking_bow.cost",
                Config.TRACKING_BOW_ATTACK_COST.get()).withStyle(ChatFormatting.DARK_GRAY));
        list.add(Component.translatable("tooltip.golems_arsenal.units",
                WeaponUpgradeData.getTotalLevels(stack)).withStyle(ChatFormatting.DARK_AQUA));
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
        return Mth.hsvToRgb(0.58f, 1.0f, 1.0f);
    }

    @Override
    public @Nullable ICapabilityProvider initCapabilities(ItemStack stack, @Nullable CompoundTag nbt) {
        return new EnergyProvider(stack);
    }

    private static int getStoredEnergy(ItemStack stack) {
        return Math.max(0, stack.getOrCreateTag().getInt(ENERGY_TAG));
    }

    private final class EnergyProvider implements ICapabilityProvider {
        private final LazyOptional<IEnergyStorage> energy;

        private EnergyProvider(ItemStack stack) {
            energy = LazyOptional.of(() -> new EnergyStorage(stack));
        }

        @Override
        public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> capability,
                                                          @Nullable Direction side) {
            return capability == ForgeCapabilities.ENERGY ? energy.cast() : LazyOptional.empty();
        }
    }

    private final class EnergyStorage implements IEnergyStorage {
        private final ItemStack stack;

        private EnergyStorage(ItemStack stack) {
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
            return GolemTrackingMechanicalBowItem.getStoredEnergy(stack);
        }

        @Override
        public int getMaxEnergyStored() {
            return GolemTrackingMechanicalBowItem.this.getEnergyCapacity(stack);
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
