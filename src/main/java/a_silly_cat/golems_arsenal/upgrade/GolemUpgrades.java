package a_silly_cat.golems_arsenal.upgrade;

import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;

public final class GolemUpgrades {
    private static Object energyEntry;
    private static Object energyTechEntry;
    private static Object energyHealLegacyEntry;
    private static Object weaponMainEntry;
    private static Object weaponAltEntry;
    private static Object weaponRangedEntry;
    private static Object weaponShieldEntry;
    private static Object weaponOnslaughtEntry;

    /** Register in Modular Golems' modifier registry so l2serial can persist the upgrade. */
    public static void register() {
        try {
            Class<?> supplierType = Class.forName("com.tterrag.registrate.util.nullness.NonNullSupplier");
            Class<?> modifiers = Class.forName("dev.xkmc.modulargolems.init.registrate.GolemModifiers");
            java.lang.reflect.Method reg = modifiers.getMethod("reg", String.class, supplierType, String.class);
            energyEntry = reg.invoke(null, "golem_energy", proxy(supplierType, GolemEnergyModifier::new),
                    "Golem Energy Storage");
            energyTechEntry = reg.invoke(null, "golem_energy_tech", proxy(supplierType, GolemEnergyTechModifier::new),
                    "Golem Energy Tech");
            // Legacy alias so holders saved before the rename keep working. Forge forbids registering one instance
            // under two names, so this is a separate instance; updateAttributes always rebuilds modifiers from the
            // upgrade items afterwards, so gameplay only ever sees the tech modifier.
            energyHealLegacyEntry = reg.invoke(null, "golem_energy_heal", proxy(supplierType, GolemEnergyTechModifier::new),
                    "Golem Energy Tech");
            weaponMainEntry = reg.invoke(null, "golem_weapon_main", proxy(supplierType, GolemWeaponMainModifier::new),
                    "Golem Weapon Upgrade: Main");
            weaponAltEntry = reg.invoke(null, "golem_weapon_alt", proxy(supplierType, GolemWeaponAltModifier::new),
                    "Golem Weapon Upgrade: Atypical");
            weaponRangedEntry = reg.invoke(null, "golem_weapon_ranged", proxy(supplierType, GolemWeaponRangedModifier::new),
                    "Golem Weapon Upgrade: Ranged");
            weaponShieldEntry = reg.invoke(null, "golem_weapon_shield", proxy(supplierType, GolemWeaponShieldModifier::new),
                    "Golem Weapon Upgrade: Shield");
            weaponOnslaughtEntry = reg.invoke(null, "golem_weapon_onslaught", proxy(supplierType, GolemWeaponOnslaughtModifier::new),
                    "Golem Weapon Upgrade: Full Onslaught");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to register golem energy modifiers", e);
        }
    }

    private static Object proxy(Class<?> supplierType, java.util.function.Supplier<?> factory) {
        return java.lang.reflect.Proxy.newProxyInstance(
                supplierType.getClassLoader(), new Class<?>[]{supplierType},
                (proxy, method, args) -> method.getName().equals("get") ? factory.get() : null);
    }

    public static GolemEnergyModifier modifier() {
        if (energyEntry == null) throw new IllegalStateException("Energy modifier has not been registered");
        try {
            return (GolemEnergyModifier) energyEntry.getClass().getMethod("get").invoke(energyEntry);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to resolve golem energy modifier", e);
        }
    }

    public static GolemEnergyTechModifier techModifier() {
        if (energyTechEntry == null) throw new IllegalStateException("Energy tech modifier has not been registered");
        try {
            return (GolemEnergyTechModifier) energyTechEntry.getClass().getMethod("get").invoke(energyTechEntry);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to resolve golem energy tech modifier", e);
        }
    }

    public static GolemWeaponMainModifier mainWeaponModifier() {
        if (weaponMainEntry == null) throw new IllegalStateException("Main weapon modifier has not been registered");
        try {
            return (GolemWeaponMainModifier) weaponMainEntry.getClass().getMethod("get").invoke(weaponMainEntry);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to resolve main weapon modifier", e);
        }
    }

    public static GolemWeaponAltModifier altWeaponModifier() {
        if (weaponAltEntry == null) throw new IllegalStateException("Atypical weapon modifier has not been registered");
        try {
            return (GolemWeaponAltModifier) weaponAltEntry.getClass().getMethod("get").invoke(weaponAltEntry);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to resolve atypical weapon modifier", e);
        }
    }

    public static GolemWeaponRangedModifier rangedWeaponModifier() {
        if (weaponRangedEntry == null) throw new IllegalStateException("Ranged weapon modifier has not been registered");
        try {
            return (GolemWeaponRangedModifier) weaponRangedEntry.getClass().getMethod("get").invoke(weaponRangedEntry);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to resolve ranged weapon modifier", e);
        }
    }

    public static GolemWeaponShieldModifier shieldWeaponModifier() {
        if (weaponShieldEntry == null) throw new IllegalStateException("Shield weapon modifier has not been registered");
        try {
            return (GolemWeaponShieldModifier) weaponShieldEntry.getClass().getMethod("get").invoke(weaponShieldEntry);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to resolve shield weapon modifier", e);
        }
    }

    public static GolemWeaponOnslaughtModifier onslaughtModifier() {
        if (weaponOnslaughtEntry == null) throw new IllegalStateException("Onslaught modifier has not been registered");
        try {
            return (GolemWeaponOnslaughtModifier) weaponOnslaughtEntry.getClass().getMethod("get").invoke(weaponOnslaughtEntry);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to resolve onslaught modifier", e);
        }
    }

    /** Level-1 upgrades are considered installed when their modifier is present on the golem. */
    public static boolean hasUpgrade(AbstractGolemEntity<?, ?> entity, Class<? extends GolemModifier> type) {
        return entity.getModifiers().keySet().stream().anyMatch(type::isInstance);
    }

    private GolemUpgrades() {}
}
