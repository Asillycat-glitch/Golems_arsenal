package a_silly_cat.golems_arsenal.compat.mekanism;

import a_silly_cat.golems_arsenal.init.ModTags;
import a_silly_cat.golems_arsenal.tech.energy.MinionEnergyAccess;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.energy.IEnergyContainer;
import mekanism.api.energy.IStrictEnergyHandler;
import mekanism.api.gear.ICustomModule;
import mekanism.api.gear.ICustomModule.ModuleDamageAbsorbInfo;
import mekanism.api.math.FloatingLong;
import mekanism.api.math.FloatingLongSupplier;
import mekanism.common.config.MekanismConfig;
import mekanism.common.content.gear.Module;
import mekanism.common.content.gear.ModuleConfigItem;
import mekanism.common.content.gear.mekasuit.ModuleChargeDistributionUnit;
import mekanism.common.content.network.distribution.EnergySaveTarget;
import mekanism.common.integration.energy.EnergyCompatUtils;
import mekanism.common.item.gear.ItemMekaSuitArmor;
import mekanism.common.tags.MekanismTags;
import mekanism.common.util.EmitUtils;
import mekanism.common.util.StorageUtils;
import mekanism.common.util.UnitDisplayUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * MekaSuit support for golem-like minions, gated by two datapack tags.
 *
 * <p>Mekanism's own {@code ItemMekaSuitArmor} paths are player-only:
 * {@code onArmorTick(..., Player)} never runs on a golem, and
 * {@code CommonPlayerTickHandler} only calls the energy shield absorption for
 * {@code entity instanceof Player}. This handler supplies the two missing pieces for entities in
 * {@code golems_arsenal:meka_suit_minions} that wear armor in
 * {@code golems_arsenal:minion_meka_suit}:</p>
 *
 * <ul>
 *   <li><b>charge distribution</b> — a golem-aware copy of
 *       {@code ModuleChargeDistributionUnit} (suit pieces, hands and the entity FE pool);</li>
 *   <li><b>energy shield absorption</b> — a {@code LivingEntity} version of
 *       {@code ItemMekaSuitArmor.getDamageAbsorbed} / {@code tryAbsorbAll}.</li>
 * </ul>
 *
 * <p>Everything is registered only from {@code MekanismCompat.register}, so no {@code mekanism.*}
 * type is touched when Mekanism is absent. Equipping the armor is not gated here: Modular Golems
 * already accepts any {@code ArmorItem}; the tags decide whether the above effects run.</p>
 */
public final class MinionMekaSuitHandler {

    /** Same cadence as the golem's own equipment charger. */
    private static final int CHARGE_INTERVAL = 5;

    private MinionMekaSuitHandler() {
    }

    // ------------------------------------------------------------------
    // Charge distribution
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onMinionTick(LivingEvent.LivingTickEvent event) {
        LivingEntity minion = event.getEntity();
        if (minion.level().isClientSide
                || minion.tickCount % CHARGE_INTERVAL != 0
                || !isTaggedMinion(minion)) {
            return;
        }
        for (ItemStack armor : minion.getArmorSlots()) {
            if (!isTaggedArmor(armor) || !(armor.getItem() instanceof ItemMekaSuitArmor suit)) {
                continue;
            }
            for (Module<?> module : suit.getModules(armor)) {
                if (!module.isEnabled()
                        || !(module.getCustomInstance() instanceof ModuleChargeDistributionUnit)) {
                    continue;
                }
                if (config(module, "charge_inventory")) {
                    chargeEquipment(minion, armor, module);
                }
                if (config(module, "charge_suit")) {
                    balanceSuit(minion);
                }
            }
        }
    }

    /** Charges hands, armor and finally the minion's own FE pool from one suit module. */
    private static void chargeEquipment(LivingEntity minion, ItemStack source, Module<?> module) {
        FloatingLong budget = MekanismConfig.gear.mekaSuitInventoryChargeRate.get()
                .multiply(CHARGE_INTERVAL);
        if (budget.isZero()) {
            return;
        }
        budget = chargeStack(minion, module, minion.getMainHandItem(), budget, source);
        budget = chargeStack(minion, module, minion.getOffhandItem(), budget, source);
        for (ItemStack armor : minion.getArmorSlots()) {
            if (budget.isZero()) {
                break;
            }
            budget = chargeStack(minion, module, armor, budget, source);
        }
        if (!budget.isZero()) {
            chargeEntityPool(minion, module, budget);
        }
    }

    /**
     * Mirrors Mekanism's {@code ModuleChargeDistributionUnit.charge}: simulate, take exactly the
     * accepted Joules out of the suit piece, then insert them. Returns the unspent budget.
     */
    private static FloatingLong chargeStack(LivingEntity minion, Module<?> module, ItemStack stack,
                                            FloatingLong budget, ItemStack source) {
        if (budget.isZero() || stack.isEmpty() || stack == source) {
            return budget;
        }
        IStrictEnergyHandler handler = EnergyCompatUtils.getStrictEnergyHandler(stack);
        if (handler == null) {
            return budget;
        }
        FloatingLong remainder = handler.insertEnergy(budget, Action.SIMULATE);
        if (remainder.smallerThan(budget)) {
            FloatingLong accepted = budget.subtract(remainder);
            FloatingLong extracted = module.useEnergy(minion, accepted, false);
            return handler.insertEnergy(extracted, Action.EXECUTE).add(remainder);
        }
        return budget;
    }

