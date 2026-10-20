package a_silly_cat.golems_arsenal.base.item;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 示例 / 测试武器：和钥匙剑、神通棍同一套做法 —— {@link GolemSwordItem} 既让<b>玩家</b>当剑用，
 * 也让<b>任何傀儡</b>装备到主手并拿到同样的属性（外加大型傀儡那一档加成）。
 *
 * <p>数值固定在这里，不再由构造参数传进来：它现在的定位是"测试武器"，数值要稳定可比，
 * 免得每次换参数都让测试结果失去对照。</p>
 */
public class ExampleWeapon extends GolemSwordItem {

    /** 基础攻击力（本家 {@code Tier} 的加成，剑类会再叠加玩家/傀儡自身的攻击力）。 */
    public static final int BASE_ATTACK_DAMAGE = 7;
    /** 额外攻击力百分比，乘在基础乘区（0.2 = +20%）。 */
    public static final double EXTRA_DAMAGE_PERCENT = 0.2;
    /** 攻速偏移：剑基础 1.6，这里 -2.1 让总攻速落在 1.9（和钥匙剑一致）。 */
    public static final float ATTACK_SPEED_MODIFIER = -2.1f;

    /**
     * 固定 UUID：属性修饰符靠 id 认"同一条"，用 {@code randomUUID()} 会让傀儡每次换装备都再叠一层。
     */
    private static final UUID EXTRA_DAMAGE_ID =
            UUID.nameUUIDFromBytes("golems_arsenal:example_weapon_extra_damage"
                    .getBytes(StandardCharsets.UTF_8));

    public ExampleWeapon(Properties properties) {
        super(TIER, 0, ATTACK_SPEED_MODIFIER, properties);
    }

    /**
     * +20% 攻击力走 {@code MULTIPLY_BASE}。写在 {@code getAttributeModifiers} 里而不是只写傀儡那条路，
     * 是因为傀儡侧（{@code GolemSwordItem#getGolemModifiers}）就是从
     * {@code stack.getAttributeModifiers(slot)} 复制出去的 —— 写在这里，玩家和傀儡自动一致。
     */
    @Override
    public Multimap<Attribute, AttributeModifier> getAttributeModifiers(EquipmentSlot slot,
                                                                        ItemStack stack) {
        Multimap<Attribute, AttributeModifier> map =
                HashMultimap.create(super.getAttributeModifiers(slot, stack));
        if (slot == EquipmentSlot.MAINHAND) {
            map.put(Attributes.ATTACK_DAMAGE, new AttributeModifier(
                    EXTRA_DAMAGE_ID, "golems_arsenal.example_weapon_extra_damage",
                    EXTRA_DAMAGE_PERCENT, AttributeModifier.Operation.MULTIPLY_BASE));
        }
        return map;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> list,
                                TooltipFlag flag) {
        list.add(Component.translatable("tooltip.golems_arsenal.example_weapon",
                Math.round(EXTRA_DAMAGE_PERCENT * 100)));
        list.add(largeGolemBonusLine());
        super.appendHoverText(stack, level, list, flag);
    }

    private static final Tier TIER = new Tier() {
        @Override
        public int getUses() {
            return 2032;
        }

        @Override
        public float getSpeed() {
            return 1.0f;
        }

        @Override
        public float getAttackDamageBonus() {
            return BASE_ATTACK_DAMAGE;
        }

        @Override
        public int getLevel() {
            return 4;
        }

        @Override
        public int getEnchantmentValue() {
            return 15;
        }

        @Override
        public Ingredient getRepairIngredient() {
            return Ingredient.EMPTY;
        }
    };
}
