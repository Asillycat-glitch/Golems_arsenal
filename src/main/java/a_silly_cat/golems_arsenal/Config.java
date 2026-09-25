package a_silly_cat.golems_arsenal;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

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
    public static final ForgeConfigSpec.DoubleValue BACKUP_ENERGY_SHIELD_PER_LEVEL;
    public static final ForgeConfigSpec.IntValue BACKUP_ENERGY_RECHARGE_INTERVAL;
    public static final ForgeConfigSpec.DoubleValue BACKUP_ENERGY_RECHARGE_PER_LEVEL;
    public static final ForgeConfigSpec.IntValue BACKUP_ENERGY_FE_PER_POINT;
    public static final ForgeConfigSpec.IntValue BACKUP_ENERGY_HURT_COOLDOWN;
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
    public static final ForgeConfigSpec.DoubleValue RANGED_CANNON_MAGIC_BONUS;
    public static final ForgeConfigSpec.DoubleValue CAVALRY_DAMAGE_PER_SPEED;
    public static final ForgeConfigSpec.DoubleValue CAVALRY_SPEAR_MULTIPLIER_PER_SPEED;
    public static final ForgeConfigSpec.DoubleValue CAVALRY_MAX_SPEED;
    public static final ForgeConfigSpec.DoubleValue CAVALRY_ARROW_SPEED;
    public static final ForgeConfigSpec.DoubleValue SHIELD_REPAIR_PER_ARMOR;
    public static final ForgeConfigSpec.IntValue SHIELD_REPAIR_MAX;
    public static final ForgeConfigSpec.IntValue SHIELD_REPAIR_COOLDOWN;
    public static final ForgeConfigSpec.IntValue ONSLAUGHT_ARMOR_THRESHOLD;
    public static final ForgeConfigSpec.BooleanValue ONSLAUGHT_ATTACK_PERCENT_MODE;
    public static final ForgeConfigSpec.DoubleValue ONSLAUGHT_ATTACK_PERCENT_PER_POINT;
    public static final ForgeConfigSpec.DoubleValue ONSLAUGHT_ATTACK_FLAT_PER_POINT;
    public static final ForgeConfigSpec.DoubleValue ONSLAUGHT_GUN_PERCENT_PER_POINT;
    public static final ForgeConfigSpec.DoubleValue STANCE_GAUGE_MAX;
    public static final ForgeConfigSpec.DoubleValue STANCE_ATTACK_GAIN;
    public static final ForgeConfigSpec.DoubleValue STANCE_HURT_GAIN;
    public static final ForgeConfigSpec.DoubleValue STANCE_MOVE_GAIN;
    public static final ForgeConfigSpec.DoubleValue STANCE_MELEE_RATIO;
    public static final ForgeConfigSpec.DoubleValue STANCE_BOW_RATIO;
    public static final ForgeConfigSpec.DoubleValue STANCE_SUB_IFRAME_GAIN;
    public static final ForgeConfigSpec.DoubleValue GRAZE_RADIUS;
    public static final ForgeConfigSpec.IntValue GRAZE_SCAN_INTERVAL;
    public static final ForgeConfigSpec.DoubleValue GRAZE_MOVE_SPEED;
    public static final ForgeConfigSpec.DoubleValue GRAZE_MOVE_SPEED_THRESHOLD;
    public static final ForgeConfigSpec.DoubleValue GRAZE_MIN_PROJECTILE_SPEED;
    public static final ForgeConfigSpec.DoubleValue GRAZE_ATTACK_SPEED;
    public static final ForgeConfigSpec.DoubleValue GRAZE_IFRAME_SECONDS;
    public static final ForgeConfigSpec.IntValue GRAZE_BUFF_DURATION;
    public static final ForgeConfigSpec.IntValue GRAZE_COOLDOWN;
    public static final ForgeConfigSpec.DoubleValue TRAIN_BUFF_RADIUS;
    public static final ForgeConfigSpec.DoubleValue TRAIN_SHARE_RATIO;
    public static final ForgeConfigSpec.IntValue KEY_SWORD_SPECIAL_COOLDOWN;
    public static final ForgeConfigSpec.IntValue KEY_SWORD_FIREBALL_COUNT_MIN;
    public static final ForgeConfigSpec.IntValue KEY_SWORD_FIREBALL_COUNT_MAX;
    public static final ForgeConfigSpec.DoubleValue KEY_SWORD_FIREBALL_FIXED;
    public static final ForgeConfigSpec.DoubleValue KEY_SWORD_FIREBALL_ATTACK_RATIO;
    public static final ForgeConfigSpec.DoubleValue KEY_SWORD_LIGHTNING_FIXED;
    public static final ForgeConfigSpec.DoubleValue KEY_SWORD_LIGHTNING_ATTACK_RATIO;
    public static final ForgeConfigSpec.DoubleValue KEY_SWORD_FROST_FIXED;
    public static final ForgeConfigSpec.DoubleValue KEY_SWORD_FROST_ATTACK_RATIO;
    public static final ForgeConfigSpec.DoubleValue KEY_SWORD_FROST_RADIUS;
    public static final ForgeConfigSpec.IntValue KEY_SWORD_FROST_DURATION_BASE;
    public static final ForgeConfigSpec.DoubleValue KEY_SWORD_FROST_DURATION_PER_DAMAGE;
    public static final ForgeConfigSpec.DoubleValue KEY_SWORD_SPELL_POWER_RATIO;
    public static final ForgeConfigSpec.DoubleValue KEY_SWORD_ELEMENTAL_SPELL_POWER_RATIO;
    public static final ForgeConfigSpec.DoubleValue KEY_SWORD_EXECUTE_MAX_BONUS;
    public static final ForgeConfigSpec.DoubleValue KEY_SWORD_EXECUTE_HP_RATIO;
    public static final ForgeConfigSpec.DoubleValue KEY_BLADE_FIXED_DAMAGE;
    public static final ForgeConfigSpec.DoubleValue KEY_BLADE_ATTACK_DAMAGE_RATIO;
    public static final ForgeConfigSpec.DoubleValue KEY_BLADE_THROW_SPEED;
    public static final ForgeConfigSpec.DoubleValue KEY_BLADE_THROW_RANGE;
    public static final ForgeConfigSpec.DoubleValue KEY_BLADE_THROW_MID_FACTOR;
    public static final ForgeConfigSpec.DoubleValue KEY_BLADE_THROW_END_FACTOR;
    public static final ForgeConfigSpec.DoubleValue KEY_BLADE_ORBIT_RADIUS;
    public static final ForgeConfigSpec.IntValue KEY_BLADE_ORBIT_TICKS;
    public static final ForgeConfigSpec.DoubleValue KEY_BLADE_ORBIT_TRIGGER_RANGE;
    public static final ForgeConfigSpec.IntValue PART_CRUSHER_MATERIAL_LOSS;
    public static final ForgeConfigSpec.IntValue PART_SAWMILL_MATERIAL_LOSS;
    public static final ForgeConfigSpec.IntValue PART_SAWMILL_CLAY;
    // 古遗物联动
    public static final ForgeConfigSpec.DoubleValue PERFECTION_HEALTH_THRESHOLD;
    public static final ForgeConfigSpec.DoubleValue PERFECTION_ABSORB_FLAT_MIN;
    public static final ForgeConfigSpec.DoubleValue PERFECTION_ABSORB_FLAT_MAX;
    public static final ForgeConfigSpec.DoubleValue PERFECTION_ABSORB_PERCENT_MIN;
    public static final ForgeConfigSpec.DoubleValue PERFECTION_ABSORB_PERCENT_MAX;
    public static final ForgeConfigSpec.DoubleValue PERFECTION_GUARD_THRESHOLD;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> PERFECTION_REGEN_UPGRADES;
    public static final ForgeConfigSpec.DoubleValue MAGE_REFLECT_FLAT_MIN;
    public static final ForgeConfigSpec.DoubleValue MAGE_REFLECT_FLAT_MAX;
    public static final ForgeConfigSpec.DoubleValue MAGE_REFLECT_PERCENT_MIN;
    public static final ForgeConfigSpec.DoubleValue MAGE_REFLECT_PERCENT_MAX;
    public static final ForgeConfigSpec.DoubleValue EXECUTOR_DAMAGE_FACTOR;
    public static final ForgeConfigSpec.DoubleValue EXECUTOR_MAX_HEALTH_REDUCTION;
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

        BUILDER.push("backup_energy_upgrade");
        BACKUP_ENERGY_SHIELD_PER_LEVEL = BUILDER.comment("Max shield points per upgrade level (2 points = 1 heart)")
                .defineInRange("shield_per_level", 20.0, 1.0, 10000.0);
        BACKUP_ENERGY_RECHARGE_INTERVAL = BUILDER.comment("Ticks between shield recharges (fixed 2s = 40 ticks)")
                .defineInRange("recharge_interval", 40, 1, 72000);
        BACKUP_ENERGY_RECHARGE_PER_LEVEL = BUILDER.comment("Shield points restored per recharge interval per level")
                .defineInRange("recharge_per_level", 5.0, 0.1, 10000.0);
        BACKUP_ENERGY_FE_PER_POINT = BUILDER.comment("FE consumed from the golem per shield point restored")
                .defineInRange("fe_per_point", 50, 1, Integer.MAX_VALUE);
        BACKUP_ENERGY_HURT_COOLDOWN = BUILDER.comment("Ticks after taking damage during which the shield stops recharging (damage interrupts recharge)")
                .defineInRange("hurt_cooldown", 100, 0, 72000);
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
        RANGED_CANNON_MAGIC_BONUS = BUILDER.comment("Magic damage bonus for golems holding the Sonic Cannon (Echo Cannon) while the ranged weapon upgrade is installed; multiplier on the L2lib magic damage factor")
                .defineInRange("ranged_cannon_magic_bonus", 0.5, 0.0, 10.0);
        CAVALRY_DAMAGE_PER_SPEED = BUILDER.comment("Cavalry upgrade (枪骑, riding a golem mount): extra melee damage per 1.0 of the MOUNT's movement_speed attribute. That attribute is a per-tick movement scale, not blocks per second (0.1 = 4.317 blocks/s walking), and golem materials keep it inside 0..1, so 10.0 means +1 damage per 0.1 speed")
                .defineInRange("cavalry_damage_per_speed", 10.0, 0.0, 1000.0);
        CAVALRY_SPEAR_MULTIPLIER_PER_SPEED = BUILDER.comment("Cavalry upgrade: damage MULTIPLIER per 1.0 of the mount's movement_speed while the golem holds a golem spear (spear-type weapons are the only ones that get a multiplicative speed bonus): damage is multiplied by 1 + speed x this, so 2.0 means x1.5 at speed 0.25 and x3 at speed 1.0")
                .defineInRange("cavalry_spear_multiplier_per_speed", 2.0, 0.0, 100.0);
        CAVALRY_MAX_SPEED = BUILDER.comment("Cavalry upgrade: mount movement_speed used at most for the scaling, so an over-buffed mount cannot scale damage forever (the golem design range is 0..1)")
                .defineInRange("cavalry_max_speed", 1.0, 0.05, 10.0);
        CAVALRY_ARROW_SPEED = BUILDER.comment("Cavalry upgrade: arrow velocity multiplier for bows and crossbows fired from a golem mount (2.0 doubles the arrow speed and the vanilla velocity-based damage)")
                .defineInRange("cavalry_arrow_speed", 2.0, 1.0, 10.0);
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
        STANCE_GAUGE_MAX = BUILDER.comment("Stance gauge points needed to gain one stance stack (Black Monkey stance upgrade)")
                .defineInRange("stance_gauge_max", 100.0, 1.0, 10000.0);
        STANCE_ATTACK_GAIN = BUILDER.comment("Stance gauge gained each melee attack")
                .defineInRange("stance_attack_gain", 25.0, 0.0, 10000.0);
        STANCE_HURT_GAIN = BUILDER.comment("Stance gauge gained each time the golem is hurt")
                .defineInRange("stance_hurt_gain", 15.0, 0.0, 10000.0);
        STANCE_MOVE_GAIN = BUILDER.comment("Stance gauge gained per block the golem moves")
                .defineInRange("stance_move_gain", 3.0, 0.0, 10000.0);
        STANCE_MELEE_RATIO = BUILDER.comment("Magic damage per stance stack, as a fraction of the melee hit damage (e.g. a 100-damage hit with 3 stacks and 0.1 adds 30 magic damage)")
                .defineInRange("stance_melee_ratio", 0.5, 0.0, 100.0);
        STANCE_BOW_RATIO = BUILDER.comment("Magic damage per stance stack on arrows, multiplied by the golem's L2lib BOW_STRENGTH attribute value; e.g. 3 stacks at BOW_STRENGTH 1.5 with 4.0 adds 18 magic damage")
                .defineInRange("stance_bow_ratio", 6.0, 0.0, 1000.0);
        STANCE_SUB_IFRAME_GAIN = BUILDER.comment("Stance gauge gained each time an attack is absorbed by the golem's invulnerability frames (stance sub-upgrade); 100 = one full stack")
                .defineInRange("stance_sub_iframe_gain", 100.0, 0.0, 10000.0);
        GRAZE_RADIUS = BUILDER.comment("Radius in blocks around the golem inside which a passing projectile counts as a graze (graze upgrade)")
                .defineInRange("graze_radius", 1.5, 0.25, 16.0);
        GRAZE_SCAN_INTERVAL = BUILDER.comment("Ticks between projectile scans per golem (graze upgrade)")
                .defineInRange("graze_scan_interval", 4, 1, 100);
        GRAZE_MOVE_SPEED = BUILDER.comment("Movement speed granted on graze, as an additive modifier")
                .defineInRange("graze_move_speed", 0.05, 0.0, 10.0);
        GRAZE_MOVE_SPEED_THRESHOLD = BUILDER.comment("Golems whose movement speed already reaches this value skip the movement speed part of the graze buff")
                .defineInRange("graze_move_speed_threshold", 0.35, 0.0, 10.0);
        GRAZE_MIN_PROJECTILE_SPEED = BUILDER.comment("Minimum projectile speed (blocks/tick) for a nearby projectile to count as a graze; resting/embedded arrows are ignored")
                .defineInRange("graze_min_projectile_speed", 0.5, 0.0, 100.0);
        GRAZE_ATTACK_SPEED = BUILDER.comment("Attack speed granted on graze, as an additive modifier")
                .defineInRange("graze_attack_speed", 2.0, 0.0, 100.0);
        GRAZE_IFRAME_SECONDS = BUILDER.comment("Invulnerability granted on graze, in seconds (0.5 = half a second)")
                .defineInRange("graze_iframe_seconds", 0.5, 0.0, 10.0);
        GRAZE_BUFF_DURATION = BUILDER.comment("Duration in ticks of the graze movement/attack speed buff")
                .defineInRange("graze_buff_duration", 40, 1, 72000);
        GRAZE_COOLDOWN = BUILDER.comment("Ticks between graze grants while projectiles keep passing by")
                .defineInRange("graze_cooldown", 20, 1, 72000);
        TRAIN_BUFF_RADIUS = BUILDER.comment("Radius in blocks for the train buff: buff granting, attack scaling and damage share (train upgrade)")
                .defineInRange("train_buff_radius", 16.0, 1.0, 64.0);
        TRAIN_SHARE_RATIO = BUILDER.comment("Fraction of damage a train golem passes to a random buffed golem (train upgrade)")
                .defineInRange("train_share_ratio", 0.3, 0.0, 1.0);
        BUILDER.pop();

        BUILDER.push("key_sword");
        KEY_SWORD_SPECIAL_COOLDOWN = BUILDER.comment("Shared internal cooldown in ticks between special-attack triggers (40 ticks = 2 seconds); the execute damage bonus is not affected")
                .defineInRange("special_cooldown", 40, 0, 72000);
        KEY_SWORD_FIREBALL_COUNT_MIN = BUILDER.comment("Minimum blaze fireballs spawned per fireball trigger")
                .defineInRange("fireball_count_min", 3, 1, 16);
        KEY_SWORD_FIREBALL_COUNT_MAX = BUILDER.comment("Maximum blaze fireballs spawned per fireball trigger")
                .defineInRange("fireball_count_max", 4, 1, 16);
        KEY_SWORD_FIREBALL_FIXED = BUILDER.comment("Fixed damage of each blaze fireball")
                .defineInRange("fireball_fixed", 4.0, 0.0, 1024.0);
        KEY_SWORD_FIREBALL_ATTACK_RATIO = BUILDER.comment("Blaze fireball damage per point of the attacker's attack damage")
                .defineInRange("fireball_attack_ratio", 0.5, 0.0, 100.0);
        KEY_SWORD_LIGHTNING_FIXED = BUILDER.comment("Fixed damage of the lightning strike")
                .defineInRange("lightning_fixed", 8.0, 0.0, 1024.0);
        KEY_SWORD_LIGHTNING_ATTACK_RATIO = BUILDER.comment("Lightning damage per point of the attacker's attack damage")
                .defineInRange("lightning_attack_ratio", 0.5, 0.0, 100.0);
        KEY_SWORD_FROST_FIXED = BUILDER.comment("Fixed damage of the frost nova")
                .defineInRange("frost_fixed", 4.0, 0.0, 1024.0);
        KEY_SWORD_FROST_ATTACK_RATIO = BUILDER.comment("Frost nova damage per point of the attacker's attack damage")
                .defineInRange("frost_attack_ratio", 0.5, 0.0, 100.0);
        KEY_SWORD_FROST_RADIUS = BUILDER.comment("Radius in blocks of the frost nova freeze/push effect")
                .defineInRange("frost_radius", 4.0, 1.0, 32.0);
        KEY_SWORD_FROST_DURATION_BASE = BUILDER.comment("Base freeze/ice duration in ticks of the frost nova (the L2Complements frost effect needs a long enough duration to actually freeze; the freeze meter is always raised to at least 140 ticks = full freeze)")
                .defineInRange("frost_duration_base", 100, 1, 72000);
        KEY_SWORD_FROST_DURATION_PER_DAMAGE = BUILDER.comment("Extra freeze/ice ticks per point of the attacker's attack damage (the winterstorm effect duration scales with attack damage)")
                .defineInRange("frost_duration_per_damage", 5.0, 0.0, 1000.0);
        KEY_SWORD_SPELL_POWER_RATIO = BUILDER.comment("Generic spell power (irons_spellbooks:spell_power, default 1.0) contribution to special attack damage when Golem Magicka / Iron's Spells is installed")
                .defineInRange("spell_power_ratio", 0.5, 0.0, 100.0);
        KEY_SWORD_ELEMENTAL_SPELL_POWER_RATIO = BUILDER.comment("Elemental spell power (fire/ice/lightning_spell_power, default 1.0) contribution to special attack damage; larger than spell_power_ratio by default")
                .defineInRange("elemental_spell_power_ratio", 1.0, 0.0, 100.0);
        KEY_SWORD_EXECUTE_MAX_BONUS = BUILDER.comment("Maximum extra damage fraction of the execute effect, reached when the target is at execute_hp_ratio HP")
                .defineInRange("execute_max_bonus", 0.5, 0.0, 100.0);
        KEY_SWORD_EXECUTE_HP_RATIO = BUILDER.comment("Target HP fraction at which the execute bonus reaches its maximum")
                .defineInRange("execute_hp_ratio", 0.10, 0.01, 1.0);
        BUILDER.pop();

        BUILDER.push("key_blade_spin");
        KEY_BLADE_FIXED_DAMAGE = BUILDER.comment("Fixed damage of each enemy hit by the key blade spin special move")
                .defineInRange("fixed_damage", 6.0, 0.0, 1024.0);
        KEY_BLADE_ATTACK_DAMAGE_RATIO = BUILDER.comment("Key blade damage per point of the golem's attack damage")
                .defineInRange("attack_damage_ratio", 0.8, 0.0, 100.0);
        KEY_BLADE_THROW_SPEED = BUILDER.comment("Key blade flight speed in blocks per tick (throw mode)")
                .defineInRange("throw_speed", 1.3, 0.2, 10.0);
        KEY_BLADE_THROW_RANGE = BUILDER.comment("Distance in blocks the thrown key blade flies before turning back")
                .defineInRange("throw_range", 12.0, 3.0, 64.0);
        KEY_BLADE_THROW_MID_FACTOR = BUILDER.comment("Speed multiplier of the thrown key blade at the middle of the flight (acceleration phase; the base speed is throw_speed)")
                .defineInRange("throw_mid_factor", 1.2, 0.1, 10.0);
        KEY_BLADE_THROW_END_FACTOR = BUILDER.comment("Speed multiplier of the thrown key blade near the turning point and near the owner (deceleration phase)")
                .defineInRange("throw_end_factor", 0.4, 0.05, 1.0);
        KEY_BLADE_ORBIT_RADIUS = BUILDER.comment("Base radius in blocks of the key blade orbit around the golem; half of the golem's own width is added automatically. 0 keeps the orbit hugging the golem so the blade overlaps the golem's melee attacks; raise it to widen the circle (melee mode)")
                .defineInRange("orbit_radius", 0.5, 0.0, 16.0);
        KEY_BLADE_ORBIT_TICKS = BUILDER.comment("Ticks for one full key blade orbit around the golem (40 = 2 seconds)")
                .defineInRange("orbit_ticks", 40, 10, 200);
        KEY_BLADE_ORBIT_TRIGGER_RANGE = BUILDER.comment("Enemies at or below this distance trigger the melee orbit; farther targets trigger the throw")
                .defineInRange("orbit_trigger_range", 3.5, 1.0, 32.0);
        BUILDER.pop();

        BUILDER.push("part_recycling");
        PART_CRUSHER_MATERIAL_LOSS = BUILDER.comment("Materials lost when crushing a golem part (less loss than sawmilling)")
                .defineInRange("crusher_material_loss", 1, 0, 64);
        PART_SAWMILL_MATERIAL_LOSS = BUILDER.comment("Materials lost when sawmilling a golem part (more loss than crushing)")
                .defineInRange("sawmill_material_loss", 2, 0, 64);
        PART_SAWMILL_CLAY = BUILDER.comment("Clay balls produced as a byproduct when sawmilling a golem part (the golem template recipe uses 4 clay)")
                .defineInRange("sawmill_clay", 4, 0, 64);
        BUILDER.pop();

        BUILDER.push("artifact_synergy");
        PERFECTION_HEALTH_THRESHOLD = BUILDER.comment("Perfection synergy (regeneration upgrade lv5 + 4-piece perfection set): health fraction at or below which the stored regeneration bursts into absorption. Keep above 0.5")
                .defineInRange("perfection_health_threshold", 0.65, 0.5, 1.0);
        PERFECTION_ABSORB_FLAT_MIN = BUILDER.comment("Perfection synergy: flat part of the absorption cap at rarity tier 1")
                .defineInRange("perfection_absorb_flat_min", 2.0, 0.0, 1000.0);
        PERFECTION_ABSORB_FLAT_MAX = BUILDER.comment("Perfection synergy: flat part of the absorption cap at rarity tier 5")
                .defineInRange("perfection_absorb_flat_max", 10.0, 0.0, 1000.0);
        PERFECTION_ABSORB_PERCENT_MIN = BUILDER.comment("Perfection synergy: max-health percentage part of the absorption cap at rarity tier 1")
                .defineInRange("perfection_absorb_percent_min", 0.01, 0.0, 10.0);
        PERFECTION_ABSORB_PERCENT_MAX = BUILDER.comment("Perfection synergy: max-health percentage part of the absorption cap at rarity tier 5")
                .defineInRange("perfection_absorb_percent_max", 0.03, 0.0, 10.0);
        PERFECTION_GUARD_THRESHOLD = BUILDER.comment("Perfection synergy: absorption points the golem must hold before the damage limit kicks in. Once it holds at least this many, an incoming hit deals at most the golem's current absorption, so the absorption eats the hit whole and no health is lost. Tiers whose absorption cap stays below this value never reach it; 0 disables the limit")
                .defineInRange("perfection_guard_threshold", 5.0, 0.0, 1000.0);
        PERFECTION_REGEN_UPGRADES = BUILDER.comment("Perfection synergy: modifier ids that count as the golem's regeneration upgrade. Addon mods often ship their own \"Regeneration V\" apple upgrade under a different id (the MGDP netherite gold apples register modulargolems:netherite_gold and modulargolems:enchanted_netherite_gold), so all of them are listed here. The highest level found across these ids has to reach 5")
                .defineList("perfection_regen_upgrades", List.of(
                                "modulargolems:regeneration_up",
                                "modulargolems:netherite_gold",
                                "modulargolems:enchanted_netherite_gold"),
                        o -> o instanceof String);
        MAGE_REFLECT_FLAT_MIN = BUILDER.comment("Mage synergy (soul flame upgrade lv3 + 4-piece mage set): flat reflect damage at rarity tier 1")
                .defineInRange("mage_reflect_flat_min", 2.0, 0.0, 1000.0);
        MAGE_REFLECT_FLAT_MAX = BUILDER.comment("Mage synergy: flat reflect damage at rarity tier 5")
                .defineInRange("mage_reflect_flat_max", 10.0, 0.0, 1000.0);
        MAGE_REFLECT_PERCENT_MIN = BUILDER.comment("Mage synergy: reflect damage per point of the golem's missing health at rarity tier 1")
                .defineInRange("mage_reflect_percent_min", 0.30, 0.0, 10.0);
        MAGE_REFLECT_PERCENT_MAX = BUILDER.comment("Mage synergy: reflect damage per point of the golem's missing health at rarity tier 5")
                .defineInRange("mage_reflect_percent_max", 0.50, 0.0, 10.0);
        EXECUTOR_DAMAGE_FACTOR = BUILDER.comment("Executor synergy (damage upgrade lv5 + 5-piece executor set): additive damage multiplier per point of the target's missing health fraction (0.3 = +30% against an empty-health target)")
                .defineInRange("executor_damage_factor", 0.30, 0.0, 100.0);
        EXECUTOR_MAX_HEALTH_REDUCTION = BUILDER.comment("Executor synergy: permanent max health reduction taken as the cost of the bonus")
                .defineInRange("executor_max_health_reduction", 0.10, 0.0, 1.0);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private Config() {
    }
}