    /**
     * The minion's own FE pool (golem energy upgrade / MEK Robit / other Forge energy entity).
     * FE and J are converted at Mekanism's configured rate, so the card/pool stays in FE.
     */
    private static void chargeEntityPool(LivingEntity minion, Module<?> module, FloatingLong budget) {
        int feBudget = UnitDisplayUtils.EnergyUnit.FORGE_ENERGY.convertToAsInt(budget);
        if (feBudget <= 0) {
            return;
        }
        int acceptedFe = MinionEnergyAccess.insert(minion, feBudget, true);
        if (acceptedFe <= 0) {
            return;
        }
        FloatingLong acceptedJ = UnitDisplayUtils.EnergyUnit.FORGE_ENERGY.convertFrom(acceptedFe);
        FloatingLong extractedJ = module.useEnergy(minion, acceptedJ, false);
        int actualFe = UnitDisplayUtils.EnergyUnit.FORGE_ENERGY.convertToAsInt(extractedJ);
        if (actualFe > 0) {
            MinionEnergyAccess.insert(minion, actualFe, false);
        }
    }

    /** Balances the energy of all tagged MekaSuit pieces, like the module's {@code charge_suit}. */
    private static void balanceSuit(LivingEntity minion) {
        List<IEnergyContainer> containers = new ArrayList<>(4);
        FloatingLong total = FloatingLong.ZERO;
        for (ItemStack armor : minion.getArmorSlots()) {
            if (!isTaggedArmor(armor)) {
                continue;
            }
            IEnergyContainer container = StorageUtils.getEnergyContainer(armor, 0);
            if (container != null) {
                containers.add(container);
                total = total.add(container.getEnergy());
            }
        }
        if (containers.size() < 2) {
            return;
        }
        EnergySaveTarget target = new EnergySaveTarget(containers.size());
        for (IEnergyContainer container : containers) {
            target.addDelegate(container);
        }
        EmitUtils.sendToAcceptors(target, total);
        target.save();
    }

