package a_silly_cat.golems_arsenal.base.event;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.tech.item.GolemEnergyKatanaItem;
import a_silly_cat.golems_arsenal.tech.item.GolemEnergyHammerItem;
import a_silly_cat.golems_arsenal.tech.item.GolemTrackingMechanicalBowItem;
import a_silly_cat.golems_arsenal.tech.item.WeaponUpgradeData;
import a_silly_cat.golems_arsenal.init.GolemEffects;
import a_silly_cat.golems_arsenal.init.ModAttributes;
import a_silly_cat.golems_arsenal.init.ModEnchantments;
import a_silly_cat.golems_arsenal.init.ModTags;
import dev.xkmc.modulargolems.content.entity.metalgolem.MetalGolemEntity;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.EntityStruckByLightningEvent;
import a_silly_cat.golems_arsenal.tech.energy.GolemEnergyProvider;
import a_silly_cat.golems_arsenal.tech.energy.GolemEnergyStorage;
import a_silly_cat.golems_arsenal.tech.energy.GolemEnergyItemProvider;
import a_silly_cat.golems_arsenal.tech.upgrade.GolemEnergyModifier;
import a_silly_cat.golems_arsenal.tech.upgrade.GolemEnergyTechModifier;
import a_silly_cat.golems_arsenal.base.upgrade.GolemWeaponAltModifier;
import a_silly_cat.golems_arsenal.base.upgrade.GolemWeaponMainModifier;
import a_silly_cat.golems_arsenal.base.upgrade.GolemWeaponOnslaughtModifier;
import a_silly_cat.golems_arsenal.base.upgrade.GolemWeaponRangedModifier;
import a_silly_cat.golems_arsenal.base.upgrade.GolemWeaponShieldModifier;
import dev.xkmc.l2library.init.events.GeneralEventHandler;
import dev.xkmc.modulargolems.content.entity.humanoid.HumanoidGolemEntity;
import dev.xkmc.modulargolems.content.item.ranged.SonicCannonItem;
import dev.xkmc.modulargolems.content.item.golem.GolemHolder;
import dev.xkmc.modulargolems.events.event.GolemDamageShieldEvent;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraftforge.event.AnvilUpdateEvent;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID)
public final class WeaponEventHandler {
    private static final String TRACKING_TAG = "GolemsArsenalTracking";
    private static final String EXPLOSIVE_TAG = "GolemsArsenalExplosive";
    private static final Set<UUID> TRACKING_ARROWS = ConcurrentHashMap.newKeySet();
    private static final UUID KATANA_PERCENT_UUID =
            UUID.nameUUIDFromBytes("golems_arsenal:katana_percent".getBytes());
    private static final UUID BOW_EXPLOSION_UUID =
            UUID.nameUUIDFromBytes("golems_arsenal:bow_explosion".getBytes());
    private static final UUID SWORD_REACH_UUID =
            UUID.nameUUIDFromBytes("golems_arsenal:sword_reach".getBytes());
    private static final UUID SWORD_SWEEP_UUID =
            UUID.nameUUIDFromBytes("golems_arsenal:sword_sweep".getBytes());
    private static final UUID RANGED_VELOCITY_UUID =
            UUID.nameUUIDFromBytes("golems_arsenal:ranged_velocity".getBytes());
    private static final UUID RANGED_MAGIC_UUID =
            UUID.nameUUIDFromBytes("golems_arsenal:ranged_magic".getBytes());
    private static final ResourceKey<DamageType> FLAME_MAGIC =
            ResourceKey.create(Registries.DAMAGE_TYPE, Golems_arsenal.id("flame_magic"));
    private static final TagKey<DamageType> TACZ_BULLETS =
            TagKey.create(Registries.DAMAGE_TYPE, new ResourceLocation("tacz", "bullets"));
    private static final String SHIELD_REPAIR_KEY = "golems_arsenal_shield_repair";
    private static volatile Attribute GOLEM_SWEEP_ATTR;
    private WeaponEventHandler() {
    }

