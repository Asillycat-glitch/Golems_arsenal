package a_silly_cat.golems_arsenal.upgrade;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import com.tterrag.registrate.util.entry.RegistryEntry;
import com.tterrag.registrate.util.nullness.NonNullSupplier;
import dev.xkmc.l2library.base.L2Registrate;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.AttributeGolemModifier;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import dev.xkmc.modulargolems.init.registrate.GolemTypes;

/**
 * Registers this mod's golem modifiers through its own {@link L2Registrate}, exactly like other
 * working Modular Golems addons (e.g. Golem Dungeons). The L2Registrate constructor wires the
 * registry events automatically, so the values land in the live {@code modulargolems:modifier}
 * registry and {@link RegistryEntry#get()} resolves both server and client side.
 */
public final class GolemUpgrades {

    public static final L2Registrate REGISTRATE = new L2Registrate(Golems_arsenal.MODID);

    public static final RegistryEntry<GolemEnergyModifier> ENERGY =
            reg("golem_energy", GolemEnergyModifier::new);
    public static final RegistryEntry<GolemEnergyTechModifier> ENERGY_TECH =
            reg("golem_energy_tech", GolemEnergyTechModifier::new);
    // Legacy alias so holders saved before the rename keep working. Forge forbids registering one
    // instance under two names, so this is a separate instance; updateAttributes always rebuilds
    // modifiers from the upgrade items afterwards, so gameplay only ever sees the tech modifier.
    public static final RegistryEntry<GolemEnergyTechModifier> ENERGY_HEAL_LEGACY =
            reg("golem_energy_heal", GolemEnergyTechModifier::new);
    public static final RegistryEntry<GolemWeaponMainModifier> WEAPON_MAIN =
            reg("golem_weapon_main", GolemWeaponMainModifier::new);
    public static final RegistryEntry<GolemWeaponAltModifier> WEAPON_ALT =
            reg("golem_weapon_alt", GolemWeaponAltModifier::new);
    public static final RegistryEntry<GolemWeaponRangedModifier> WEAPON_RANGED =
            reg("golem_weapon_ranged", GolemWeaponRangedModifier::new);
    public static final RegistryEntry<GolemWeaponShieldModifier> WEAPON_SHIELD =
            reg("golem_weapon_shield", GolemWeaponShieldModifier::new);
    public static final RegistryEntry<GolemWeaponOnslaughtModifier> WEAPON_ONSLAUGHT =
            reg("golem_weapon_onslaught", GolemWeaponOnslaughtModifier::new);

    /**
     * Example repeatable expansion: up to 5 applications, each level +1 upgrade slot,
     * +1 armor and +0.5 attack damage. Duplicate the registration to make more variants.
     */
    public static final RegistryEntry<RepeatableExpansionModifier> ARMORY_EXPANSION =
            reg("golem_armory_expansion", () -> new RepeatableExpansionModifier(5, 1,
                    new AttributeGolemModifier.AttrEntry(GolemTypes.STAT_ARMOR, () -> 1.0),
                    new AttributeGolemModifier.AttrEntry(GolemTypes.STAT_ATTACK, () -> 0.5)));

    private static <T extends GolemModifier> RegistryEntry<T> reg(String id, NonNullSupplier<T> sup) {
        return REGISTRATE.generic(GolemTypes.MODIFIERS, id, sup).defaultLang().register();
    }

    /** Forces the static initializer; the actual registration happens in the field declarations above. */
    public static void register() {
    }

    public static GolemEnergyModifier modifier() {
        return ENERGY.get();
    }

    public static GolemEnergyTechModifier techModifier() {
        return ENERGY_TECH.get();
    }

    public static GolemWeaponMainModifier mainWeaponModifier() {
        return WEAPON_MAIN.get();
    }

    public static GolemWeaponAltModifier altWeaponModifier() {
        return WEAPON_ALT.get();
    }

    public static GolemWeaponRangedModifier rangedWeaponModifier() {
        return WEAPON_RANGED.get();
    }

    public static GolemWeaponShieldModifier shieldWeaponModifier() {
        return WEAPON_SHIELD.get();
    }

    public static GolemWeaponOnslaughtModifier onslaughtModifier() {
        return WEAPON_ONSLAUGHT.get();
    }

    public static RepeatableExpansionModifier armoryExpansionModifier() {
        return ARMORY_EXPANSION.get();
    }

    /** Level-1 upgrades are considered installed when their modifier is present on the golem. */
    public static boolean hasUpgrade(AbstractGolemEntity<?, ?> entity, Class<? extends GolemModifier> type) {
        return entity.getModifiers().keySet().stream().anyMatch(type::isInstance);
    }

    private GolemUpgrades() {
    }
}