    /** Reads one of the module's boolean config items by its stable name. */
    private static boolean config(Module<?> module, String name) {
        for (ModuleConfigItem<?> item : module.getConfigItems()) {
            if (name.equals(item.getName())) {
                Object value = item.get();
                return value instanceof Boolean bool && bool;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Energy shield absorption
    // ------------------------------------------------------------------

    /**
     * Full absorption path. Runs late so other cancellations/immunities win first; if the suit
     * absorbs 100% of the hit the event is canceled and the energy is actually extracted here.
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onMinionAttacked(LivingAttackEvent event) {
        LivingEntity minion = event.getEntity();
        if (minion.level().isClientSide
                || event.getAmount() <= 0.0F
                || !isTaggedMinion(minion)
                || event.getSource().is(DamageTypeTags.IS_FALL)) {
            return;
        }
        List<Runnable> callbacks = new ArrayList<>(4);
        if (absorb(minion, event.getSource(), event.getAmount(), callbacks) >= 1.0F) {
            for (Runnable callback : callbacks) {
                callback.run();
            }
            event.setCanceled(true);
        }
    }

    /** Partial absorption path; fall damage is left to Mekanism's own LivingEntity fall branch. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onMinionHurt(LivingHurtEvent event) {
        LivingEntity minion = event.getEntity();
        if (minion.level().isClientSide
                || event.getAmount() <= 0.0F
                || !isTaggedMinion(minion)
                || event.getSource().is(DamageTypeTags.IS_FALL)) {
            return;
        }
        float ratio = absorb(minion, event.getSource(), event.getAmount(), null);
        if (ratio > 0.0F) {
            float remaining = event.getAmount() * Math.max(0.0F, 1.0F - ratio);
            if (remaining <= 0.0F) {
                event.setCanceled(true);
            } else {
                event.setAmount(remaining);
            }
        }
    }

    /**
     * {@code LivingEntity} version of {@code ItemMekaSuitArmor.getDamageAbsorbed}.
     *
     * <p>First the per-module absorption contributions are applied, then the generic per-piece
     * damage-type ratio. Energy is extracted at the end: immediately when {@code callbacks} is
     * {@code null}, or deferred through the callbacks when the caller may still decline the hit.</p>
     */
    private static float absorb(LivingEntity minion, DamageSource source, float amount,
                                @Nullable List<Runnable> callbacks) {
        if (amount <= 0.0F) {
            return 0.0F;
        }
        float ratioAbsorbed = 0.0F;
        List<FoundArmor> armorDetails = new ArrayList<>(4);
        for (ItemStack stack : minion.getArmorSlots()) {
            if (!isTaggedArmor(stack) || !(stack.getItem() instanceof ItemMekaSuitArmor armor)) {
                continue;
            }
            IEnergyContainer container = StorageUtils.getEnergyContainer(stack, 0);
            if (container == null) {
                continue;
            }
            FoundArmor found = new FoundArmor(container, new EnergyUsage(container.getEnergy()), armor);
            armorDetails.add(found);
            for (Module<?> module : armor.getModules(stack)) {
                if (!module.isEnabled()) {
                    continue;
                }
                ModuleDamageAbsorbInfo info = absorbInfo(module, source);
                if (info != null) {
                    ratioAbsorbed += absorbDamage(found.usage, amount,
                            info.absorptionRatio().getAsFloat(), ratioAbsorbed, info.energyCost());
                    if (ratioAbsorbed >= 1.0F) {
                        break;
                    }
                }
            }
            if (ratioAbsorbed >= 1.0F) {
                break;
            }
        }

        if (ratioAbsorbed < 1.0F) {
            Float ratio = null;
            for (FoundArmor found : armorDetails) {
                if (ratio == null) {
                    if (!source.is(MekanismTags.DamageTypes.MEKASUIT_ALWAYS_SUPPORTED)
                            && source.is(DamageTypeTags.BYPASSES_ARMOR)) {
                        break;
                    }
                    ResourceLocation damageType = source.typeHolder().unwrapKey()
                            .map(ResourceKey::location)
                            .orElseGet(() -> minion.level().registryAccess()
                                    .registry(Registries.DAMAGE_TYPE)
                                    .map(registry -> registry.getKey(source.type()))
                                    .orElse(null));
                    if (damageType != null) {
                        ratio = MekanismConfig.gear.mekaSuitDamageRatios.get().get(damageType);
                    }
                    if (ratio == null) {
                        ratio = MekanismConfig.gear.mekaSuitUnspecifiedDamageRatio.getAsFloat();
                    }
                    if (ratio == 0.0F) {
                        break;
                    }
                }
                float absorption = absorptionOf(found.armor) * ratio;
                ratioAbsorbed += absorbDamage(found.usage, amount, absorption, ratioAbsorbed,
                        MekanismConfig.gear.mekaSuitEnergyUsageDamage);
                if (ratioAbsorbed >= 1.0F) {
                    break;
                }
            }
        }

        for (FoundArmor found : armorDetails) {
            if (found.usage.used.isZero()) {
                continue;
            }
            if (callbacks == null) {
                found.container.extract(found.usage.used, Action.EXECUTE, AutomationType.MANUAL);
            } else {
                callbacks.add(() -> found.container.extract(found.usage.used, Action.EXECUTE,
                        AutomationType.MANUAL));
            }
        }
        return Math.min(ratioAbsorbed, 1.0F);
    }

    private static float absorbDamage(EnergyUsage usage, float amount, float absorption,
                                      float currentAbsorbed, FloatingLongSupplier energyCost) {
        absorption = Math.min(1.0F - currentAbsorbed, absorption);
        float toAbsorb = amount * absorption;
        if (toAbsorb <= 0.0F) {
            return 0.0F;
        }
        FloatingLong usageCost = energyCost.get().multiply(toAbsorb);
        if (usageCost.isZero()) {
            return absorption;
        }
        if (usage.available.greaterOrEqual(usageCost)) {
            usage.used = usage.used.plusEqual(usageCost);
            usage.available = usage.available.minusEqual(usageCost);
            return absorption;
        }
        if (!usage.available.isZero()) {
            float absorbedPercent = usage.available.divide(usageCost).floatValue();
            usage.used = usage.used.plusEqual(usage.available);
            usage.available = FloatingLong.ZERO;
            return absorption * absorbedPercent;
        }
        return 0.0F;
    }

    /**
     * Per-piece absorption from {@code ItemMekaSuitArmor}'s constructor (helmet/boots 0.15,
     * chest 0.4, leggings 0.3). Kept here because the field is private and the values are stable.
     */
    private static float absorptionOf(ItemMekaSuitArmor armor) {
        return switch (armor.getType()) {
            case HELMET, BOOTS -> 0.15F;
            case CHESTPLATE -> 0.4F;
            case LEGGINGS -> 0.3F;
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static @Nullable ModuleDamageAbsorbInfo absorbInfo(Module<?> module, DamageSource source) {
        return ((ICustomModule) module.getCustomInstance()).getDamageAbsorbInfo(module, source);
    }

    private static boolean isTaggedMinion(LivingEntity entity) {
        return entity.getType().is(ModTags.MEKA_SUIT_MINIONS);
    }

    private static boolean isTaggedArmor(ItemStack stack) {
        return stack.is(ModTags.MINION_MEKA_SUIT);
    }

    private record FoundArmor(IEnergyContainer container, EnergyUsage usage, ItemMekaSuitArmor armor) {
    }

    private static final class EnergyUsage {
        private FloatingLong available;
        private FloatingLong used = FloatingLong.ZERO;

        private EnergyUsage(FloatingLong available) {
            this.available = available.copy();
        }
    }
}