    @SubscribeEvent
    public static void attachEnergy(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof AbstractGolemEntity<?, ?>) {
            GolemEnergyProvider provider = new GolemEnergyProvider();
            event.addCapability(Golems_arsenal.id("energy"), provider);
            event.addListener(provider::invalidate);
        }
    }

    @SubscribeEvent
    public static void attachHolderEnergy(AttachCapabilitiesEvent<ItemStack> event) {
        if (event.getObject().getItem() instanceof dev.xkmc.modulargolems.content.item.golem.GolemHolder) {
            event.addCapability(Golems_arsenal.id("golem_energy"), new GolemEnergyItemProvider(event.getObject()));
        }
    }

    @SubscribeEvent
    public static void onLightning(EntityStruckByLightningEvent event) {
        if (!(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)) return;
        golem.getCapability(GolemEnergyProvider.CAPABILITY).ifPresent(cap ->
                ((GolemEnergyStorage) cap).receiveEnergy(Config.GOLEM_ENERGY_LIGHTNING.get(), false));
    }

    /**
     * All attack-side damage bonuses from this mod are fused into a single per-hit check: one
     * golem lookup, one main-hand fetch and one item registry id lookup, then the katana,
     * weapon-upgrade and onslaught effects are applied in order.
     */
    @SubscribeEvent
    public static void onGolemAttackHurt(LivingHurtEvent event) {
        if (event.getEntity().level().isClientSide) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        ItemStack stack = golem.getMainHandItem();
        ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        float amount = event.getAmount();

        if (golem instanceof MetalGolemEntity
                && stack.getItem() instanceof GolemEnergyKatanaItem katana
                && katana.consumeAttackEnergy(stack)) {
            amount *= (1.0f + katana.getPoweredHitBonus(stack));
            int tech = GolemEnergyTechModifier.levelOf(golem);
            golem.addEffect(new MobEffectInstance(GolemEffects.CHARGE.get(),
                    Config.ENERGY_KATANA_CHARGE_DURATION.get(), chargeAmplifier(tech)));
            if (katana.canTriggerChain(golem, stack)) {
                lightningChain(golem, event.getEntity(), stack);
            }
        }
        if (GolemWeaponMainModifier.hasUpgrade(golem)) {
            double bonus = mainClassHpBonus(golem, stack, id);
            if (bonus > 0) {
                amount += (float) bonus;
            }
            if (isFlameSword(id)) {
                scheduleFlameCloud(golem, event.getEntity());
            }
        }
        if (GolemWeaponAltModifier.hasUpgrade(golem)) {
            if (isSculkScythe(id)) {
                amount *= (float) (1 + Config.SCULK_SCYTHE_BONUS.get());
            }
            if (isGolemSpear(id)) {
                scheduleSpearAoe(golem, event.getEntity(), amount);
            }
        }
        if (golem instanceof HumanoidGolemEntity && GolemWeaponOnslaughtModifier.hasUpgrade(golem)) {
            amount = applyOnslaughtBonus(golem, event.getSource(), amount);
        }
        event.setAmount(amount);
    }

    /**
     * Full-onslaught chestplate enchantment: players wearing an enchanted chestplate gain the same
     * bonus as the golem upgrade (melee attack and percentage TACZ gun damage while armor exceeds
     * the threshold).
     */
    @SubscribeEvent
    public static void onPlayerOnslaughtAttack(LivingHurtEvent event) {
        if (event.getEntity().level().isClientSide) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof Player player)) {
            return;
        }
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.getEnchantmentLevel(ModEnchantments.FULL_ONSLAUGHT.get()) <= 0) {
            return;
        }
        event.setAmount(applyOnslaughtBonus(player, event.getSource(), event.getAmount()));
    }

    /**
     * Meme-upgrade items apply their matching enchantment on an anvil: the enchantment id is the
     * item id with the trailing {@code _upgrade} removed (e.g. golem_full_onslaught_upgrade adds
     * full_onslaught). One meme-upgrade item is consumed per application.
     */
    @SubscribeEvent
    public static void onAnvilUpdate(AnvilUpdateEvent event) {
        ItemStack left = event.getLeft();
        ItemStack right = event.getRight();
        if (left.isEmpty() || right.isEmpty() || !right.is(ModTags.MEME_UPGRADES)) {
            return;
        }
        ResourceLocation upgradeId = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(right.getItem());
        if (upgradeId == null || !upgradeId.getPath().endsWith("_upgrade")) {
            return;
        }
        String enchantName = upgradeId.getPath().substring(0, upgradeId.getPath().length() - "_upgrade".length());
        Enchantment enchantment = net.minecraftforge.registries.ForgeRegistries.ENCHANTMENTS
                .getValue(new ResourceLocation(upgradeId.getNamespace(), enchantName));
        if (enchantment == null || !enchantment.canEnchant(left)) {
            return;
        }
        ItemStack output = left.copy();
        output.enchant(enchantment, 1);
        event.setOutput(output);
        event.setMaterialCost(1);
        event.setCost(1);
    }

    /**
     * Shared onslaught math for golems and players: while armor exceeds the threshold, each excess
     * armor point plus toughness grants TACZ gun damage as a percentage, and melee attack bonus
     * (percentage or flat, per config). Projectiles other than TACZ bullets are excluded.
     */
    private static float applyOnslaughtBonus(LivingEntity attacker, DamageSource source, float amount) {
        double armor = attacker.getAttributeValue(Attributes.ARMOR);
        if (armor <= Config.ONSLAUGHT_ARMOR_THRESHOLD.get()) {
            return amount;
        }
        double excess = (armor - Config.ONSLAUGHT_ARMOR_THRESHOLD.get())
                + attacker.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        if (source.is(TACZ_BULLETS)) {
            return amount * (float) (1 + excess * Config.ONSLAUGHT_GUN_PERCENT_PER_POINT.get());
        }
        if (source.is(DamageTypeTags.IS_PROJECTILE)) {
            return amount;
        }
        if (Config.ONSLAUGHT_ATTACK_PERCENT_MODE.get()) {
            return amount * (float) (1 + excess * Config.ONSLAUGHT_ATTACK_PERCENT_PER_POINT.get());
        }
        return amount + (float) (excess * Config.ONSLAUGHT_ATTACK_FLAT_PER_POINT.get());
    }

    private static int chargeAmplifier(int techLevel) {
        return Math.min(4, Math.max(0, techLevel - 1));
    }

    /** FE-powered lightning chain: repeated strikes that can hit the same target, applying Weakness. */
    private static void lightningChain(AbstractGolemEntity<?, ?> golem, LivingEntity victim,
                                       ItemStack katanaStack) {
        GolemEnergyKatanaItem katana = (GolemEnergyKatanaItem) katanaStack.getItem();
        Level level = golem.level();
        float damage = katana.getChainDamage();
        float radius = Config.ENERGY_KATANA_CHAIN_RADIUS.get().floatValue();
        List<LivingEntity> pool = level.getEntitiesOfClass(LivingEntity.class,
                AABB.ofSize(victim.position(), radius * 2, radius * 2, radius * 2),
                e -> e != golem && e != victim && e.isAlive());
        int strikes = 1 + Config.ENERGY_KATANA_CHAIN_BONUS_STRIKES.get();
        for (int i = 0; i < strikes; i++) {
            LivingEntity target = victim;
            if (i > 0 && !pool.isEmpty()) {
                target = pool.get(golem.getRandom().nextInt(pool.size()));
            }
            if (target.hurt(level.damageSources().lightningBolt(), damage)) {
                target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS,
                        Config.ENERGY_KATANA_CHAIN_WEAKNESS_DURATION.get(), 0));
            }
        }
    }

    /** Defensive passives while a golem holds the energy hammer. */
    @SubscribeEvent
    public static void onGolemHurt(LivingHurtEvent event) {
        if (event.getEntity().level().isClientSide) {
            return;
        }
        if (!(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        if (!(golem.getMainHandItem().getItem() instanceof GolemEnergyHammerItem)) {
            return;
        }
        float amount = event.getAmount();
        if (GolemEnergyHammerItem.hasFlatGuard(golem)) {
            amount = Math.max(0, amount - Config.ENERGY_HAMMER_FLAT_IMMUNITY.get().floatValue());
        }
        float threshold = golem.getMaxHealth() * Config.ENERGY_HAMMER_BIG_HIT_THRESHOLD.get().floatValue();
        if (amount > threshold && consumeHammerReductionEnergy(golem)) {
            amount *= (float) (1 - Config.ENERGY_HAMMER_BIG_HIT_REDUCTION.get());
        }
        event.setAmount(amount);
    }

    /** Big-hit reduction drains FE from the golem's energy storage. */
    private static boolean consumeHammerReductionEnergy(AbstractGolemEntity<?, ?> golem) {
        int cost = Config.ENERGY_HAMMER_REDUCTION_COST.get();
        return golem.getCapability(GolemEnergyProvider.CAPABILITY).map(cap -> {
            GolemEnergyStorage storage = (GolemEnergyStorage) cap;
            if (storage.extractEnergy(cost, true) < cost) {
                return false;
            }
            storage.extractEnergy(cost, false);
            return true;
        }).orElse(false);
    }

    /** Extra damage from the main weapon upgrade, as a flat amount based on the golem's max health. */
    private static double mainClassHpBonus(AbstractGolemEntity<?, ?> golem, ItemStack stack, ResourceLocation id) {
        double percent;
        if (isForgeHammer(id)) {
            percent = Config.FORGE_HAMMER_HP_PERCENT.get();
        } else if (isMainClassWeapon(stack, id)) {
            percent = Config.MAIN_WEAPON_HP_PERCENT.get();
        } else {
            return 0;
        }
        return golem.getMaxHealth() * percent;
    }

    private static boolean isMainClassWeapon(ItemStack stack, ResourceLocation id) {
        return stack.is(ItemTags.SWORDS) || isGolemSword(id) || isGolemAxe(id) || isFlameSword(id);
    }

    private static boolean isGolemSword(ResourceLocation id) {
        return id != null && id.getPath().endsWith("_golem_sword");
    }

    private static boolean isGolemAxe(ResourceLocation id) {
        return id != null && (id.getPath().endsWith("_golem_axe") || id.getPath().equals("golem_slicing_axe"));
    }

    private static boolean isFlameSword(ResourceLocation id) {
        return id != null && id.getNamespace().equals("golemdungeons") && id.getPath().equals("flame_sword");
    }

    private static boolean isForgeHammer(ResourceLocation id) {
        return id != null && id.getNamespace().equals("golemdungeons") && id.getPath().equals("ancient_forge");
    }

    private static boolean isGolemSpear(ResourceLocation id) {
        return id != null && id.getPath().endsWith("_golem_spear");
    }

    private static boolean isSculkScythe(ResourceLocation id) {
        return id != null && id.getNamespace().equals("golemdungeons") && id.getPath().equals("sculk_golem_scythe");
    }

    /**
     * Flame sword: leaves a lingering flame particle cloud at the struck position; after a short
     * delay it deals magic damage to everyone inside, using a damage type tagged forge:is_magic.
     */
    private static void scheduleFlameCloud(AbstractGolemEntity<?, ?> golem, LivingEntity victim) {
        Level level = golem.level();
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        Vec3 pos = victim.position();
        double radius = Config.FLAME_CLOUD_RADIUS.get();
        spawnFlameParticles(server, pos, radius);
        long time = server.getGameTime();
        GeneralEventHandler.schedulePersistent(() -> {
            if (server.getGameTime() < time + Config.FLAME_CLOUD_DELAY.get()) {
                return false;
            }
            AABB box = AABB.ofSize(pos, radius * 2, radius * 2, radius * 2);
            for (LivingEntity e : server.getEntitiesOfClass(LivingEntity.class, box,
                    e -> e != golem && e.isAlive())) {
                if (!e.isAlliedTo(golem) && !golem.isAlliedTo(e)) {
                    e.hurt(flameMagicSource(golem), Config.FLAME_CLOUD_DAMAGE.get().floatValue());
                }
            }
            spawnFlameParticles(server, pos, radius);
            return true;
        });
    }

    private static void spawnFlameParticles(ServerLevel level, Vec3 pos, double radius) {
        for (int i = 0; i < 24; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2;
            double r = level.random.nextDouble() * radius;
            level.sendParticles(ParticleTypes.FLAME,
                    pos.x + Math.cos(angle) * r, pos.y + 0.3 + level.random.nextDouble() * 0.6,
                    pos.z + Math.sin(angle) * r, 1, 0, 0.08, 0, 0.02);
        }
        level.sendParticles(ParticleTypes.SMOKE, pos.x, pos.y + 0.6, pos.z, 8,
                radius * 0.6, 0.25, radius * 0.6, 0.02);
    }

    private static DamageSource flameMagicSource(Entity attacker) {
        var registry = attacker.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        return new DamageSource(registry.getHolderOrThrow(FLAME_MAGIC), attacker);
    }

    /** Golem spear: delayed area damage around the struck target, modeled after the sculk scythe. */
    private static void scheduleSpearAoe(AbstractGolemEntity<?, ?> golem, LivingEntity victim, float damage) {
        Level level = golem.level();
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        Vec3 pos = victim.position();
        double radius = Config.SPEAR_AOE_RADIUS.get();
        float aoe = damage * Config.SPEAR_AOE_DAMAGE.get().floatValue();
        long time = server.getGameTime();
        GeneralEventHandler.schedulePersistent(() -> {
            if (server.getGameTime() < time + 2) {
                return false;
            }
            AABB box = AABB.ofSize(pos, radius * 2, radius * 2, radius * 2);
            for (LivingEntity e : server.getEntitiesOfClass(LivingEntity.class, box,
                    e -> e != golem && e.isAlive())) {
                if (!e.isAlliedTo(golem) && !golem.isAlliedTo(e)) {
                    e.hurt(server.damageSources().mobAttack(golem), aoe);
                }
            }
            return true;
        });
    }

    /**
     * Shield weapon upgrade (humanoid golems only): a successful block restores shield durability
     * based on the golem's armor value, on a cooldown. The block's own durability cost still
     * applies first, so this acts as a repair on top of normal shield wear.
     */
    @SubscribeEvent
    public static void onGolemShieldBlock(GolemDamageShieldEvent event) {
        HumanoidGolemEntity golem = event.getEntity();
        if (!GolemWeaponShieldModifier.hasUpgrade(golem)) {
            return;
        }
        ItemStack stack = event.getStack();
        if (!stack.isDamageableItem() || stack.getDamageValue() <= 0) {
            return;
        }
        long now = golem.level().getGameTime();
        if (now < golem.getPersistentData().getLong(SHIELD_REPAIR_KEY)) {
            return;
        }
        double armor = golem.getAttributeValue(Attributes.ARMOR);
        double toughness = golem.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        int repair = Math.min(
                (int) Math.round((armor + toughness) * Config.SHIELD_REPAIR_PER_ARMOR.get()),
                Config.SHIELD_REPAIR_MAX.get());
        if (repair <= 0) {
            return;
        }
        stack.setDamageValue(Math.max(0, stack.getDamageValue() - repair));
        golem.getPersistentData().putLong(SHIELD_REPAIR_KEY, now + Config.SHIELD_REPAIR_COOLDOWN.get());
    }

    /**
     * Periodic per-golem work, fused into a single pass: one main-hand fetch and one combined
     * modifier check gate all updates. Golems without any modifier from this mod are skipped.
     */
    @SubscribeEvent
    public static void onGolemLivingTick(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)
                || golem.level().isClientSide
                || golem.tickCount % 5 != 0) {
            return;
        }
        syncGolemCapacity(golem);
        if (!hasArsenalModifier(golem)) {
            return;
        }
        ItemStack stack = golem.getMainHandItem();
        chargeEquipment(golem, stack);
        updateWeaponAttributes(golem, stack);
        updateSwordAttributes(golem, stack);
        updateRangedVelocityAttributes(golem, stack);
        updateCannonMagicAttributes(golem);
        hammerRegen(golem, stack);
    }

    /** True when the golem has any modifier from this mod; gates all periodic per-golem work. */
    private static boolean hasArsenalModifier(AbstractGolemEntity<?, ?> golem) {
        return golem.getModifiers().keySet().stream().anyMatch(mod ->
                mod instanceof GolemEnergyModifier
                        || mod instanceof GolemEnergyTechModifier
                        || mod instanceof GolemWeaponMainModifier
                        || mod instanceof GolemWeaponAltModifier
                        || mod instanceof GolemWeaponRangedModifier
                        || mod instanceof GolemWeaponShieldModifier
                        || mod instanceof GolemWeaponOnslaughtModifier);
    }

    /**
     * Tech-upgrade scaling is applied as attributes while the weapons are held: the katana gains
     * ATTACK_DAMAGE (+5% per level) and the bow grants the L2lib explosion damage attribute
     * (projectile-to-explosion conversion). Without the tech upgrade neither exists.
     */
    private static void updateWeaponAttributes(AbstractGolemEntity<?, ?> golem, ItemStack stack) {
        AttributeInstance attack = golem.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attack == null) {
            return;
        }
        Attribute explosionAttr = GolemTrackingMechanicalBowItem.explosionAttribute();
        AttributeInstance explosion = explosionAttr == null ? null : golem.getAttribute(explosionAttr);
        int tech = GolemEnergyTechModifier.levelOf(golem);
        if (stack.getItem() instanceof GolemEnergyKatanaItem katana) {
            if (tech > 0) {
                setModifier(attack, KATANA_PERCENT_UUID, "golems_arsenal_katana_percent",
                        katana.getTechAttackPercent(tech));
            } else {
                attack.removeModifier(KATANA_PERCENT_UUID);
            }
        } else {
            attack.removeModifier(KATANA_PERCENT_UUID);
        }
        if (stack.getItem() instanceof GolemTrackingMechanicalBowItem) {
            if (tech > 0 && explosion != null) {
                double factor = (Config.TRACKING_BOW_PROJECTILE_DAMAGE.get()
                        + tech * Config.TECH_PROJECTILE_PER_LEVEL.get())
                        * Config.TRACKING_BOW_EXPLOSION_FACTOR_RATIO.get();
                setModifier(explosion, BOW_EXPLOSION_UUID, "golems_arsenal_bow_explosion", factor);
            } else if (explosion != null) {
                explosion.removeModifier(BOW_EXPLOSION_UUID);
            }
        } else if (explosion != null) {
            explosion.removeModifier(BOW_EXPLOSION_UUID);
        }
    }

    private static void setModifier(AttributeInstance instance, UUID uuid, String name, double amount) {
        AttributeModifier current = instance.getModifier(uuid);
        if (current != null && current.getAmount() == amount) {
            return;
        }
        instance.removeModifier(uuid);
        instance.addTransientModifier(new AttributeModifier(uuid, name, amount,
                AttributeModifier.Operation.MULTIPLY_BASE));
    }

    /**
     * Sword-tag weapons (used by humanoid golems) gain +1 entity reach and +1 sweep range while
     * the main weapon upgrade is installed. Both attributes exist on every golem type.
     */
    private static void updateSwordAttributes(AbstractGolemEntity<?, ?> golem, ItemStack stack) {
        boolean enabled = GolemWeaponMainModifier.hasUpgrade(golem) && stack.is(ItemTags.SWORDS);
        AttributeInstance reach = golem.getAttribute(ForgeMod.ENTITY_REACH.get());
        if (reach != null) {
            if (enabled) {
                setAddModifier(reach, SWORD_REACH_UUID, "golems_arsenal_sword_reach", 1);
            } else {
                reach.removeModifier(SWORD_REACH_UUID);
            }
        }
        Attribute sweepAttr = golemSweepAttribute();
        AttributeInstance sweep = sweepAttr == null ? null : golem.getAttribute(sweepAttr);
        if (sweep != null) {
            if (enabled) {
                setAddModifier(sweep, SWORD_SWEEP_UUID, "golems_arsenal_sword_sweep", 1);
            } else {
                sweep.removeModifier(SWORD_SWEEP_UUID);
            }
        }
    }

    /** Cached modulargolems:golem_sweep attribute, resolved once instead of every tick. */
    private static Attribute golemSweepAttribute() {
        if (GOLEM_SWEEP_ATTR == null) {
            GOLEM_SWEEP_ATTR = net.minecraftforge.registries.ForgeRegistries.ATTRIBUTES
                    .getValue(new ResourceLocation("modulargolems", "golem_sweep"));
        }
        return GOLEM_SWEEP_ATTR;
    }

    private static void setAddModifier(AttributeInstance instance, UUID uuid, String name, double amount) {
        AttributeModifier current = instance.getModifier(uuid);
        if (current != null && current.getAmount() == amount) {
            return;
        }
        instance.removeModifier(uuid);
        instance.addTransientModifier(new AttributeModifier(uuid, name, amount,
                AttributeModifier.Operation.ADDITION));
    }

    /**
     * Ranged weapon upgrade is realized through the arrow velocity attribute: while a golem holds
     * an allowed bow with the upgrade installed, the attribute gets a +speed multiplier modifier.
     * Vanilla arrow damage scales with velocity, so this raises speed and damage together.
     */
    private static void updateRangedVelocityAttributes(AbstractGolemEntity<?, ?> golem, ItemStack stack) {
        AttributeInstance attr = golem.getAttribute(ModAttributes.ARROW_VELOCITY.get());
        if (attr == null) {
            return;
        }
        boolean enabled = GolemWeaponRangedModifier.hasUpgrade(golem)
                && stack.getItem() instanceof BowItem
                && !(stack.getItem() instanceof GolemTrackingMechanicalBowItem);
        if (enabled) {
            setModifier(attr, RANGED_VELOCITY_UUID, "golems_arsenal_ranged_velocity",
                    Config.RANGED_ARROW_SPEED.get() - 1);
        } else {
            attr.removeModifier(RANGED_VELOCITY_UUID);
        }
    }

    private static void hammerRegen(AbstractGolemEntity<?, ?> golem, ItemStack stack) {
        if (!(stack.getItem() instanceof GolemEnergyHammerItem)) {
            return;
        }
        int tech = GolemEnergyTechModifier.levelOf(golem);
        if (tech <= 0) {
            return;
        }
        float heal = golem.getMaxHealth()
                * (Config.ENERGY_HAMMER_HP_REGEN_PERCENT.get().floatValue() / 100f) * tech / 4f;
        if (heal > 0 && golem.getHealth() < golem.getMaxHealth()) {
            golem.heal(Math.min(heal, golem.getMaxHealth() - golem.getHealth()));
        }
    }

    private static void syncGolemCapacity(AbstractGolemEntity<?, ?> golem) {
        int level = golem.getModifiers().entrySet().stream()
                .filter(entry -> entry.getKey() instanceof GolemEnergyModifier)
                .mapToInt(java.util.Map.Entry::getValue)
                .sum();
        int capacity = GolemEnergyModifier.capacityForLevel(level);
        golem.getCapability(GolemEnergyProvider.CAPABILITY).ifPresent(cap -> {
            GolemEnergyStorage storage = (GolemEnergyStorage) cap;
            storage.setCapacity(capacity);
            if (capacity == 0) storage.setEnergy(0);
        });
    }

    /** Shows the stored/capacity FE on a stowed golem holder that has the energy upgrade. */
    @SubscribeEvent
    public static void onGolemHolderTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof GolemHolder<?, ?>)) {
            return;
        }
        int capacity = GolemEnergyItemProvider.capacityOf(stack);
        if (capacity <= 0) {
            return;
        }
        event.getToolTip().add(Component.translatable("tooltip.golems_arsenal.energy",
                GolemEnergyItemProvider.energyOf(stack), capacity).withStyle(ChatFormatting.AQUA));
    }

    /** L2lib magic damage factor, resolved by reflection so this mod stays optional. */
    private static Attribute magicDamageAttributeCache;

    private static Attribute magicDamageAttribute() {
        if (magicDamageAttributeCache == null) {
            magicDamageAttributeCache = resolveMagicDamageAttribute();
        }
        return magicDamageAttributeCache;
    }

    private static Attribute resolveMagicDamageAttribute() {
        try {
            Class<?> tracker = Class.forName("dev.xkmc.l2damagetracker.init.L2DamageTracker");
            Object entry = tracker.getField("MAGIC_FACTOR").get(null);
            Object attribute = entry.getClass().getMethod("get").invoke(entry);
            return attribute instanceof Attribute value ? value : null;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    /**
     * Ranged weapon upgrade extra effect for the Sonic Cannon (Echo Cannon): while the golem
     * holds the cannon, raise the L2lib magic damage factor so its magic damage scales up.
     */
    private static void updateCannonMagicAttributes(AbstractGolemEntity<?, ?> golem) {
        Attribute magic = magicDamageAttribute();
        if (magic == null) {
            return;
        }
        AttributeInstance attr = golem.getAttribute(magic);
        if (attr == null) {
            return;
        }
        if (GolemWeaponRangedModifier.hasUpgrade(golem) && isHoldingCannon(golem)) {
            setModifier(attr, RANGED_MAGIC_UUID, "golems_arsenal_ranged_magic",
                    Config.RANGED_CANNON_MAGIC_BONUS.get());
        } else {
            attr.removeModifier(RANGED_MAGIC_UUID);
        }
    }

    private static boolean isHoldingCannon(AbstractGolemEntity<?, ?> golem) {
        if (golem.getMainHandItem().getItem() instanceof SonicCannonItem
                || golem.getOffhandItem().getItem() instanceof SonicCannonItem) {
            return true;
        }
        return golem instanceof MetalGolemEntity metal
                && (metal.getLeftShoulder().getItem().getItem() instanceof SonicCannonItem
                || metal.getRightShoulder().getItem().getItem() instanceof SonicCannonItem);
    }
    @SubscribeEvent
    public static void onArrowJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof AbstractArrow arrow)) {
            return;
        }
        if (!(arrow.getOwner() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        ItemStack stack = golem.getMainHandItem();
        if (GolemWeaponRangedModifier.hasUpgrade(golem)
                && stack.getItem() instanceof BowItem
                && !(stack.getItem() instanceof GolemTrackingMechanicalBowItem)) {
            double velocity = golem.getAttributeValue(ModAttributes.ARROW_VELOCITY.get());
            if (velocity > 0) {
                arrow.setDeltaMovement(arrow.getDeltaMovement().scale(velocity));
                arrow.hasImpulse = true;
            }
        }
        if (stack.getItem() instanceof GolemTrackingMechanicalBowItem bow
                && bow.consumeTrackingEnergy(stack)) {
            arrow.getPersistentData().putBoolean(TRACKING_TAG, true);
            TRACKING_ARROWS.add(arrow.getUUID());
            int tech = GolemEnergyTechModifier.levelOf(golem);
            arrow.setBaseDamage(arrow.getBaseDamage()
                    * (1 + tech * Config.TECH_PROJECTILE_PER_LEVEL.get()));
            if (GolemTrackingMechanicalBowItem.canExplode(golem)) {
                arrow.getPersistentData().putBoolean(EXPLOSIVE_TAG, true);
            }
        }
    }

    /** Powered arrows explode in a tiny blast on impact; stronger arrows scale the radius. */
    @SubscribeEvent
    public static void onArrowImpact(ProjectileImpactEvent event) {
        if (!(event.getEntity() instanceof AbstractArrow arrow) || arrow.level().isClientSide) {
            return;
        }
        if (!arrow.getPersistentData().getBoolean(EXPLOSIVE_TAG)) {
            return;
        }
        if (!(arrow.getOwner() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        Vec3 pos = event.getRayTraceResult().getLocation();
        float radius = Config.TRACKING_BOW_EXPLOSION_RADIUS.get().floatValue();
        float ratio = Config.TRACKING_BOW_EXPLOSION_DAMAGE_RATIO.get().floatValue();
        radius = (float) Math.min(3.0, radius * (1 + ratio * arrow.getBaseDamage() / 10f));
        arrow.level().explode(golem, pos.x, pos.y, pos.z, radius, Level.ExplosionInteraction.NONE);
    }

    @SubscribeEvent
    public static void onServerLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side != LogicalSide.SERVER
                || !(event.level instanceof ServerLevel level)) {
            return;
        }
        // Equipment charging and all per-golem updates run in onGolemLivingTick, so no
        // level-wide entity iteration is needed here.
        Iterator<UUID> iterator = TRACKING_ARROWS.iterator();
        while (iterator.hasNext()) {
            UUID id = iterator.next();
            Entity entity = level.getEntity(id);
            if (!(entity instanceof AbstractArrow arrow)
                    || !arrow.getPersistentData().getBoolean(TRACKING_TAG)) {
                TRACKING_ARROWS.remove(id);
                continue;
            }
            if (!(arrow.getOwner() instanceof MetalGolemEntity golem)) {
                TRACKING_ARROWS.remove(id);
                continue;
            }
            var target = golem.getTarget();
            if (target == null || !target.isAlive()
                    || arrow.distanceToSqr(target) > Math.pow(Config.TRACKING_BOW_TRACKING_RANGE.get(), 2)) {
                continue;
            }
            Vec3 velocity = arrow.getDeltaMovement();
            double speed = velocity.length();
            if (speed < 1.0E-4) {
                continue;
            }
            Vec3 desired = target.getEyePosition().subtract(arrow.position()).normalize();
            double turn = Config.TRACKING_BOW_TRACKING_TURN.get();
            Vec3 direction = velocity.normalize().scale(1.0 - turn).add(desired.scale(turn)).normalize();
            arrow.setDeltaMovement(direction.scale(speed));
            arrow.hasImpulse = true;
            arrow.hurtMarked = true;
        }
    }

    private static void chargeEquipment(AbstractGolemEntity<?, ?> golem, ItemStack stack) {
        golem.getCapability(GolemEnergyProvider.CAPABILITY).ifPresent(source -> {
            GolemEnergyStorage energy = (GolemEnergyStorage) source;
            if (energy.getEnergyStored() <= 0) return;
            chargeStack(stack, energy);
            for (ItemStack armor : golem.getArmorSlots()) {
                chargeStack(armor, energy);
            }
        });
    }

    private static void chargeStack(ItemStack stack, GolemEnergyStorage source) {
        if (stack.isEmpty() || source.getEnergyStored() <= 0) return;
        stack.getCapability(ForgeCapabilities.ENERGY).ifPresent(target -> {
            if (!target.canReceive()) return;
            int budget = Math.min(Config.GOLEM_ENERGY_TRANSFER_RATE.get(), source.getEnergyStored());
            int accepted = target.receiveEnergy(budget, false);
            if (accepted > 0) source.extractEnergy(accepted, false);
        });
    }
}
