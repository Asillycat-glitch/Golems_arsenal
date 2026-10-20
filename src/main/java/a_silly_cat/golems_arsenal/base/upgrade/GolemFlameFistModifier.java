package a_silly_cat.golems_arsenal.base.upgrade;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.base.event.GolemCritHandler;
import dev.xkmc.l2library.init.events.GeneralEventHandler;
import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 炎拳（Flame Fist）：跨 mod 联动升级，利用《灾变》(Cataclysm) 的「炽热烙印」。
 *
 * <p>傀儡近战命中<b>已经带有炽热烙印</b>的目标时，追加若干段额外伤害；第 2 级再多一段并
 * 顺手续上烙印持续时间。设计要点：</p>
 *
 * <ul>
 *   <li><b>只"利用"烙印，不"施加"烙印</b>：叠烙印那一侧已经由 {@code modulargolems:ignis_attack}
 *       与灾变自己的装备覆盖，所以我们做的是"敌人被点燃之后的重拳"。</li>
 *   <li><b>无硬依赖</b>：烙印效果按 {@link ResourceLocation} 从 {@code ForgeRegistries.MOB_EFFECTS}
 *       查表得到，包里没有灾变时本升级只是一件装饰品，不会崩。</li>
 *   <li><b>多段必须带 {@code bypasses_cooldown}</b>：伤害类型 {@code golems_arsenal:flame_fist} 已在
 *       {@code data/minecraft/tags/damage_type/bypasses_cooldown.json} 里登记，否则第 2 段起会被目标的
 *       无敌帧（{@code invulnerableTime}）整段吃掉 —— 这是伤害管线测试里量到的结论。</li>
 *   <li><b>不碰 fire / magic 语义</b>：该伤害类型既不在 {@code is_fire} 也不在 {@code is_magic} 里，
 *       所以既不会被 {@code fire_immune} 取消，也不会被 {@code magic_immune} 取消（护甲照常生效）。</li>
 *   <li><b>每段隔一 tick 结算</b>：避免同一 tick 内叠加多次伤害事件（l2 伤害缓存的递归分支）。
 *       与 {@link GolemStanceModifier} 用的是同一套 {@code schedulePersistent} 写法。</li>
 * </ul>
 */
public class GolemFlameFistModifier extends GolemModifier {
    public static final int MAX_LEVEL = 2;

    /** {@code cataclysm:blazing_brand} —— 灾变的炽热烙印，按 id 解析以便保持可选依赖。 */
    private static final ResourceLocation BLAZING_BRAND_ID = new ResourceLocation("cataclysm", "blazing_brand");
    private static final ResourceKey<DamageType> FLAME_FIST =
            ResourceKey.create(Registries.DAMAGE_TYPE, Golems_arsenal.id("flame_fist"));
    private static final String NEXT_TABLE_KEY = "golem_flame_fist_next";

    private static boolean brandLookupDone;
    private static MobEffect blazingBrandCache;

    public GolemFlameFistModifier() {
        super(StatFilterType.ATTACK, MAX_LEVEL);
    }

    @Override
    public Component getTooltip(int level) {
        return Component.translatable("upgrade.golems_arsenal.flame_fist")
                .withStyle(ChatFormatting.GOLD);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        List<MutableComponent> list = new ArrayList<>();
        list.add(Component.translatable("upgrade.golems_arsenal.flame_fist.desc",
                        Config.FLAME_FIST_HITS_LV1.get(),
                        Math.round(Config.FLAME_FIST_RATIO.get() * 100))
                .withStyle(ChatFormatting.GREEN));
        if (level >= 2) {
            list.add(Component.translatable("upgrade.golems_arsenal.flame_fist.desc_lv2",
                            Config.FLAME_FIST_HITS_LV2.get(),
                            Math.round(Config.FLAME_FIST_BRAND_REFRESH.get() / 20.0))
                    .withStyle(ChatFormatting.GREEN));
        }
        if (blazingBrand() == null) {
            list.add(Component.translatable("upgrade.golems_arsenal.flame_fist.no_compat")
                    .withStyle(ChatFormatting.RED));
        }
        return list;
    }

