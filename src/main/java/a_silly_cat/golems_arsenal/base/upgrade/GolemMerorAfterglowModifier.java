package a_silly_cat.golems_arsenal.base.upgrade;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.base.event.GolemCritHandler;
import dev.xkmc.l2damagetracker.contents.attack.CreateSourceEvent;
import dev.xkmc.l2damagetracker.contents.damage.DefaultDamageState;
import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * 莫厄余烬（Meror Afterglow）：联动《Jerotes 村落·傀儡》的「莫厄充能」。
 *
 * <p>与"叠烙印"式的联动不同，这条升级<b>只读不写</b>莫厄充能层数，把自己的收益建立在
 * 傀儡本来就有的东西上：</p>
 *
 * <ul>
 *   <li><b>层数增伤</b>：傀儡身上的 {@code jerotes_village_golems:meror_energy} 每层 +5%（1 级）
 *       / +8%（2 级），最多计 5 层。</li>
 *   <li><b>必定穿甲</b>：攻击结算时给伤害类型开 {@code BYPASS_ARMOR} 状态（和 mgdp 的"摄魂怪"
 *       同一条通道，走 l2 的 {@code CreateSourceEvent}），护甲对这台傀儡失效。</li>
 *   <li><b>较低的 END 返还</b>：由 {@link #restitutionBonus} 提供 +0.08 / +0.12，并进
 *       {@code golems_arsenal:end_restitution} 的同一笔账（最终仍被 0.8 上限夹住）。</li>
 * </ul>
 *
 * <p>注意：<b>它不负责"把攻击变成莫厄伤害"</b>——那件事由数据包（物品 tag {@code jerotes:meror}）
 * 与 {@code compat/jerotes/MerorDamageCompat} 分别处理。原因：能拿到莫厄充能的傀儡通常已经
 * 自带莫厄伤害（实体类型 tag {@code jerotes:meror_damage}），重复授予没有意义。</p>
 */
public class GolemMerorAfterglowModifier extends GolemModifier {
    public static final int MAX_LEVEL = 2;
    public static final int MAX_STACKS = 5;

    private static final ResourceLocation MEROR_ENERGY_ID =
            new ResourceLocation("jerotes_village_golems", "meror_energy");
    private static boolean lookupDone;
    private static MobEffect merorEnergyCache;

    public GolemMerorAfterglowModifier() {
        super(StatFilterType.ATTACK, MAX_LEVEL);
    }

    /** 每层增伤：1 级 5%、2 级 8%。 */
    public static double perStack(int level) {
        return level >= 2 ? Config.MEROR_AFTERGLOW_PER_STACK_LV2.get()
                : Config.MEROR_AFTERGLOW_PER_STACK_LV1.get();
    }

    /** 该等级给多少 END 返还（并进 end_restitution 那一笔账，仍受 0.8 上限约束）。 */
    public static double restitutionBonus(int level) {
        return level >= 2 ? Config.MEROR_AFTERGLOW_RESTITUTION_LV2.get()
                : Config.MEROR_AFTERGLOW_RESTITUTION_LV1.get();
    }

    /** 傀儡身上的莫厄充能层数（0 表示没有这个效果）。 */
    public static int stacksOf(LivingEntity golem) {
        MobEffect effect = merorEnergy();
        if (effect == null) {
            return 0;
        }
        MobEffectInstance instance = golem.getEffect(effect);
        return instance == null ? 0 : Math.min(MAX_STACKS, instance.getAmplifier() + 1);
    }

    /** 莫厄充能效果查表结果；没装 JVG / Jerotes 时返回 null。 */
    public static MobEffect merorEnergy() {
        if (!lookupDone) {
            lookupDone = true;
            merorEnergyCache = ForgeRegistries.MOB_EFFECTS.getValue(MEROR_ENERGY_ID);
        }
        return merorEnergyCache;
    }

    public static int levelOf(AbstractGolemEntity<?, ?> golem) {
        return golem.getModifiers().getOrDefault(GolemUpgrades.MEROR_AFTERGLOW.get(), 0);
    }

    public static boolean hasUpgrade(AbstractGolemEntity<?, ?> golem) {
        return levelOf(golem) > 0;
    }

    @Override
    public Component getTooltip(int level) {
        return Component.translatable("upgrade.golems_arsenal.meror_afterglow")
                .withStyle(ChatFormatting.GOLD);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        List<MutableComponent> list = new ArrayList<>();
        list.add(Component.translatable("upgrade.golems_arsenal.meror_afterglow.desc",
                Math.round(perStack(level) * 100), MAX_STACKS,
                Math.round(perStack(level) * 100 * MAX_STACKS)).withStyle(ChatFormatting.GREEN));
        list.add(Component.translatable("upgrade.golems_arsenal.meror_afterglow.desc_armor")
                .withStyle(ChatFormatting.AQUA));
        list.add(Component.translatable("upgrade.golems_arsenal.meror_afterglow.desc_restitution",
                Math.round(restitutionBonus(level) * 100)).withStyle(ChatFormatting.AQUA));
        if (merorEnergy() == null) {
            list.add(Component.translatable("upgrade.golems_arsenal.meror_afterglow.no_compat")
                    .withStyle(ChatFormatting.RED));
        }
        return list;
    }

    /** 必定穿甲：给这一击的伤害类型开 BYPASS_ARMOR（走 l2 官方状态通道）。 */
    @Override
    public void modifySource(AbstractGolemEntity<?, ?> golem, CreateSourceEvent event, int level) {
        var result = event.getResult();
        if (result != null && result.validState(DefaultDamageState.BYPASS_ARMOR)) {
            event.enable(DefaultDamageState.BYPASS_ARMOR);
        }
    }

    /** 层数增伤：按傀儡自己身上的莫厄充能层数把这一击抬上去。 */
    @Override
    public void onHurtTarget(AbstractGolemEntity<?, ?> golem, LivingHurtEvent event, int level) {
        if (golem.level().isClientSide
                || event.getSource().is(DamageTypeTags.IS_PROJECTILE)
                || GolemCritHandler.isOwnSkillDamage(event.getSource().getMsgId())) {
            return;
        }
        int stacks = stacksOf(golem);
        if (stacks <= 0) {
            return;
        }
        double mult = 1.0D + perStack(level) * stacks;
        event.setAmount((float) (event.getAmount() * mult));
    }
}
