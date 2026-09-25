package a_silly_cat.golems_arsenal.base.upgrade;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import com.tterrag.registrate.util.entry.RegistryEntry;
import com.tterrag.registrate.util.nullness.NonNullSupplier;
import dev.xkmc.l2library.base.L2Registrate;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.AttributeGolemModifier;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import dev.xkmc.modulargolems.init.registrate.GolemTypes;
import a_silly_cat.golems_arsenal.tech.upgrade.GolemBackupEnergyModifier;
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
    public static final RegistryEntry<GolemBackupEnergyModifier> BACKUP_ENERGY =
            reg("backup_energy", GolemBackupEnergyModifier::new);
    /*
     * Every upgrade keeps the id rule "modifier id = item id minus _upgrade" (item main_weapon_upgrade
     * grants modifier main_weapon). Only the upgrades whose name actually says 傀儡 keep the golem_
     * prefix (golem_energy / golem_energy_tech); the rest dropped it as framework-legacy noise.
     */
    public static final RegistryEntry<GolemFlagModifier> WEAPON_MAIN =
            reg("main_weapon", () -> new GolemFlagModifier(StatFilterType.ATTACK,
                    "upgrade.golems_arsenal.main_weapon",
                    "upgrade.golems_arsenal.main_weapon.desc_sword"));
    public static final RegistryEntry<GolemFlagModifier> WEAPON_ALT =
            reg("alt_weapon", () -> new GolemFlagModifier(StatFilterType.ATTACK,
                    "upgrade.golems_arsenal.alt_weapon"));
    public static final RegistryEntry<GolemFlagModifier> WEAPON_RANGED =
            reg("ranged_weapon", () -> new GolemFlagModifier(StatFilterType.ATTACK,
                    "upgrade.golems_arsenal.ranged_weapon"));
    public static final RegistryEntry<GolemFlagModifier> WEAPON_SHIELD =
            reg("shield_weapon", () -> new GolemFlagModifier(StatFilterType.HEALTH,
                    "upgrade.golems_arsenal.shield_weapon"));
    public static final RegistryEntry<GolemFlagModifier> WEAPON_ONSLAUGHT =
            reg("full_onslaught", () -> new GolemFlagModifier(StatFilterType.ATTACK,
                    "upgrade.golems_arsenal.full_onslaught"));
    /** Cavalry (枪骑): speed-scaled melee damage while riding a golem mount, better with spears/bows. */
    public static final RegistryEntry<GolemFlagModifier> CAVALRY =
            reg("cavalry", () -> new GolemFlagModifier(StatFilterType.ATTACK,
                    "upgrade.golems_arsenal.cavalry"));
    public static final RegistryEntry<GolemStanceModifier> STANCE =
            reg("stance", GolemStanceModifier::new);
    public static final RegistryEntry<GolemStanceSubModifier> STANCE_SUB =
            reg("stance_sub", GolemStanceSubModifier::new);
    public static final RegistryEntry<GolemGrazeModifier> GRAZE =
            reg("graze", GolemGrazeModifier::new);
    /** E 罐：存储修复材料，战斗中自动消耗补血（两级：100% / 200% 最大生命容量）。 */
    public static final RegistryEntry<GolemETankModifier> E_TANK =
            reg("e_tank", GolemETankModifier::new);
    /** 光柱护体：受击召唤 4 道光柱，2 秒内免疫伤害，光柱本身也造成伤害。 */
    public static final RegistryEntry<GolemLightPillarModifier> LIGHT_PILLAR =
            reg("light_pillar", GolemLightPillarModifier::new);
    /** 减震（X5 Shock Buffer）：抗击退。 */
    public static final RegistryEntry<GolemShockBufferModifier> SHOCK_BUFFER =
            reg("shock_buffer", GolemShockBufferModifier::new);
    /** 节约能源（X5 Energy Saver）：所有 FE 消耗按等级打折。 */
    public static final RegistryEntry<GolemEnergySaverModifier> ENERGY_SAVER =
            reg("energy_saver", GolemEnergySaverModifier::new);
    /** W 罐（E 罐附属）：把 E 罐的修复材料换成傀儡 FE。 */
    public static final RegistryEntry<GolemWTankModifier> W_TANK =
            reg("w_tank", GolemWTankModifier::new);
    /** Special-move upgrade (example: key blade spin). Effects live in KeySwordEventHandler. */
    public static final RegistryEntry<GolemFlagModifier> KEY_BLADE_SPIN =
            reg("key_blade_spin", () -> new GolemFlagModifier(StatFilterType.ATTACK,
                    "upgrade.golems_arsenal.key_blade_spin",
                    "upgrade.golems_arsenal.special_move.note"));
    /** Special-move upgrade: lion slash (forward flip + downward slam AoE). */
    public static final RegistryEntry<GolemFlagModifier> LION_SLASH =
            reg("lion_slash", () -> new GolemFlagModifier(StatFilterType.ATTACK,
                    "upgrade.golems_arsenal.lion_slash",
                    "upgrade.golems_arsenal.special_move.note"));
    /** Special-move upgrade: sword rain (key blade spirals up, then iron swords rain down). */
    public static final RegistryEntry<GolemFlagModifier> SWORD_RAIN =
            reg("sword_rain", () -> new GolemFlagModifier(StatFilterType.ATTACK,
                    "upgrade.golems_arsenal.sword_rain",
                    "upgrade.golems_arsenal.special_move.note"));
    /** Special-move upgrade: charge (dash to a spot behind the target, damaging the path). */
    public static final RegistryEntry<GolemFlagModifier> CHARGE =
            reg("charge", () -> new GolemFlagModifier(StatFilterType.ATTACK,
                    "upgrade.golems_arsenal.charge",
                    "upgrade.golems_arsenal.special_move.note"));
    /** Special-move upgrade: phoenix rush (backward leap, hover, dive, fire impact). */
    public static final RegistryEntry<GolemFlagModifier> PHOENIX =
            reg("phoenix", () -> new GolemFlagModifier(StatFilterType.ATTACK,
                    "upgrade.golems_arsenal.phoenix",
                    "upgrade.golems_arsenal.special_move.note"));
    /** Start-the-Train: granted to the create:railway material via a material-config datapack. */
    public static final RegistryEntry<GolemTrainModifier> TRAIN =
            reg("train", GolemTrainModifier::new);

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
    public static GolemBackupEnergyModifier backupEnergyModifier() {
        return BACKUP_ENERGY.get();
    }

    public static GolemETankModifier eTankModifier() {
        return E_TANK.get();
    }

    public static GolemLightPillarModifier lightPillarModifier() {
        return LIGHT_PILLAR.get();
    }

    public static GolemShockBufferModifier shockBufferModifier() {
        return SHOCK_BUFFER.get();
    }

    public static GolemEnergySaverModifier energySaverModifier() {
        return ENERGY_SAVER.get();
    }

    public static GolemWTankModifier wTankModifier() {
        return W_TANK.get();
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

    public static GolemFlagModifier cavalryModifier() {
        return CAVALRY.get();
    }

    public static GolemStanceModifier stanceModifier() {
        return STANCE.get();
    }

    public static GolemStanceSubModifier stanceSubModifier() {
        return STANCE_SUB.get();
    }

    public static GolemGrazeModifier grazeModifier() {
        return GRAZE.get();
    }

    public static GolemFlagModifier keyBladeSpinModifier() {
        return KEY_BLADE_SPIN.get();
    }

    public static GolemFlagModifier lionSlashModifier() {
        return LION_SLASH.get();
    }

    public static GolemFlagModifier swordRainModifier() {
        return SWORD_RAIN.get();
    }

    public static GolemFlagModifier chargeModifier() {
        return CHARGE.get();
    }

    public static GolemFlagModifier phoenixModifier() {
        return PHOENIX.get();
    }

    public static GolemTrainModifier trainModifier() {
        return TRAIN.get();
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

    public static boolean hasCavalry(AbstractGolemEntity<?, ?> entity) {
        return GolemFlagModifier.hasUpgrade(entity, CAVALRY.get());
    }

    public static boolean hasStance(AbstractGolemEntity<?, ?> entity) {
        return GolemStanceModifier.hasUpgrade(entity);
    }

    public static boolean hasStanceSub(AbstractGolemEntity<?, ?> entity) {
        return GolemFlagModifier.hasUpgrade(entity, STANCE_SUB.get());
    }

    public static boolean hasGraze(AbstractGolemEntity<?, ?> entity) {
        return GolemFlagModifier.hasUpgrade(entity, GRAZE.get());
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
