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
    public static final ForgeConfigSpec.IntValue ENERGY_KATANA_SHRED_DURATION;
    public static final ForgeConfigSpec.IntValue ENERGY_KATANA_SHRED_MAX_STACKS;
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
    // ===== 合金能源链（步长制）=====
    public static final ForgeConfigSpec.IntValue ENERGY_ALLOY_STEP;
    // ===== 辐射发电 / 屏蔽（步长制）=====
    public static final ForgeConfigSpec.IntValue RADIATION_FE_PER_SEVERITY;
    public static final ForgeConfigSpec.IntValue RADIATION_STEP;
    public static final ForgeConfigSpec.DoubleValue RADIATION_MIN_DOSE;
    public static final ForgeConfigSpec.DoubleValue RADIATION_MAX_SEVERITY;
    public static final ForgeConfigSpec.DoubleValue BACKUP_ENERGY_SHIELD_PER_LEVEL;
    public static final ForgeConfigSpec.DoubleValue BACKUP_ENERGY_SHIELD_RATIO;
    public static final ForgeConfigSpec.IntValue BACKUP_ENERGY_RECHARGE_INTERVAL;
    public static final ForgeConfigSpec.DoubleValue BACKUP_ENERGY_RECHARGE_PER_LEVEL;
    public static final ForgeConfigSpec.DoubleValue BACKUP_ENERGY_RECHARGE_RATIO;
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
    public static final ForgeConfigSpec.IntValue FLAME_FIST_HITS_LV1;
    public static final ForgeConfigSpec.IntValue FLAME_FIST_HITS_LV2;
    public static final ForgeConfigSpec.DoubleValue FLAME_FIST_RATIO;
    public static final ForgeConfigSpec.IntValue FLAME_FIST_COOLDOWN;
    public static final ForgeConfigSpec.IntValue FLAME_FIST_BRAND_REFRESH;
    public static final ForgeConfigSpec.DoubleValue MEROR_AFTERGLOW_PER_STACK_LV1;
    public static final ForgeConfigSpec.DoubleValue MEROR_AFTERGLOW_PER_STACK_LV2;
    public static final ForgeConfigSpec.DoubleValue MEROR_AFTERGLOW_RESTITUTION_LV1;
    public static final ForgeConfigSpec.DoubleValue MEROR_AFTERGLOW_RESTITUTION_LV2;
    public static final ForgeConfigSpec.DoubleValue ATTRITION_STEP_LV1;
    public static final ForgeConfigSpec.DoubleValue ATTRITION_STEP_LV2;
    public static final ForgeConfigSpec.IntValue ATTRITION_EXPIRY_TICKS;
    public static final ForgeConfigSpec.DoubleValue GRAZE_RADIUS;
    public static final ForgeConfigSpec.IntValue GRAZE_SCAN_INTERVAL;
    // ===== 天基激光（第 1 刀：光束本体）=====
    public static final ForgeConfigSpec.IntValue ORBITAL_BEAM_ALTITUDE;
    public static final ForgeConfigSpec.DoubleValue ORBITAL_BEAM_DAMAGE;
    public static final ForgeConfigSpec.DoubleValue ORBITAL_BEAM_RESIDUAL_DAMAGE;
    public static final ForgeConfigSpec.DoubleValue ORBITAL_BEAM_HIT_RADIUS;
    public static final ForgeConfigSpec.IntValue ORBITAL_BEAM_LIFE;
    public static final ForgeConfigSpec.DoubleValue ORBITAL_BEAM_WIDTH;
    // ===== 天基激光（第 2 刀：引导升级）=====
    /** 破坏地形（负面代价）。我们自己挖，不用 Mekanism 的激光。 */
    public static final ForgeConfigSpec.BooleanValue ORBITAL_BEAM_BREAK_BLOCKS;
    public static final ForgeConfigSpec.DoubleValue ORBITAL_BEAM_MAX_BLOCK_HARDNESS;
    public static final ForgeConfigSpec.DoubleValue ORBITAL_BEAM_BLOCK_HARDNESS_COST;
    public static final ForgeConfigSpec.BooleanValue ORBITAL_BEAM_DROP_BLOCKS;
    // ===== 火箭（天基平台的载具）=====
    public static final ForgeConfigSpec.IntValue ROCKET_COUNTDOWN;
    public static final ForgeConfigSpec.IntValue ROCKET_FUEL_CAPACITY;
    public static final ForgeConfigSpec.IntValue ROCKET_FUEL_COST;
    public static final ForgeConfigSpec.DoubleValue ROCKET_EXPLOSION_POWER;
    public static final ForgeConfigSpec.BooleanValue ROCKET_EXPLOSION_FIRE;
    /** 只在战斗时引导 + 只引导真实威胁。 */
    public static final ForgeConfigSpec.BooleanValue ORBITAL_BEAM_COMBAT_ONLY;
    public static final ForgeConfigSpec.IntValue ORBITAL_BEAM_COMBAT_MEMORY;
    public static final ForgeConfigSpec.IntValue ORBITAL_BEAM_GUIDANCE_STEP;
    public static final ForgeConfigSpec.DoubleValue ORBITAL_BEAM_GUIDANCE_RANGE;
    public static final ForgeConfigSpec.DoubleValue ORBITAL_BEAM_MIN_RANGE;
    public static final ForgeConfigSpec.IntValue ORBITAL_BEAM_SCAN_INTERVAL;
    public static final ForgeConfigSpec.IntValue ORBITAL_BEAM_COOLDOWN;
    public static final ForgeConfigSpec.DoubleValue ORBITAL_BEAM_DAMAGE_PER_LEVEL;
    public static final ForgeConfigSpec.IntValue ORBITAL_BEAM_COST_BASE;
    public static final ForgeConfigSpec.IntValue ORBITAL_BEAM_COST_PER_LEVEL;
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
    public static final ForgeConfigSpec.IntValue PART_CRUSHER_YIELD_PERCENT;
    public static final ForgeConfigSpec.IntValue PART_SAWMILL_YIELD_PERCENT;
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
    // 指令升级（Command）：只有这两个旋钮需要调
    public static final ForgeConfigSpec.IntValue COMMAND_MAX_LEVEL;
    public static final ForgeConfigSpec.IntValue COMMAND_COOLDOWN;

    // 伤害诊断探针（只读，不修改任何伤害）
    public static final ForgeConfigSpec.BooleanValue DAMAGE_PROBE_ENABLED;
    public static final ForgeConfigSpec.BooleanValue DAMAGE_PROBE_ONLY_GOLEM_ATTACKS;
    public static final ForgeConfigSpec.BooleanValue DAMAGE_PROBE_LOG_EVERY_HIT;
    public static final ForgeConfigSpec.IntValue DAMAGE_PROBE_SUMMARY_EVERY;
    public static final ForgeConfigSpec.IntValue DAMAGE_PROBE_INTERVAL;
    public static final ForgeConfigSpec.IntValue DAMAGE_PROBE_MAX_HEARTBEATS;
    public static final ForgeConfigSpec SPEC;

    static {
        BUILDER.push("energy_katana");
        ENERGY_KATANA_ATTACK_COST = BUILDER.comment("FE consumed by each powered hit")
                .defineInRange("attack_cost", 2_000, 0, Integer.MAX_VALUE);
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
        ENERGY_KATANA_SHRED_DURATION = BUILDER.comment("Armor-shred duration (ticks) applied to the target by a powered katana hit")
                .defineInRange("shred_duration", 200, 1, 72_000);
        ENERGY_KATANA_SHRED_MAX_STACKS = BUILDER.comment("Maximum armor-shred stacks; each stack strips the effect's per-level amount of vanilla armor points")
                .defineInRange("shred_max_stacks", 5, 1, 20);
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
        GOLEM_ENERGY_BASE_CAPACITY = BUILDER.comment("Base FE capacity of the golem energy pool (the base tier of the alloy chain). The alloy tier multiplier is applied on top of this by energy_alloy_upgrade.step")
                .defineInRange("base_capacity", 300_000, 0, Integer.MAX_VALUE);
        GOLEM_ENERGY_PER_LEVEL = BUILDER.comment("LEGACY: extra FE capacity per level of the old 5-level energy upgrade. The base upgrade is level 1 now and the alloy chain carries the scaling, so this is 0 by default and only exists so old config files keep loading. Base capacity is base_capacity + this")
                .defineInRange("capacity_per_level", 0, 0, Integer.MAX_VALUE);
        GOLEM_ENERGY_LIGHTNING = BUILDER.comment("FE restored when a golem is struck by lightning")
                .defineInRange("lightning_charge", 50_000, 0, Integer.MAX_VALUE);
        GOLEM_ENERGY_TRANSFER_RATE = BUILDER.comment("FE transferred per charge pass from a golem to each powered item (one pass every 5 ticks). The alloy energy chain multiplies this together with the capacity, so raising the alloy step raises both")
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

        // ============================================================================
        // 合金能源链（步长制）。
        //
        // 设计：基础电量升级（max 1 级）是链的根，占一个槽；三件合金能源升级是它的附属，
        // **不占额外槽位**（consumesSlot()=false），各自往同一个 golem_energy 修饰符上
        // 加 2/4/8 点等级。等级累加由 Modular Golems 的 GolemMaterial.collectModifiers 完成，
        // 所以"装了几档"完全由一个整数表达：
        //
        //     总等级 L = 1 + 2(灌注) + 4(精英) + 8(终极)     满配 L = 15
        //     合金加成 = max(0, L − 1) × step                 满配 = 14 × step
        //     乘数     = 1 + 合金加成                          step=2 时 = 29
        //
        // 步长制的意义：这里**只有 step 一个旋钮**，容量与带宽共用它。
        // 各档的"档位系数"是 2 的幂次常量（写在代码里），所以任意两档之差
        // = step × 2^k > 0 与 step 无关 —— 无论玩家把 step 调成几，
        // "终极 > 精英 > 灌注 > 基础"都不会倒挂。详见 docs/MEK-ENERGY-RADIATION-DESIGN.md §2。
        // ============================================================================
        BUILDER.push("energy_alloy_upgrade");
        ENERGY_ALLOY_STEP = BUILDER.comment("Step size of the alloy energy chain: each installed alloy tier adds (tier coefficient x step), where the tier coefficients are the constants 1 / 2 / 4. The same step multiplies BOTH the golem's FE capacity and its charge throughput (equipment_transfer_rate). Default 2 gives x29 at full installation (step=1 -> x15, step=4 -> x57). Raising it keeps the tier ordering intact by construction")
                .defineInRange("step", 2, 1, 64);
        BUILDER.pop();

        // ============================================================================
        // 辐射发电 / 辐射屏蔽（步长制）。
        //
        // 两条升级共用一条曲线，这是刻意的：屏蔽值越高，发电机出力越低。
        //
        //     加权      = 1 + (本档系数 − 1) × step
        //     发电倍率  = 加权
        //     屏蔽值    = clamp(加权 / shield_denominator, 0, 1)
        //
        // 档位系数是常量（发电 1/2/4，屏蔽 1/3/7）—— 见 RadiationUpgradeItems。
        //
        // 发电量为 0 需要两个条件同时成立，缺一不可：
        //   ① 环境剂量低于 min_dose（干净环境的基线是 1.0e-7，实测自 DefaultRadiationEntity）
        //   ② severity 公式在低剂量下自然趋近 0
        // 默认 min_dose = 1.0e-4（约 0.1 mSv/h，盖革刻度的 LOW 档），
        // 所以"野外打架"永远是 0，"站在自家核电站/废料库旁"才有收益。
        // ============================================================================
        BUILDER.push("radiation");
        RADIATION_FE_PER_SEVERITY = BUILDER.comment("FE per tick generated at severity = 1.0 by the BASIC radiation generator tier. Higher tiers multiply this by (1 + (tier coefficient - 1) x step). 1 FE = 2.5 J, so the Mekanism equivalent is 2.5x this number")
                .defineInRange("fe_per_severity", 20, 0, 100_000);
        RADIATION_STEP = BUILDER.comment("Step size of the radiation generator chain: each tier adds (tier coefficient x step) to the multiplier, where the coefficients are the constants 1 / 2 / 4. Default 3 gives multipliers 4 / 7 / 13. Raising this makes the curve steeper")
                .defineInRange("step", 3, 1, 64);
        RADIATION_MIN_DOSE = BUILDER.comment("Environmental dose (Sv/h) below which the generator produces nothing at all. Clean air sits at the Mekanism baseline 1.0e-7, so any value well above that keeps ordinary battlefields at zero output. Lower it to make weak radiation fields usable")
                .defineInRange("min_dose", 1.0E-4, 0.0, 1.0E6);
        RADIATION_MAX_SEVERITY = BUILDER.comment("Clamp on the severity used for generation. severity = clamp((log10(dose) + 6) / 7, 0, 1) by default; lowering this caps the income in extreme fields (a meltdown site sits around 1.0)")
                .defineInRange("max_severity", 1.0, 0.0, 1.0);
        BUILDER.pop();

        BUILDER.push("backup_energy_upgrade");
        BACKUP_ENERGY_SHIELD_PER_LEVEL = BUILDER.comment("LEGACY: max shield points per upgrade level. Superseded by shield_ratio below (kept so old configs still load)")
                .defineInRange("shield_per_level", 20.0, 1.0, 10000.0);
        BACKUP_ENERGY_SHIELD_RATIO = BUILDER.comment("Max shield as a fraction of the golem's max health (0.20 = 20%)")
                .defineInRange("shield_ratio", 0.20, 0.01, 10.0);
        BACKUP_ENERGY_RECHARGE_INTERVAL = BUILDER.comment("Ticks between shield recharges (fixed 2s = 40 ticks)")
                .defineInRange("recharge_interval", 40, 1, 72000);
        BACKUP_ENERGY_RECHARGE_PER_LEVEL = BUILDER.comment("LEGACY: shield points restored per interval per level. Superseded by recharge_ratio below")
                .defineInRange("recharge_per_level", 5.0, 0.1, 10000.0);
        BACKUP_ENERGY_RECHARGE_RATIO = BUILDER.comment("Shield restored per recharge interval, as a fraction of the golem's max health (0.05 = 5% every 2s)")
                .defineInRange("recharge_ratio", 0.05, 0.001, 10.0);
        BACKUP_ENERGY_FE_PER_POINT = BUILDER.comment("FE consumed from the golem per shield point restored")
                .defineInRange("fe_per_point", 25, 1, Integer.MAX_VALUE);
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
        FLAME_FIST_HITS_LV1 = BUILDER.comment("Flame fist (炎拳): extra hits dealt on a melee hit against a target afflicted with Cataclysm's Blazing Brand, at level 1")
                .defineInRange("flame_fist_hits_lv1", 2, 0, 16);
        FLAME_FIST_HITS_LV2 = BUILDER.comment("Flame fist: extra hits at level 2")
                .defineInRange("flame_fist_hits_lv2", 3, 0, 16);
        FLAME_FIST_RATIO = BUILDER.comment("Flame fist: damage of each extra hit, as a fraction of the triggering melee hit damage")
                .defineInRange("flame_fist_ratio", 0.35, 0.0, 10.0);
        FLAME_FIST_COOLDOWN = BUILDER.comment("Flame fist: ticks between two flame fist volleys against the SAME target (per-target). Default 5 matches the combo stage interval so every stage of a three-hit combo can trigger; raise it to turn this into a rate limit")
                .defineInRange("flame_fist_cooldown", 5, 0, 12000);
        FLAME_FIST_BRAND_REFRESH = BUILDER.comment("Flame fist level 2: ticks the Blazing Brand is refreshed to on the final hit of a volley; 0 disables refreshing")
                .defineInRange("flame_fist_brand_refresh", 60, 0, 12000);
        MEROR_AFTERGLOW_PER_STACK_LV1 = BUILDER.comment("Meror afterglow level 1: melee damage bonus per stack of Meror Energy, as a fraction (max 5 stacks)")
                .defineInRange("meror_afterglow_per_stack_lv1", 0.05, 0.0, 5.0);
        MEROR_AFTERGLOW_PER_STACK_LV2 = BUILDER.comment("Meror afterglow level 2: damage bonus per Meror Energy stack")
                .defineInRange("meror_afterglow_per_stack_lv2", 0.08, 0.0, 5.0);
        MEROR_AFTERGLOW_RESTITUTION_LV1 = BUILDER.comment("Meror afterglow level 1: END restitution granted (added into golems_arsenal:end_restitution, still capped at 0.8)")
                .defineInRange("meror_afterglow_restitution_lv1", 0.08, 0.0, 0.8);
        MEROR_AFTERGLOW_RESTITUTION_LV2 = BUILDER.comment("Meror afterglow level 2: END restitution granted")
                .defineInRange("meror_afterglow_restitution_lv2", 0.12, 0.0, 0.8);
        ATTRITION_STEP_LV1 = BUILDER.comment("Attrition level 1: fraction of the remaining shortfall added to the next hit (0.4 = the bonus approaches the target's full attack power gradually). There is no maximum bonus constant: accumulation stops on its own once the damage taken reaches the golem's attack power")
                .defineInRange("attrition_step_lv1", 0.4, 0.0, 1.0);
        ATTRITION_STEP_LV2 = BUILDER.comment("Attrition level 2: shortfall fraction per hit")
                .defineInRange("attrition_step_lv2", 0.4, 0.0, 1.0);
        ATTRITION_EXPIRY_TICKS = BUILDER.comment("Attrition: ticks without hitting that specific enemy before its record is treated as expired (400 = 20 seconds, independent of switching targets)")
                .defineInRange("attrition_expiry_ticks", 400, 1, 24000);
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
                .defineInRange("execute_max_bonus", 2.0, 0.0, 100.0);
        KEY_SWORD_EXECUTE_HP_RATIO = BUILDER.comment("Target HP fraction at which the execute bonus reaches its maximum")
                .defineInRange("execute_hp_ratio", 0.20, 0.01, 1.0);
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
        PART_CRUSHER_YIELD_PERCENT = BUILDER
                .comment("Percent of a golem part's crafting material recovered by the Mekanism crusher (rounded UP)",
                        "50 = half. Rounding: crusher rounds up, sawmill rounds down.")
                .defineInRange("crusher_yield_percent", 50, 0, 100);
        PART_SAWMILL_YIELD_PERCENT = BUILDER
                .comment("Percent of a golem part's crafting material recovered by the Mekanism sawmill (rounded DOWN)",
                        "50 = half. Sawmilling gives up to 1 less than crushing, but also yields clay.")
                .defineInRange("sawmill_yield_percent", 50, 0, 100);
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

        // ============================================================================
        // 指令升级（Command）。
        //
        // 触发：命中时若不在冷却中，从九个效果里抽 N 个（N = 等级，被 max_level 夹住）并全部获得。
        // 效果带时长，到期消失，需要重新命中才能再抽。
        //
        // 这里<b>只留两个旋钮</b>：等级上限、重抽冷却。
        // 九个效果的强度是设计值，写在 CommandEffects 的 STR_* 常量里；
        // 时长跟随等级，写在 CommandEffects.durationTicks 里。
        // 把它们做成配置项只会让配置文件长出十七行只影响内部平衡的东西，
        // 反而把真正需要调的这两个淹没掉。
        //
        // 效果本身提炼自已删除的「军械手套」九个武器格。
        // ============================================================================
        BUILDER.push("command");
        COMMAND_MAX_LEVEL = BUILDER.comment("Maximum level (1..9). Each level draws one more effect per trigger, so 9 = the whole pool. NOTE: Modular Golems ADDS UP the levels of stacked upgrades, so three level-1 items reach level 3 - this cap is what stops that from going past your intent")
                .defineInRange("max_level", 3, 1, 9);
        COMMAND_COOLDOWN = BUILDER.comment("Cooldown in ticks between two draws. 200 = 10 seconds. Effects already granted keep running through the cooldown - this only limits how often you can re-draw. Duration itself follows the level: 100 ticks at level 1, +60 per extra level")
                .defineInRange("cooldown", 200, 0, 72_000);
        BUILDER.pop();

        BUILDER.push("damage_probe");
        DAMAGE_PROBE_ENABLED = BUILDER.comment("Read-only damage diagnostic. It never changes any damage; it only measures how much of a hit survived the target's reductions and prints a summary. Safe to leave on, but it does keep a small per-target-type tally")
                .define("enabled", true);
        DAMAGE_PROBE_ONLY_GOLEM_ATTACKS = BUILDER.comment("Only record hits whose attacker is a modulargolems golem. Turn off to measure everything, which is much noisier")
                .define("only_golem_attacks", true);
        DAMAGE_PROBE_LOG_EVERY_HIT = BUILDER.comment("Log every recorded hit individually. Very spammy; the summary below is usually what you want")
                .define("log_every_hit", false);
        DAMAGE_PROBE_SUMMARY_EVERY = BUILDER.comment("Print the running summary after this many recorded hits, then reset the tally. Kept low on purpose: a high value makes the probe look dead when nothing is being recorded")
                .defineInRange("summary_every", 25, 1, 1_000_000);
        DAMAGE_PROBE_INTERVAL = BUILDER.comment("Also print the summary at least this often (ticks) even when the hit count above is not reached. 600 = every 30 seconds")
                .defineInRange("interval", 600, 20, 72_000);
        DAMAGE_PROBE_MAX_HEARTBEATS = BUILDER.comment("How many consecutive empty intervals to announce before going quiet. This is what tells you the probe is alive but seeing no hits; 0 disables the heartbeat")
                .defineInRange("max_heartbeats", 20, 0, 1_000_000);
        BUILDER.pop();

        // ============================================================================
        // 天基激光（第 1 刀：光束本体 + 伤害）。
        //
        // 这一刀刻意不依赖任何"在轨状态"：调试命令直接指定目标就能开火，用来先验证
        // 渲染、伤害、击杀归属这三件最容易做错的事。引导升级与发射台在后续刀里加。
        //
        // 关于高度：1.20.1 的世界建筑上限是 y=320（主世界），所以默认就取 320 而不是
        // Ad Astra 的 600 —— 后者在 1.20.1 是超出世界高度的。光束的来源在观感上是"天基"，
        // 在实现上只是"从建筑上限往下打"。
        // ============================================================================
        BUILDER.push("orbital_beam");
        ORBITAL_BEAM_ALTITUDE = BUILDER.comment("Y coordinate the beam visually descends FROM. 1.20.1's overworld build limit is 320, so that is the default; raising it past the world height changes nothing visually")
                .defineInRange("altitude", 320, 64, 4096);
        ORBITAL_BEAM_DAMAGE = BUILDER.comment("Damage per tick dealt to each enemy standing in the beam column. 2.0 = one heart per tick, which kills most mobs almost instantly: this is meant to be a costly, deliberate strike, not a sustained aura. Set 0 for a purely visual test")
                .defineInRange("damage", 2.0, 0.0, 1000.0);

        // 炮的主伤害是落地那一下的冲击波；存活期间的只是固定余热（用户 2026-10-19 定）。
        ORBITAL_BEAM_RESIDUAL_DAMAGE = BUILDER
                .comment("Damage per tick while the beam is still burning (RESIDUAL heat, does not decay)",
                        "This is intentionally small: the real hit is orbital_beam.damage (one-shot impact).")
                .defineInRange("residual_damage", 0.5D, 0.0D, 100.0D);
        ORBITAL_BEAM_HIT_RADIUS = BUILDER.comment("Radius of the damaging column around the impact point (blocks). The rendered beam is much thinner than this - the visual width is cosmetic")
                .defineInRange("hit_radius", 1.5, 0.0, 16.0);
        ORBITAL_BEAM_LIFE = BUILDER.comment("How long the beam stays (ticks). Deliberately SHORT so it reads as a strike rather than a lingering zone. 40 ticks = 2 seconds, which at the default feed_rate is enough to actually mine a couple of blocks - a 20-tick beam cannot, because the Mekanism laser only accumulates energy while it lives")
                .defineInRange("life", 40, 1, 72000);
        ORBITAL_BEAM_WIDTH = BUILDER.comment("Rendered beam width (blocks) = the OUTER prism's width. The renderer draws two nested four-sided prisms: a wide translucent outer one at this width, plus a bright inner one at 40% of it. This is purely cosmetic - the damaging column radius is hit_radius")
                .defineInRange("width", 0.6, 0.05, 8.0);
        BUILDER.pop();

        // ============================================================================
        // 天基激光（第 2 刀：引导升级）。
        //
        // 傀儡不自己开火，而是"标定"看得见的敌人，由在轨平台打下来。三条设计约束都落在这里：
        //   ① min_range 之内标定不了  → 贴身混战里不许它输出，且给了"贴脸"这一反制手段
        //   ② 目标被方块挡住就不打     → 掩体有意义（判定见 GolemOrbitalGuidanceModifier#canSee）
        //   ③ 每段扣傀儡的 FE          → 合金能源链直接决定你能打几轮，两条升级线互为前提
        //
        // 等级换算与合金能源链同一套步长制，只有 step 一个旋钮（档位系数 1/2/4 是代码常量）：
        //   可同时引导数 = 1 + 档位之和 × step        满配 15
        //   单次打击段数 = 同上（目标少 → 段数全砸在首目标上，单挑最猛）
        //   单段伤害     = damage + 等级 × damage_per_level
        //   单段 FE      = cost_base + 等级 × cost_per_level
        // ============================================================================
        BUILDER.push("orbital_guidance");
        ORBITAL_BEAM_GUIDANCE_STEP = BUILDER.comment("Step size of the guidance alloy chain - the ONLY knob for target count and volley count. Installed alloy tiers contribute coefficients 1/2/4, so the fixed points are 1 (core only), 3, 7, 15. At step=1 full installation means 15 simultaneous targets. Higher tiers keep a larger value than lower ones at any step, by construction")
                .defineInRange("step", 1, 1, 64);
        ORBITAL_BEAM_GUIDANCE_RANGE = BUILDER.comment("How far the golem can designate targets (blocks). Also the maximum distance at which the platform will engage - a spotter that cannot see it cannot call it in")
                .defineInRange("range", 32.0, 4.0, 256.0);
        ORBITAL_BEAM_MIN_RANGE = BUILDER.comment("Targets closer than this CANNOT be designated (blocks). This is what keeps the upgrade from being extra melee damage: enemies that hug the golem are simply not engageable. Set to 0 to remove that limitation")
                .defineInRange("min_range", 5.0, 0.0, 64.0);
        ORBITAL_BEAM_SCAN_INTERVAL = BUILDER.comment("Ticks between two designation scans. 20 = once a second. Raising it makes the golem slower to switch targets")
                .defineInRange("scan_interval", 20, 1, 1200);
        ORBITAL_BEAM_COOLDOWN = BUILDER.comment("Ticks between two strike rounds. Every round fires every designated target at once. Deliberately LONG (120 = 6 seconds) so the platform reads as a called-in strike rather than a repeating turret - and so it cannot keep grinding the same patch of terrain")
                .defineInRange("cooldown", 120, 1, 7200);
        ORBITAL_BEAM_DAMAGE_PER_LEVEL = BUILDER.comment("Extra damage per guidance level, added to orbital_beam.damage. At max level (15) this is multiplied by 15, so keep it small: 1.0 already means +15 damage per strike")
                .defineInRange("damage_per_level", 1.0, 0.0, 100.0);
        ORBITAL_BEAM_COST_BASE = BUILDER.comment("Base FE cost of ONE strike (one volley against one target), charged to the golem's own FE pool. The whole point is that the alloy energy chain decides how many rounds you can afford - if this is 0 the two upgrade lines stop being connected")
                .defineInRange("cost_base", 20000, 0, Integer.MAX_VALUE);
        ORBITAL_BEAM_COST_PER_LEVEL = BUILDER.comment("Extra FE per strike per guidance level. At max level (14) this is multiplied by 14. Note a round costs this PER TARGET, so wide designation drains proportionally faster")
                .defineInRange("cost_per_level", 5000, 0, Integer.MAX_VALUE);
        // ---- 破坏地形：负面代价（我们自己挖） ----
        //
        // 试过委托给 Mekanism 的 mekanism:laser 方块，撤回了：它的破坏受三重限制——
        //   射程     [general.laser] range（默认 64，得让玩家改配置才够得到地面）
        //   耗能     energyNeededPerHardness × 硬度（默认 100000/格，和我们的 FE 量级对不上）
        //   还得在世界上额外挂一个激光方块实体
        // 自建更简单也更可控，代价只是"这块行为是我们写的，不是抄的"。
        ORBITAL_BEAM_BREAK_BLOCKS = BUILDER.comment("Let the beam DESTROY terrain. This is deliberately a downside, not a mining bonus: firing near your own base chews holes in it and ruins farmland. Turn off if you want the beam to be purely a weapon")
                .define("break_blocks", true);
        ORBITAL_BEAM_MAX_BLOCK_HARDNESS = BUILDER.comment("Highest block hardness the beam can dig through. Reference points: grass/plants/torch 0, dirt/sand/gravel 0.5-0.6, stone 1.5, wood 2.0, obsidian 50. So 2.0 eats soil, plants and wood but stops dead at stone; lower it to 1.0 to also protect stone. Negative-hardness blocks (bedrock and friends) are always immune")
                .defineInRange("max_block_hardness", 2.0, 0.0, 100.0);
        ORBITAL_BEAM_BLOCK_HARDNESS_COST = BUILDER.comment("Digging effort per point of block hardness. Progress per tick = damage / (cost x hardness), and a block breaks at progress 1.0. So the ticks needed for one block = cost x hardness / damage. With the defaults (cost 40, damage 2.0): a dirt block (0.5) takes 10 ticks, wood (2.0) takes 40 ticks, and grass (hardness 0) is cleared instantly. The whole beam lives 40 ticks, so one strike digs about 2 blocks of soil or 1 of wood - raise this value for slower digging, lower it to chew faster")
                .defineInRange("block_hardness_cost", 40.0, 1.0, 10000.0);
        ORBITAL_BEAM_DROP_BLOCKS = BUILDER.comment("Whether blocks destroyed by the beam drop their items. Default false: the platform is a weapon, not a quarry, so chewing terrain should not pay for itself")
                .define("drop_blocks", false);
        // ---- 只在战斗时引导 ----
        ORBITAL_BEAM_COMBAT_ONLY = BUILDER.comment("Only designate targets while the golem is actually in combat (it has a target, or something has hit it recently). This is what stops it from firing at random wildlife while idle - and it is also the main lever that keeps the beam from constantly grinding the terrain")
                .define("combat_only", true);
        ORBITAL_BEAM_COMBAT_MEMORY = BUILDER.comment("How long (ticks) the golem still counts as 'in combat' after the last hit it took or dealt. 200 = 10 seconds. Only used when combat_only is on")
                .defineInRange("combat_memory", 200, 0, 72000);
        BUILDER.pop();

        // ============================================================================
        // 火箭（天基平台的载具）。流程见 docs/ORBITAL-LASER-DESIGN.md §2：
        //   放置（火箭物品）→ 加燃料（UI）→ 按发射 → 倒计时 → 升空 → 入轨
        // 燃料是 Mekanism 的氢气（Gas-Burning Generator 只烧氢气与乙烯，氢气最易得）。
        // ============================================================================
        BUILDER.push("rocket");
        ROCKET_COUNTDOWN = BUILDER.comment("Countdown before lift-off (ticks). The countdown belongs to the rocket ENTITY, so if the entity disappears the countdown disappears with it. 200 = 10 seconds")
                .defineInRange("countdown", 200, 0, 72000);
        ROCKET_FUEL_CAPACITY = BUILDER.comment("Rocket fuel tank capacity (mB). Mekanism's Gas-Burning Generator tank holds 18000 mB, so this is a bit over half of one of those")
                .defineInRange("fuel_capacity", 10000, 1, 1_000_000);
        ROCKET_FUEL_COST = BUILDER.comment("Hydrogen consumed per launch (mB). One-shot item, so keep it modest - at 1000 mB a single Gas-Burning Generator tank fuels 18 launches")
                .defineInRange("fuel_cost", 1000, 1, 1_000_000);
        ROCKET_EXPLOSION_POWER = BUILDER.comment("Explosion power when the rocket hits something during ascent. Vanilla TNT is 4.0; a rocket is deliberately nastier. Set to 0 to disable the explosion (the rocket still stops)")
                .defineInRange("explosion_power", 8.0, 0.0, 100.0);
        ROCKET_EXPLOSION_FIRE = BUILDER.comment("Whether the ascent collision explosion starts fires, like a TNT explosion does")
                .define("explosion_fire", true);
        // 火箭模型的垂直落位校准值（实测 +5 格）写在了 RocketRenderer.MODEL_ANCHOR_CALIBRATION，
        // 不作为配置项暴露——配置项之后会做一次统合简化。
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private Config() {
    }
}