    /**
     * 近战命中目标时结算：目标身上有炽热烙印 → 追加多段炎拳。
     *
     * <p>会被跳过的三类：弹射物命中、本 mod 自己的技能伤害（{@link GolemCritHandler#isOwnSkillDamage}，
     * 防止炎拳自己触发自己）、以及冷却未到的连击。</p>
     */
    @Override
    public void onHurtTarget(AbstractGolemEntity<?, ?> golem, LivingHurtEvent event, int level) {
        if (golem.level().isClientSide
                || event.getSource().is(DamageTypeTags.IS_PROJECTILE)
                || GolemCritHandler.isOwnSkillDamage(event.getSource().getMsgId())) {
            return;
        }
        LivingEntity target = event.getEntity();
        if (isFriendly(golem, target)) {
            return;
        }
        MobEffect brand = blazingBrand();
        if (brand == null || !target.hasEffect(brand)) {
            return;
        }
        long now = golem.level().getGameTime();
        if (onCooldown(golem, target, now)) {
            return;
        }
        int hits = Math.max(0, level >= 2
                ? Config.FLAME_FIST_HITS_LV2.get()
                : Config.FLAME_FIST_HITS_LV1.get());
        float per = (float) Math.max(0.0D, event.getAmount() * Config.FLAME_FIST_RATIO.get());
        if (hits <= 0 || per <= 0.0F) {
            return;
        }
        stamp(golem, target, now);
        scheduleVolley(golem, target, per, hits, Config.FLAME_FIST_BRAND_REFRESH.get());
    }

    /**
     * 限频：<b>按目标各记各的</b>。
     *
     * <p>原来是整台傀儡共用一个时间戳，于是"主手那一击"就把冷却吃掉了，
     * 紧随其后的三段连击（每 5 tick 一段）全都落在冷却里 —— 表现就是"三连击不触发炎拳"。
     * 现在改成每个目标一条时间戳，并把默认冷却降到 5 tick（= 连击的段间隔），
     * 这样一次连击里每一段都能对同一个目标各触发一次；把配置调大就退回"限频"语义。</p>
     */
    private static boolean onCooldown(AbstractGolemEntity<?, ?> golem, LivingEntity target, long now) {
        return now < golem.getPersistentData().getCompound(NEXT_TABLE_KEY).getLong(target.getStringUUID());
    }

    private static void stamp(AbstractGolemEntity<?, ?> golem, LivingEntity target, long now) {
        CompoundTag data = golem.getPersistentData();
        CompoundTag table = data.contains(NEXT_TABLE_KEY) ? data.getCompound(NEXT_TABLE_KEY) : new CompoundTag();
        table.putLong(target.getStringUUID(), now + Config.FLAME_FIST_COOLDOWN.get());
        data.put(NEXT_TABLE_KEY, table);
    }

    /** 每段隔一 tick 打出，最后一段（2 级）顺手续上烙印。 */
    private static void scheduleVolley(AbstractGolemEntity<?, ?> golem, LivingEntity target,
                                       float per, int hits, int refresh) {
        if (!(golem.level() instanceof ServerLevel server)) {
            return;
        }
        long start = server.getGameTime();
        for (int i = 0; i < hits; i++) {
            final long due = start + 1 + i;
            final boolean last = i == hits - 1;
            GeneralEventHandler.schedulePersistent(() -> {
                if (server.getGameTime() < due) {
                    return false;
                }
                if (!golem.isAlive() || !target.isAlive() || target.level() != golem.level()) {
                    return true;
                }
                target.hurt(flameFistSource(golem), per);
                if (last && refresh > 0) {
                    refreshBrand(target, refresh);
                }
                flameBurst(server, target);
                return true;
            });
        }
    }

