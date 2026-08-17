package a_silly_cat.golems_arsenal;

import net.minecraftforge.common.ForgeConfigSpec;

public final class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.IntValue ENERGY_KATANA_ATTACK_COST;
    public static final ForgeConfigSpec.DoubleValue ENERGY_KATANA_SPECIAL_DAMAGE;
    public static final ForgeConfigSpec.IntValue ENERGY_KATANA_UNIT_MAX_LEVEL;
    public static final ForgeConfigSpec.IntValue ENERGY_KATANA_CAPACITY;
    public static final ForgeConfigSpec.IntValue ENERGY_KATANA_CHARGE_DURATION;
    public static final ForgeConfigSpec.IntValue ENERGY_KATANA_CHAIN_COST;
    public static final ForgeConfigSpec.IntValue ENERGY_KATANA_CHAIN_BONUS_STRIKES;
    public static final ForgeConfigSpec.DoubleValue ENERGY_KATANA_CHAIN_RADIUS;
    public static final ForgeConfigSpec.DoubleValue ENERGY_KATANA_CHAIN_DAMAGE;
    public static final ForgeConfigSpec.IntValue ENERGY_KATANA_CHAIN_WEAKNESS_DURATION;
    public static final ForgeConfigSpec.IntValue TRACKING_BOW_ATTACK_COST;
    public static final ForgeConfigSpec.DoubleValue TRACKING_BOW_PROJECTILE_DAMAGE;
    public static final ForgeConfigSpec.IntValue TRACKING_BOW_CAPACITY;
    public static final ForgeConfigSpec.IntValue TRACKING_BOW_UNIT_MAX_LEVEL;
    public static final ForgeConfigSpec.DoubleValue TRACKING_BOW_TRACKING_RANGE;
    public static final ForgeConfigSpec.DoubleValue TRACKING_BOW_TRACKING_TURN;
    public static final ForgeConfigSpec.DoubleValue TRACKING_BOW_EXPLOSION_RADIUS;
    public static final ForgeConfigSpec.DoubleValue TRACKING_BOW_EXPLOSION_DAMAGE_RATIO;
    public static final ForgeConfigSpec.DoubleValue TRACKING_BOW_EXPLOSION_FACTOR_RATIO;
    public static final ForgeConfigSpec.IntValue GOLEM_ENERGY_BASE_CAPACITY;
    public static final ForgeConfigSpec.IntValue GOLEM_ENERGY_PER_LEVEL;
    public static final ForgeConfigSpec.IntValue GOLEM_ENERGY_LIGHTNING;
    public static final ForgeConfigSpec.IntValue GOLEM_ENERGY_TRANSFER_RATE;
    public static final ForgeConfigSpec.IntValue TECH_ARMOR;
    public static final ForgeConfigSpec.IntValue TECH_TOUGHNESS;
    public static final ForgeConfigSpec.DoubleValue TECH_HP_REGEN;
    public static final ForgeConfigSpec.DoubleValue TECH_DAMAGE_PER_LEVEL;
    public static final ForgeConfigSpec.DoubleValue TECH_PROJECTILE_PER_LEVEL;
    public static final ForgeConfigSpec.DoubleValue TECH_CHARGE_PIERCE_PER_LEVEL;
    public static final ForgeConfigSpec.DoubleValue ENERGY_HAMMER_BIG_HIT_THRESHOLD;
    public static final ForgeConfigSpec.DoubleValue ENERGY_HAMMER_BIG_HIT_REDUCTION;
    public static final ForgeConfigSpec.DoubleValue ENERGY_HAMMER_FLAT_IMMUNITY;
    public static final ForgeConfigSpec.DoubleValue ENERGY_HAMMER_HP_REGEN_PERCENT;
    public static final ForgeConfigSpec.IntValue ENERGY_HAMMER_REDUCTION_COST;
    public static final ForgeConfigSpec.DoubleValue MAIN_WEAPON_HP_PERCENT;
    public static final ForgeConfigSpec.DoubleValue FORGE_HAMMER_HP_PERCENT;
    public static final ForgeConfigSpec.DoubleValue FLAME_CLOUD_RADIUS;
    public static final ForgeConfigSpec.IntValue FLAME_CLOUD_DELAY;
    public static final ForgeConfigSpec.DoubleValue FLAME_CLOUD_DAMAGE;
    public static final ForgeConfigSpec.DoubleValue SPEAR_AOE_RADIUS;
    public static final ForgeConfigSpec.DoubleValue SPEAR_AOE_DAMAGE;
    public static final ForgeConfigSpec.DoubleValue SCULK_SCYTHE_BONUS;
    public static final ForgeConfigSpec.DoubleValue RANGED_ARROW_SPEED;
    public static final ForgeConfigSpec.DoubleValue RANGED_CANNON_CD_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue SHIELD_REPAIR_PER_ARMOR;
    public static final ForgeConfigSpec.IntValue SHIELD_REPAIR_MAX;
    public static final ForgeConfigSpec.IntValue SHIELD_REPAIR_COOLDOWN;
    public static final ForgeConfigSpec.IntValue ONSLAUGHT_ARMOR_THRESHOLD;
    public static final ForgeConfigSpec.BooleanValue ONSLAUGHT_ATTACK_PERCENT_MODE;
    public static final ForgeConfigSpec.DoubleValue ONSLAUGHT_ATTACK_PERCENT_PER_POINT;
    public static final ForgeConfigSpec.DoubleValue ONSLAUGHT_ATTACK_FLAT_PER_POINT;
    public static final ForgeConfigSpec.DoubleValue ONSLAUGHT_GUN_PERCENT_PER_POINT;
    public static final ForgeConfigSpec.IntValue PART_CRUSHER_MATERIAL_LOSS;
    public static final ForgeConfigSpec.IntValue PART_SAWMILL_MATERIAL_LOSS;
    public static final ForgeConfigSpec.IntValue PART_SAWMILL_CLAY;
    public static final ForgeConfigSpec SPEC;

    static {
        BUILDER.push("energy_katana");
        ENERGY_KATANA_ATTACK_COST = BUILDER.comment("FE consumed by each powered hit")
                .defineInRange("attack_cost", 2_500, 0, Integer.MAX_VALUE);
        ENERGY_KATANA_SPECIAL_DAMAGE = BUILDER.comment("Additional damage while the katana has enough FE")
                .defineInRange("special_damage", 0.25, 0.0, 100.0);
        ENERGY_KATANA_CAPACITY = BUILDER.comment("Base FE capacity of the katana")
                .defineInRange("capacity", 1_000_000, 1, Integer.MAX_VALUE);
        ENERGY_KATANA_UNIT_MAX_LEVEL = BUILDER.comment("Maximum level for each future katana unit; 0 means unlimited")
                .defineInRange("unit_max_level", 0, 0, Integer.MAX_VALUE);
        ENERGY_KATANA_CHARGE_DURATION = BUILDER.comment("Charge effect duration (ticks) granted to the holder on a powered katana hit")
                .defineInRange("charge_duration", 100, 1, 72_000);
        ENERGY_KATANA_CHAIN_COST = BUILDER.comment("Extra FE consumed each time the lightning chain triggers (on top of the powered-hit cost)")
                .defineInRange("chain_cost", 2_000, 0, Integer.MAX_VALUE);
        ENERGY_KATANA_CHAIN_BONUS_STRIKES = BUILDER.comment("Extra lightning-chain strikes after the first; the chain can also repeat the same target")
                .defineInRange("chain_bonus_strikes", 2, 0, 16);
        ENERGY_KATANA_CHAIN_RADIUS = BUILDER.comment("Radius in blocks for finding additional lightning-chain targets")
                .defineInRange("chain_radius", 6.0, 1.0, 64.0);
        ENERGY_KATANA_CHAIN_DAMAGE = BUILDER.comment("Base lightning damage per chain strike")
                .defineInRange("chain_damage", 5.0, 0.0, 1024.0);
        ENERGY_KATANA_CHAIN_WEAKNESS_DURATION = BUILDER.comment("Weakness duration (ticks) applied to chain targets")
                .defineInRange("chain_weakness_duration", 100, 1, 72_000);
        BUILDER.pop();

        BUILDER.push("tracking_mechanical_bow");
        TRACKING_BOW_ATTACK_COST = BUILDER.comment("FE consumed when a golem fires a tracking arrow")
                .defineInRange("attack_cost", 4_000, 0, Integer.MAX_VALUE);
        TRACKING_BOW_PROJECTILE_DAMAGE = BUILDER.comment("Projectile damage bonus from the bow attribute")
                .defineInRange("projectile_damage", 0.25, -1.0, 100.0);
        TRACKING_BOW_CAPACITY = BUILDER.comment("Base FE capacity of the bow")
                .defineInRange("capacity", 1_000_000, 1, Integer.MAX_VALUE);
        TRACKING_BOW_UNIT_MAX_LEVEL = BUILDER.comment("Maximum level for each future bow unit; 0 means unlimited")
                .defineInRange("unit_max_level", 0, 0, Integer.MAX_VALUE);
        TRACKING_BOW_TRACKING_RANGE = BUILDER.comment("Maximum distance for arrow homing")
                .defineInRange("tracking_range", 32.0, 1.0, 256.0);
        TRACKING_BOW_TRACKING_TURN = BUILDER.comment("Arrow homing turn strength per tick")
                .defineInRange("tracking_turn", 0.18, 0.01, 1.0);
        TRACKING_BOW_EXPLOSION_RADIUS = BUILDER.comment("Base explosion radius when a powered arrow hits")
                .defineInRange("explosion_radius", 0.75, 0.0, 8.0);
        TRACKING_BOW_EXPLOSION_DAMAGE_RATIO = BUILDER.comment("Conversion rate of projectile damage into explosion damage (radius growth per damage point)")
                .defineInRange("explosion_damage_ratio", 1.0, 0.0, 10.0);
        TRACKING_BOW_EXPLOSION_FACTOR_RATIO = BUILDER.comment("Explosion damage attribute granted to the holder, as a fraction of the projectile damage attribute")
                .defineInRange("explosion_factor_ratio", 1.0, 0.0, 10.0);
        BUILDER.pop();

        BUILDER.push("golem_energy_upgrade");
        GOLEM_ENERGY_BASE_CAPACITY = BUILDER.comment("Base FE capacity granted by the energy upgrade")
                .defineInRange("base_capacity", 100_000, 0, Integer.MAX_VALUE);
        GOLEM_ENERGY_PER_LEVEL = BUILDER.comment("Additional FE capacity per upgrade level")
                .defineInRange("capacity_per_level", 100_000, 0, Integer.MAX_VALUE);
        GOLEM_ENERGY_LIGHTNING = BUILDER.comment("FE restored when a golem is struck by lightning")
                .defineInRange("lightning_charge", 50_000, 0, Integer.MAX_VALUE);
        GOLEM_ENERGY_TRANSFER_RATE = BUILDER.comment("FE transferred per tick from a golem to each powered item")
                .defineInRange("equipment_transfer_rate", 2_500, 0, Integer.MAX_VALUE);
        BUILDER.pop();

        BUILDER.push("golem_energy_tech_upgrade");
        TECH_ARMOR = BUILDER.comment("Armor granted per upgrade level by the tech upgrade")
                .defineInRange("armor", 8, 0, Integer.MAX_VALUE);
        TECH_TOUGHNESS = BUILDER.comment("Armor toughness granted per upgrade level by the tech upgrade")
                .defineInRange("toughness", 3, 0, Integer.MAX_VALUE);
        TECH_HP_REGEN = BUILDER.comment("Health regen granted per upgrade level by the tech upgrade")
                .defineInRange("hp_regen", 1.0, 0.0, 1000.0);
        TECH_DAMAGE_PER_LEVEL = BUILDER.comment("Extra attack damage per tech upgrade level, granted to the holder while wielding the energy katana")
                .defineInRange("damage_per_level", 0.05, 0.0, 10.0);
        TECH_PROJECTILE_PER_LEVEL = BUILDER.comment("Extra projectile damage per tech upgrade level (tracking bow)")
                .defineInRange("projectile_per_level", 0.10, 0.0, 10.0);
        TECH_CHARGE_PIERCE_PER_LEVEL = BUILDER.comment("Chance per charge amplifier level for the charge effect to pierce protection and resistance on an attack")
                .defineInRange("charge_pierce_per_level", 0.2, 0.0, 1.0);
        BUILDER.pop();

        BUILDER.push("energy_hammer");
        ENERGY_HAMMER_BIG_HIT_THRESHOLD = BUILDER.comment("Fraction of max health above which damage is reduced by big_hit_reduction")
                .defineInRange("big_hit_threshold", 0.2, 0.0, 1.0);
        ENERGY_HAMMER_BIG_HIT_REDUCTION = BUILDER.comment("Fraction of damage removed from hits above the threshold (consumes energy)")
                .defineInRange("big_hit_reduction", 0.4, 0.0, 1.0);
        ENERGY_HAMMER_FLAT_IMMUNITY = BUILDER.comment("Flat damage removed from every hit while holding the hammer")
                .defineInRange("flat_immunity", 4.0, 0.0, 1024.0);
        ENERGY_HAMMER_HP_REGEN_PERCENT = BUILDER.comment("Max HP restored per second per tech upgrade level while holding the hammer")
                .defineInRange("hp_regen_percent", 2.0, 0.0, 100.0);
        ENERGY_HAMMER_REDUCTION_COST = BUILDER.comment("FE consumed from the golem each time the big-hit reduction triggers")
                .defineInRange("reduction_cost", 2_000, 0, Integer.MAX_VALUE);
        BUILDER.pop();

        BUILDER.push("weapon_upgrades");
        MAIN_WEAPON_HP_PERCENT = BUILDER.comment("Extra damage as a fraction of the golem's max health for main-class weapons (swords, axes, flame sword)")
                .defineInRange("main_hp_percent", 0.02, 0.0, 1.0);
        FORGE_HAMMER_HP_PERCENT = BUILDER.comment("Extra damage as a fraction of the golem's max health for the forge hammer (larger than the default)")
                .defineInRange("forge_hammer_hp_percent", 0.06, 0.0, 1.0);
        FLAME_CLOUD_RADIUS = BUILDER.comment("Radius in blocks of the flame sword's lingering flame cloud")
                .defineInRange("flame_cloud_radius", 2.0, 0.5, 16.0);
        FLAME_CLOUD_DELAY = BUILDER.comment("Ticks before the flame sword's flame cloud deals magic damage")
                .defineInRange("flame_cloud_delay", 20, 1, 200);
        FLAME_CLOUD_DAMAGE = BUILDER.comment("Magic damage dealt by the flame sword's flame cloud")
                .defineInRange("flame_cloud_damage", 4.0, 0.0, 1024.0);
        SPEAR_AOE_RADIUS = BUILDER.comment("Radius in blocks of the golem spear's area damage")
                .defineInRange("spear_aoe_radius", 2.5, 0.5, 16.0);
        SPEAR_AOE_DAMAGE = BUILDER.comment("Fraction of the original hit dealt as area damage by the golem spear")
                .defineInRange("spear_aoe_damage", 0.5, 0.0, 10.0);
        SCULK_SCYTHE_BONUS = BUILDER.comment("Extra damage as a fraction of the original hit for the sculk golem scythe")
                .defineInRange("sculk_scythe_bonus", 0.25, 0.0, 10.0);
        RANGED_ARROW_SPEED = BUILDER.comment("Arrow velocity multiplier for golem bows with the ranged weapon upgrade; vanilla arrow damage scales with velocity, so damage rises together with speed")
                .defineInRange("ranged_arrow_speed", 1.5, 1.0, 10.0);
        RANGED_CANNON_CD_MULTIPLIER = BUILDER.comment("Cooldown multiplier for the golem Sonic Cannon (Echo Cannon) while the ranged weapon upgrade is installed; lower = faster firing")
                .defineInRange("ranged_cannon_cd_multiplier", 0.5, 0.05, 1.0);
        SHIELD_REPAIR_PER_ARMOR = BUILDER.comment("Shield durability restored per combined armor and toughness point on a successful block (humanoid golems, shield weapon upgrade)")
                .defineInRange("shield_repair_per_armor", 0.2, 0.0, 10.0);
        SHIELD_REPAIR_MAX = BUILDER.comment("Maximum shield durability restored per successful block")
                .defineInRange("shield_repair_max", 20, 1, 10000);
        SHIELD_REPAIR_COOLDOWN = BUILDER.comment("Cooldown in ticks between shield durability restorations")
                .defineInRange("shield_repair_cooldown", 100, 1, 72000);
        ONSLAUGHT_ARMOR_THRESHOLD = BUILDER.comment("Armor value above which the full onslaught upgrade starts granting bonuses; toughness adds to the bonus points once exceeded")
                .defineInRange("onslaught_armor_threshold", 16, 0, 1000);
        ONSLAUGHT_ATTACK_PERCENT_MODE = BUILDER.comment("True: melee attack bonus is percentage per armor point; false: flat damage per armor point")
                .define("onslaught_attack_percent_mode", false);
        ONSLAUGHT_ATTACK_PERCENT_PER_POINT = BUILDER.comment("Melee attack bonus per armor point above the threshold when percent mode is on")
                .defineInRange("onslaught_attack_percent_per_point", 0.10, 0.0, 10.0);
        ONSLAUGHT_ATTACK_FLAT_PER_POINT = BUILDER.comment("Melee attack bonus per armor point above the threshold when percent mode is off")
                .defineInRange("onslaught_attack_flat_per_point", 1.0, 0.0, 1000.0);
        ONSLAUGHT_GUN_PERCENT_PER_POINT = BUILDER.comment("TACZ gun damage bonus per armor point above the threshold, as a fraction (percentage only)")
                .defineInRange("onslaught_gun_percent_per_point", 0.05, 0.0, 10.0);
        BUILDER.pop();

        BUILDER.push("part_recycling");
        PART_CRUSHER_MATERIAL_LOSS = BUILDER.comment("Materials lost when crushing a golem part (less loss than sawmilling)")
                .defineInRange("crusher_material_loss", 1, 0, 64);
        PART_SAWMILL_MATERIAL_LOSS = BUILDER.comment("Materials lost when sawmilling a golem part (more loss than crushing)")
                .defineInRange("sawmill_material_loss", 2, 0, 64);
        PART_SAWMILL_CLAY = BUILDER.comment("Clay balls produced as a byproduct when sawmilling a golem part (the golem template recipe uses 4 clay)")
                .defineInRange("sawmill_clay", 4, 0, 64);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private Config() {
    }
}
