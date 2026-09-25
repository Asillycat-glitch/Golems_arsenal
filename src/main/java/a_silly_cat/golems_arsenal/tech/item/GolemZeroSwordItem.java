package a_silly_cat.golems_arsenal.tech.item;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.tech.energy.ItemEnergyCapability;
import a_silly_cat.golems_arsenal.tech.energy.ItemEnergyStorage;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.item.equipments.MetalGolemWeaponItem;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Zero 能量刀（Z 光剑）：12 点攻击伤害、攻击距离 +1、横扫范围 +1。
 * <p>
 * 三项机制：
 * <ul>
 *   <li><b>穿透护甲</b>：近战攻击打开 l2damagetracker 的 {@code BYPASS_ARMOR} 状态，走的是和
 *      傀儡地牢"遗迹锻锤"完全同一条路（见 {@code ZeroSwordAttackListener}）。</li>
 *   <li><b>科技升级加成</b>：持有期间每装有一个科技升级，攻击力 +5%（由
 *      {@code WeaponEventHandler#updateWeaponAttributes} 挂属性，UUID 独立于武士刀）。</li>
 *   <li><b>电量 + 蓄力攻击</b>：距上一次攻击满 {@link #CHARGE_WINDOW_TICKS} tick 时，
 *      这一击消耗 {@link #CHARGE_ENERGY_COST} FE 并造成 {@link #CHARGE_DAMAGE_MULTIPLIER} 倍伤害
 *      （由 {@code WeaponEventHandler#onGolemAttackHurt} 结算，放在最前面，所以后面的加成照常叠）。</li>
 * </ul>
 */
public class GolemZeroSwordItem extends MetalGolemWeaponItem {

    /** 12 点攻击伤害（本家基类第 1 个参数，ADDITION）。 */
    public static final int BASE_ATTACK_DAMAGE = 12;
    /** 攻击距离 +1（第 3 个参数 → forge:entity_reach）。 */
    public static final float WEAPON_REACH_BONUS = 1.0f;
    /** 横扫范围 +1（第 4 个参数 → 本家 GOLEM_SWEEP）。 */
    public static final float WEAPON_SWEEP_BONUS = 1.0f;

    /** 基础电量。 */
    public static final int BASE_ENERGY_CAPACITY = 1_000_000;
    /** 蓄力命中消耗的电量。 */
    public static final int CHARGE_ENERGY_COST = 2_500;
    /** 蓄力窗口：两次攻击间隔达到 2 秒（40 tick）算蓄力完成。 */
    public static final int CHARGE_WINDOW_TICKS = 40;
    /** 蓄力命中的伤害倍率。 */
    public static final float CHARGE_DAMAGE_MULTIPLIER = 2.0f;

    /** 上一次攻击的时间戳（存在傀儡的持久数据里，每只傀儡各记各的）。 */
    public static final String LAST_ATTACK_KEY = "GolemsArsenalZeroSwordLastAttack";

    /**
     * 科技升级带来的攻击力百分比加成 UUID；必须和武士刀分开，否则两者互相覆盖。
     * <p>
     * 注意：本家 {@code MetalGolemWeaponItem} 里有一个名为 {@code UUID} 的静态字段（装备槽 → UUID 的
     * EnumMap），会把 {@code java.util.UUID} 遮蔽掉，所以这里必须写全限定名。
     */
    public static final java.util.UUID TECH_PERCENT_UUID =
            java.util.UUID.nameUUIDFromBytes(
                    "golems_arsenal:zero_sword_percent".getBytes(StandardCharsets.UTF_8));

    public GolemZeroSwordItem(Properties properties) {
        super(properties, BASE_ATTACK_DAMAGE, 0, WEAPON_REACH_BONUS, WEAPON_SWEEP_BONUS);
    }

    /** 蓄力是否就绪：距上次攻击满一个窗口。没打过（时间戳为 0）时不算，避免"第一击翻倍"。 */
    public static boolean isCharged(AbstractGolemEntity<?, ?> golem) {
        CompoundTag tag = golem.getPersistentData();
        long last = tag.getLong(LAST_ATTACK_KEY);
        return last > 0 && golem.level().getGameTime() - last >= CHARGE_WINDOW_TICKS;
    }

    /** 记录这次攻击的时间戳（每次命中都要刷新，包括没电的时候）。 */
    public static void markAttack(AbstractGolemEntity<?, ?> golem) {
        golem.getPersistentData().putLong(LAST_ATTACK_KEY, golem.level().getGameTime());
    }

    /** 蓄力命中扣电；电量不够返回 false（那就只是普通一击）。 */
    public boolean consumeChargeEnergy(ItemStack stack) {
        return consumeChargeEnergy(stack, CHARGE_ENERGY_COST);
    }

    /** 带"节约能源"折扣的消耗入口。 */
    public boolean consumeChargeEnergy(ItemStack stack, int cost) {
        return ItemEnergyStorage.consume(stack, Math.max(1, cost));
    }

    /** 每级科技升级提供的攻击力百分比（和武士刀同一个配置项）。 */
    public float getTechAttackPercent(int techLevel) {
        return (float) (techLevel * Config.TECH_DAMAGE_PER_LEVEL.get());
    }

    public int getEnergyCapacity(ItemStack stack) {
        return BASE_ENERGY_CAPACITY;
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return true;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return Math.round(13.0f * ItemEnergyStorage.getStored(stack) / getEnergyCapacity(stack));
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return Mth.hsvToRgb(0.48f, 1.0f, 1.0f);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> list,
                                TooltipFlag flag) {
        list.add(Component.translatable("tooltip.golems_arsenal.energy",
                ItemEnergyStorage.getStored(stack), getEnergyCapacity(stack))
                .withStyle(ChatFormatting.AQUA));
        list.add(Component.translatable("tooltip.golems_arsenal.zero_sword.armor_pierce")
                .withStyle(ChatFormatting.GRAY));
        list.add(Component.translatable("tooltip.golems_arsenal.zero_sword.tech_attack",
                Math.round(Config.TECH_DAMAGE_PER_LEVEL.get() * 100)).withStyle(ChatFormatting.GRAY));
        list.add(Component.translatable("tooltip.golems_arsenal.zero_sword.charge",
                CHARGE_WINDOW_TICKS / 20, String.format("%.0f", CHARGE_DAMAGE_MULTIPLIER))
                .withStyle(ChatFormatting.GRAY));
        list.add(Component.translatable("tooltip.golems_arsenal.zero_sword.cost",
                CHARGE_ENERGY_COST).withStyle(ChatFormatting.DARK_GRAY));
        super.appendHoverText(stack, level, list, flag);
        // 彩蛋：灰色删除线，放在整段介绍的最下面。
        list.add(Component.translatable("tooltip.golems_arsenal.zero_sword.meme")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.STRIKETHROUGH));
    }

    @Override
    public @Nullable ICapabilityProvider initCapabilities(ItemStack stack, @Nullable CompoundTag nbt) {
        return new ItemEnergyCapability(stack, () -> getEnergyCapacity(stack));
    }
}