    /** 每段命中都刷一点火花，让"炎拳"看得见。 */
    private static void flameBurst(ServerLevel server, LivingEntity target) {
        server.sendParticles(ParticleTypes.FLAME,
                target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ(),
                10, target.getBbWidth() * 0.4D, target.getBbHeight() * 0.3D, target.getBbWidth() * 0.4D, 0.02D);
        server.sendParticles(ParticleTypes.LAVA,
                target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ(),
                2, 0.2D, 0.2D, 0.2D, 0.0D);
        server.playSound(null, target.getX(), target.getY(), target.getZ(),
                SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 0.5F, 1.4F);
    }

    /**
     * 续上烙印持续时间（等级 2）。<b>只延长已有的那一条</b>，不改等级、不新加 ——
     * 施加烙印仍然是灾变装备 / {@code modulargolems:ignis_attack} 的职责。
     */
    private static void refreshBrand(LivingEntity target, int duration) {
        MobEffect brand = blazingBrand();
        if (brand == null) {
            return;
        }
        MobEffectInstance old = target.getEffect(brand);
        if (old == null) {
            return;
        }
        target.addEffect(new MobEffectInstance(brand,
                Math.max(duration, old.getDuration()), old.getAmplifier(),
                old.isAmbient(), old.isVisible(), old.showIcon()));
    }

    /** 烙印效果查表结果；包里没有灾变时返回 null（并只在第一次尝试解析）。 */
    public static MobEffect blazingBrand() {
        if (!brandLookupDone) {
            brandLookupDone = true;
            blazingBrandCache = ForgeRegistries.MOB_EFFECTS.getValue(BLAZING_BRAND_ID);
        }
        return blazingBrandCache;
    }

    /**
     * 目标是不是"自己人"：玩家（含主人）、友军、同主傀儡一律不追加炎拳。
     *
     * <p>只靠 {@code isAlliedTo} 不够：本家的 {@code AbstractGolemEntity#isAlliedTo} 走的是
     * {@code getOwner()}，主人不在线时会退化成"看队伍"，同主的傀儡会被判成敌人
     * （{@code LightPillarEntity#isFriendly} 里记了同一坑），所以这里再补一道
     * 不依赖主人在线的 {@code getOwnerUUID()} 比对。</p>
     */
    private static boolean isFriendly(AbstractGolemEntity<?, ?> golem, LivingEntity target) {
        if (target instanceof Player || target == golem) {
            return true;
        }
        if (target.isAlliedTo(golem) || golem.isAlliedTo(target)) {
            return true;
        }
        UUID master = golem.getOwnerUUID();
        if (master == null) {
            return false;
        }
        if (master.equals(target.getUUID())) {
            return true;
        }
        return target instanceof OwnableEntity other && master.equals(other.getOwnerUUID());
    }

    /**
     * 炎拳的伤害源。伤害类型自带 {@code bypasses_cooldown}（多段不被无敌帧吃掉），
     * 且不属于 fire / magic 语义，所以不会被元素免疫取消。
     */
    public static DamageSource flameFistSource(Entity attacker) {
        var registry = attacker.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        return new DamageSource(registry.getHolderOrThrow(FLAME_FIST), attacker);
    }

    /** 供其它 on-hit 处理器跳过炎拳伤害，避免自我触发。 */
    public static boolean isFlameFist(DamageSource source) {
        return "flame_fist".equals(source.getMsgId());
    }

    /** 已安装的炎拳等级（未安装为 0）。 */
    public static int levelOf(AbstractGolemEntity<?, ?> golem) {
        return golem.getModifiers().getOrDefault(GolemUpgrades.FLAME_FIST.get(), 0);
    }

    public static boolean hasUpgrade(AbstractGolemEntity<?, ?> golem) {
        return levelOf(golem) > 0;
    }
}
