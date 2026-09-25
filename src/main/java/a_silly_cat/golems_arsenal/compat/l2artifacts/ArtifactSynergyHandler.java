package a_silly_cat.golems_arsenal.compat.l2artifacts;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.base.upgrade.GolemUpgradeLevels;
import dev.xkmc.l2library.init.events.GeneralEventHandler;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.UUID;

/**
 * Golem-side crossovers between Modular Golems' own upgrades and L2Artifacts sets. A golem only ever
 * wears one set, so at most one of these is active at a time; each one is gated on both the upgrade
 * level (read from the upgrade items only) and the set's piece count.
 * <ul>
 *   <li>完美之人 - 回复升级 5 级（接受清单见 {@code perfection_regen_upgrades}）：回复量按 1:1 存进池子；
 *   血量掉到阈值以下且身上没有吸收时按档位上限爆发；
 *   身上积攒的伤害吸收达到门槛（默认 5 点）后，这一击的伤害不会超过当前吸收值，整击被吸收吃掉、血一点不掉。</li>
 *   <li>法师 - 魂火 3 级：受击时对攻击者造成"固定值 + 已损生命 × 百分比"的荆棘反伤（荆棘在 l2dam 的
 *   {@code forge:is_magic} 里，因此吃法术强度）。</li>
 *   <li>执行者 - 伤害升级 5 级：攻击按目标已损生命获得加算倍率，代价是最大生命被常态削减。</li>
 * </ul>
 * Set state is refreshed every 10 ticks per golem and cached in the golem's persistent data, so the
 * per-hit paths never scan curios.
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID)
public final class ArtifactSynergyHandler {

    // 触发条件：升级修饰符 id 与所需等级（与傀儡装配自己的 maxLevel 一致）
    private static final String FLAME_UPGRADE = "modulargolems:soul_flame";
    private static final String DAMAGE_UPGRADE = "modulargolems:damage_up";
    private static final int REGEN_LEVEL = 5;
    private static final int FLAME_LEVEL = 3;
    private static final int DAMAGE_LEVEL = 5;
    private static final int PERFECTION_PIECES = 4;
    private static final int MAGE_PIECES = 4;
    private static final int EXECUTOR_PIECES = 5;

    // 每隔多少 tick 刷新一次套装状态 / 累积一次池子
    private static final int SYNC_INTERVAL = 10;
    /** {@code golem_regen} 是"每秒回复的点数"，而回复本身每 20 tick 结算一次。 */
    private static final double REGEN_TICKS_PER_SECOND = 20.0;
    private static final int MAX_RANK = 5;

    private static final String PERFECTION_RANK_KEY = "GolemsArsenalPerfectionRank";
    private static final String PERFECTION_POOL_KEY = "GolemsArsenalPerfectionPool";
    private static final String MAGE_RANK_KEY = "GolemsArsenalMageRank";
    private static final String EXECUTOR_RANK_KEY = "GolemsArsenalExecutorRank";

    private static final UUID EXECUTOR_HEALTH_UUID =
            UUID.nameUUIDFromBytes("golems_arsenal:executor_health".getBytes());

    private static Attribute golemRegenCache;

    /** 反伤用的荆棘伤害的 message id，用于排除自己造成的反伤。 */
    private static final String THORNS_MSG_ID = "thorns";

    private ArtifactSynergyHandler() {
    }

    @SubscribeEvent
    public static void onGolemLivingTick(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        if (golem.level().isClientSide || golem.tickCount % SYNC_INTERVAL != 0) {
            return;
        }
        if (!ModList.get().isLoaded("l2artifacts")) {
            return;
        }
        sync(golem);
    }

    /** 刷新三套的档位缓存，并驱动需要持续维持的两项属性代价。 */
    private static void sync(AbstractGolemEntity<?, ?> golem) {
        CompoundTag tag = golem.getPersistentData();

        int perfectionTier = ArtifactSetHelper.rank(golem, "perfection", PERFECTION_PIECES);
        int regenLevel = perfectionTier > 0 ? regenerationLevel(golem) : 0;
        int perfection = perfectionTier > 0 && regenLevel < REGEN_LEVEL ? 0 : perfectionTier;
        int mage = ArtifactSetHelper.rank(golem, "mage", MAGE_PIECES);
        if (mage > 0 && GolemUpgradeLevels.levelOf(golem, FLAME_UPGRADE) < FLAME_LEVEL) {
            mage = 0;
        }
        int executor = ArtifactSetHelper.rank(golem, "executor", EXECUTOR_PIECES);
        if (executor > 0 && GolemUpgradeLevels.levelOf(golem, DAMAGE_UPGRADE) < DAMAGE_LEVEL) {
            executor = 0;
        }
        tag.putInt(PERFECTION_RANK_KEY, perfection);
        tag.putInt(MAGE_RANK_KEY, mage);
        tag.putInt(EXECUTOR_RANK_KEY, executor);

        tickPerfection(golem, tag, perfection);
        tickExecutor(golem, executor);

    }

    /**
     * 完美之人：回复量 1:1 存进池子。血量低于阈值且身上没有伤害吸收时，把池子按档位上限爆发成吸收并清零。
     * 挡伤害那条规则不在这里维护：它看的是傀儡当下的吸收值，见 {@link #onPerfectionDamageLimit(LivingHurtEvent)}。
     */
    private static void tickPerfection(AbstractGolemEntity<?, ?> golem, CompoundTag tag, int rank) {
        Attribute regen = golemRegenAttribute();
        AttributeInstance instance = regen == null ? null : golem.getAttribute(regen);
        if (instance == null) {
            return;
        }
        if (rank <= 0) {
            tag.putDouble(PERFECTION_POOL_KEY, 0.0);
            return;
        }
        double perSecond = Math.max(0.0, instance.getValue());
        double pool = tag.getDouble(PERFECTION_POOL_KEY)
                + perSecond * SYNC_INTERVAL / REGEN_TICKS_PER_SECOND;
        double cap = perfectionCap(rank, golem.getMaxHealth());
        double threshold = Config.PERFECTION_HEALTH_THRESHOLD.get() * golem.getMaxHealth();
        if (pool > 0 && golem.getHealth() <= threshold && golem.getAbsorptionAmount() <= 0) {
            double burst = Math.min(pool, cap);
            golem.setAbsorptionAmount(golem.getAbsorptionAmount() + (float) burst);
            pool = 0.0;
        }
        tag.putDouble(PERFECTION_POOL_KEY, pool);
    }

    /**
     * 完美之人·伤害上限：身上积攒的伤害吸收达到门槛（默认 5 点）后，这一击的伤害不会超过当前吸收值。
     * 吸收在受击结算里排在扣血之前，所以把伤害压到吸收值以内之后，整击正好被吸收吃掉、血一点不掉。
     * <p>
     * 这里改伤害，而不是在 {@code LivingDamageEvent} 里把溢出部分取消掉：一来这样"超过吸收值的部分"才算
     * 真的没打出来，二来穿透吸收的伤害不会被顺带一起免掉。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPerfectionDamageLimit(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        if (golem.level().isClientSide) {
            return;
        }
        // 只有挂着完美之人联动的傀儡才有这条规则（档位缓存在每 10 tick 的刷新里写入）。
        if (golem.getPersistentData().getInt(PERFECTION_RANK_KEY) <= 0) {
            return;
        }
        double threshold = Config.PERFECTION_GUARD_THRESHOLD.get();
        double absorption = golem.getAbsorptionAmount();
        if (threshold <= 0 || absorption < threshold) {
            return;
        }
        // 无视无敌的伤害（达摩剑那类处死）必须能穿过来。
        if (event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return;
        }
        if (event.getAmount() <= absorption) {
            return;
        }
        event.setAmount((float) absorption);
    }

    /** 执行者：常态削减最大生命，作为加伤倍率的代价。 */
    private static void tickExecutor(AbstractGolemEntity<?, ?> golem, int rank) {
        AttributeInstance health = golem.getAttribute(Attributes.MAX_HEALTH);
        if (health == null) {
            return;
        }
        boolean active = rank > 0;
        boolean applied = health.getModifier(EXECUTOR_HEALTH_UUID) != null;
        if (active && !applied) {
            health.addTransientModifier(new AttributeModifier(EXECUTOR_HEALTH_UUID,
                    "golems_arsenal_executor_health",
                    -Config.EXECUTOR_MAX_HEALTH_REDUCTION.get(),
                    AttributeModifier.Operation.MULTIPLY_BASE));
        } else if (!active && applied) {
            health.removeModifier(EXECUTOR_HEALTH_UUID);
        }
    }

    /**
     * 法师：受击时对攻击者造成反伤。用荆棘伤害类型，因为 {@code minecraft:thorns} 已在 l2dam 的
     * {@code forge:is_magic} 里，L2DamageTracker 会按攻击者的 MAGIC_FACTOR 计入受击侧。
     */
    @SubscribeEvent
    public static void onGolemHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        if (golem.level().isClientSide || THORNS_MSG_ID.equals(event.getSource().getMsgId())) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof LivingEntity attacker) || attacker == golem) {
            return;
        }
        int rank = golem.getPersistentData().getInt(MAGE_RANK_KEY);
        if (rank <= 0) {
            return;
        }
        double flat = lerp(Config.MAGE_REFLECT_FLAT_MIN.get(), Config.MAGE_REFLECT_FLAT_MAX.get(), rank);
        double ratio = lerp(Config.MAGE_REFLECT_PERCENT_MIN.get(), Config.MAGE_REFLECT_PERCENT_MAX.get(), rank);
        double missing = Math.max(0.0, golem.getMaxHealth() - golem.getHealth());
        float amount = (float) (flat + missing * ratio);
        if (amount <= 0) {
            return;
        }
        ServerLevel server = (ServerLevel) golem.level();
        GeneralEventHandler.schedulePersistent(() -> {
            if (attacker.isAlive() && attacker.level() == server) {
                attacker.hurt(golem.damageSources().thorns(golem), amount);
            }
            return true;
        });
    }

    /**
     * 执行者的加伤倍率（按目标已损生命），由攻击结算链读取；未激活时返回 0。数值缓存在 NBT 里，
     * 避免每次攻击都扫 curios。
     */
    public static double executorDamageBonus(AbstractGolemEntity<?, ?> golem, LivingEntity target) {
        if (golem.getPersistentData().getInt(EXECUTOR_RANK_KEY) <= 0) {
            return 0.0;
        }
        double max = target.getMaxHealth();
        if (max <= 0) {
            return 0.0;
        }
        double missing = 1.0 - target.getHealth() / max;
        return Config.EXECUTOR_DAMAGE_FACTOR.get() * Math.max(0.0, Math.min(1.0, missing));
    }

    /** 档位 1..5 之间线性取值（最小值 = 档位 1，最大值 = 档位 5）。 */
    private static double lerp(double min, double max, int rank) {
        int clamped = Math.max(1, Math.min(MAX_RANK, rank));
        return min + (max - min) * (clamped - 1) / (double) (MAX_RANK - 1);
    }

    private static double perfectionCap(int rank, float maxHealth) {
        double flat = lerp(Config.PERFECTION_ABSORB_FLAT_MIN.get(),
                Config.PERFECTION_ABSORB_FLAT_MAX.get(), rank);
        double percent = lerp(Config.PERFECTION_ABSORB_PERCENT_MIN.get(),
                Config.PERFECTION_ABSORB_PERCENT_MAX.get(), rank);
        return flat + percent * maxHealth;
    }

    /**
     * 傀儡当前算作"回复升级"的等级：取 {@code perfection_regen_upgrades} 里所有词条的最高等级。
     * 附属模组常把"回复V"的苹果升级挂在自己的词条 id 上（例如决战方案的下界合金苹果是
     * {@code modulargolems:netherite_gold} / {@code modulargolems:enchanted_netherite_gold}），
     * 所以这里按列表逐个读，而不是写死 {@code modulargolems:regeneration_up}。
     */
    private static int regenerationLevel(AbstractGolemEntity<?, ?> golem) {
        int best = 0;
        for (String id : Config.PERFECTION_REGEN_UPGRADES.get()) {
            best = Math.max(best, GolemUpgradeLevels.levelOf(golem, id));
        }
        return best;
    }

    private static Attribute golemRegenAttribute() {
        if (golemRegenCache == null) {
            ResourceLocation id = ResourceLocation.tryParse("modulargolems:golem_regen");
            golemRegenCache = id == null ? null : ForgeRegistries.ATTRIBUTES.getValue(id);
        }
        return golemRegenCache;
    }
}
