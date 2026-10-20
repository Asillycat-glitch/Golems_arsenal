package a_silly_cat.golems_arsenal.base.item;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import dev.xkmc.modulargolems.content.entity.metalgolem.MetalGolemEntity;
import dev.xkmc.modulargolems.content.item.equipments.IGolemEquipmentItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;

import java.util.UUID;

/**
 * Base class for this mod's golem swords that players can also wield (currently the Shen Tong
 * Staff and the Key Sword). Registers as a sword for swing/sweep logic, lets every golem type
 * equip it in the main hand, and hands the golem the same attribute modifiers the player gets
 * &mdash; plus an extra attack bonus when the holder is a <b>large</b> golem.
 */
public abstract class GolemSwordItem extends SwordItem implements IGolemEquipmentItem {

    /** 大型傀儡（{@code modulargolems:metal_golem}）持有时的攻击力加成，0.3 = +30%。 */
    public static final double LARGE_GOLEM_ATTACK_BONUS = 0.3;

    /**
     * 固定 UUID。属性修饰符靠 id 认"同一条"，若每次 {@code randomUUID()}，
     * 傀儡每次换装备都会再叠一层同名加成。
     */
    private static final UUID LARGE_GOLEM_BONUS_ID =
            UUID.nameUUIDFromBytes("golems_arsenal:large_golem_attack_bonus".getBytes());

    protected GolemSwordItem(Tier tier, int attackDamageModifier, float attackSpeedModifier,
                             Properties properties) {
        super(tier, attackDamageModifier, attackSpeedModifier, properties);
    }

    /** Any golem type can equip these weapons, not just metal golems (players always can). */
    @Override
    public boolean isFor(EntityType<?> type) {
        return true;
    }

    @Override
    public EquipmentSlot getSlot() {
        return EquipmentSlot.MAINHAND;
    }

    /**
     * 把武器自身的属性交给傀儡，并<b>只在大型傀儡手里</b>额外加一档攻击力。
     * <p>
     * {@code entity} 就是持有者：本家在 {@code LivingEntityMixin#collectEquipmentChanges} 里调用这里，
     * 所以"按持有者分支"是官方给的入口，不需要 mixin、事件或新依赖。
     * <p>
     * 乘区选 {@code MULTIPLY_BASE} 而不是 {@code MULTIPLY_TOTAL}：只有这一条乘区时两者都是 ×1.3，
     * 但基础乘区之间是<b>加法</b>叠加，不会跟整包里其它总乘区一起滚雪球。
     * <p>
     * {@code slot == getSlot()} 这一道是为了跟物品自身的属性行为对齐：{@code SwordItem} 只在主手
     * 给出属性，别的槽位本来返回空表，加成也不该在那里冒出来。
     * <p>
     * ⚠️ 这里必须先复制一份再 put：{@code stack.getAttributeModifiers(slot)} 可能是<b>不可变</b>的
     * （{@code SwordItem} 在构造时用 {@code ImmutableMultimap} 烘死），原地 {@code put} 会抛异常。
     */
    @Override
    public Multimap<Attribute, AttributeModifier> getGolemModifiers(ItemStack stack, Entity entity,
                                                                    EquipmentSlot slot) {
        Multimap<Attribute, AttributeModifier> map =
                HashMultimap.create(stack.getAttributeModifiers(slot));
        if (slot == getSlot() && entity instanceof MetalGolemEntity) {
            map.put(Attributes.ATTACK_DAMAGE, new AttributeModifier(
                    LARGE_GOLEM_BONUS_ID, "golems_arsenal.large_golem_attack_bonus",
                    LARGE_GOLEM_ATTACK_BONUS, AttributeModifier.Operation.MULTIPLY_BASE));
        }
        return map;
    }

    /**
     * 悬停提示里那一行：条件加成不在物品自身的 modifiers 里，<b>不会自动显示</b>，得自己写出来。
     * 数字从 {@link #LARGE_GOLEM_ATTACK_BONUS} 算，改常量不会让文案走偏。
     */
    protected static Component largeGolemBonusLine() {
        return Component.translatable("tooltip.golems_arsenal.large_golem_bonus",
                Math.round(LARGE_GOLEM_ATTACK_BONUS * 100));
    }
}
