package a_silly_cat.golems_arsenal.base.upgrade;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import com.tterrag.registrate.util.entry.RegistryEntry;
import com.tterrag.registrate.util.nullness.NonNullSupplier;
import dev.xkmc.l2library.base.L2Registrate;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.AttributeGolemModifier;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import dev.xkmc.modulargolems.init.registrate.GolemTypes;
import a_silly_cat.golems_arsenal.tech.upgrade.GolemEnergyModifier;
import a_silly_cat.golems_arsenal.tech.upgrade.GolemEnergyTechModifier;
import dev.xkmc.modulargolems.content.core.StatFilterType;

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
    public static final RegistryEntry<GolemFlagModifier> WEAPON_MAIN =
            reg("golem_weapon_main", () -> new GolemFlagModifier(StatFilterType.ATTACK,
                    "upgrade.golems_arsenal.golem_weapon_main",
                    "upgrade.golems_arsenal.golem_weapon_main.desc_sword"));
    public static final RegistryEntry<GolemFlagModifier> WEAPON_ALT =
            reg("golem_weapon_alt", () -> new GolemFlagModifier(StatFilterType.ATTACK,
                    "upgrade.golems_arsenal.golem_weapon_alt"));
    public static final RegistryEntry<GolemFlagModifier> WEAPON_RANGED =
            reg("golem_weapon_ranged", () -> new GolemFlagModifier(StatFilterType.ATTACK,
                    "upgrade.golems_arsenal.golem_weapon_ranged"));
    public static final RegistryEntry<GolemFlagModifier> WEAPON_SHIELD =
            reg("golem_weapon_shield", () -> new GolemFlagModifier(StatFilterType.HEALTH,
                    "upgrade.golems_arsenal.golem_weapon_shield"));
    public static final RegistryEntry<GolemFlagModifier> WEAPON_ONSLAUGHT =
            reg("golem_weapon_onslaught", () -> new GolemFlagModifier(StatFilterType.ATTACK,
                    "upgrade.golems_arsenal.golem_weapon_onslaught"));
    public static final RegistryEntry<GolemFlagModifier> DEATH_EXPLOSION =
            reg("golem_death_explosion", () -> new GolemFlagModifier(StatFilterType.HEALTH,
                    "upgrade.golems_arsenal.golem_death_explosion"));

    /**
     * Tech expansion template modifier: up to 5 applications, each level +1 upgrade slot,
     * but -10% max health. Duplicate the registration to make more variants.
     */
    public static final RegistryEntry<RepeatableExpansionModifier> TECH_EXPANSION =
            reg("tech_expansion", () -> new RepeatableExpansionModifier(5, 1,
                    "upgrade.golems_arsenal.tech_expansion",
                    new AttributeGolemModifier.AttrEntry(GolemTypes.STAT_HEALTH_P, () -> -0.1)));

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

    public static GolemFlagModifier mainWeaponModifier() {
        return WEAPON_MAIN.get();
    }

    public static GolemFlagModifier altWeaponModifier() {
        return WEAPON_ALT.get();
    }

    public static GolemFlagModifier rangedWeaponModifier() {
        return WEAPON_RANGED.get();
    }

    public static GolemFlagModifier shieldWeaponModifier() {
        return WEAPON_SHIELD.get();
    }

    public static GolemFlagModifier onslaughtModifier() {
        return WEAPON_ONSLAUGHT.get();
    }

    public static GolemFlagModifier deathExplosionModifier() {
        return DEATH_EXPLOSION.get();
    }

    public static boolean hasWeaponMain(AbstractGolemEntity<?, ?> entity) {
        return GolemFlagModifier.hasUpgrade(entity, WEAPON_MAIN.get());
    }

    public static boolean hasWeaponAlt(AbstractGolemEntity<?, ?> entity) {
        return GolemFlagModifier.hasUpgrade(entity, WEAPON_ALT.get());
    }

    public static boolean hasWeaponRanged(AbstractGolemEntity<?, ?> entity) {
        return GolemFlagModifier.hasUpgrade(entity, WEAPON_RANGED.get());
    }

    public static boolean hasWeaponShield(AbstractGolemEntity<?, ?> entity) {
        return GolemFlagModifier.hasUpgrade(entity, WEAPON_SHIELD.get());
    }

    public static boolean hasWeaponOnslaught(AbstractGolemEntity<?, ?> entity) {
        return GolemFlagModifier.hasUpgrade(entity, WEAPON_ONSLAUGHT.get());
    }

    public static boolean hasDeathExplosion(AbstractGolemEntity<?, ?> entity) {
        return GolemFlagModifier.hasUpgrade(entity, DEATH_EXPLOSION.get());
    }

    public static RepeatableExpansionModifier techExpansionModifier() {
        return TECH_EXPANSION.get();
    }

    /** Level-1 upgrades are considered installed when their modifier is present on the golem. */
    public static boolean hasUpgrade(AbstractGolemEntity<?, ?> entity, Class<? extends GolemModifier> type) {
        return entity.getModifiers().keySet().stream().anyMatch(type::isInstance);
    }

    private GolemUpgrades() {
    }
}
