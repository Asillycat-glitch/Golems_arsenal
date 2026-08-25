package a_silly_cat.golems_arsenal.base.item;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import dev.xkmc.modulargolems.content.item.equipments.IGolemEquipmentItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeMod;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Shen Tong Staff (神通棍): a large golem weapon that players can also wield. Extends
 * {@link SwordItem} so vanilla and YSM treat it as a sword (swing animation, sweep), while
 * implementing {@link IGolemEquipmentItem} so every golem type can equip it. +10 attack damage
 * and +2 entity reach come from the attribute modifiers; the per-Black-Monkey-upgrade +10% attack
 * damage is applied dynamically by WeaponEventHandler.
 */
public class ShenTongStaffItem extends SwordItem implements IGolemEquipmentItem {
    private static final UUID REACH_UUID =
            UUID.nameUUIDFromBytes("golems_arsenal:staff_reach".getBytes());

    public ShenTongStaffItem(Properties properties) {
        super(STAFF_TIER, 10, -2.4f, properties);
    }

    @Override
    public Multimap<Attribute, AttributeModifier> getAttributeModifiers(EquipmentSlot slot,
                                                                        ItemStack stack) {
        Multimap<Attribute, AttributeModifier> map =
                HashMultimap.create(super.getAttributeModifiers(slot, stack));
        if (slot == EquipmentSlot.MAINHAND) {
            map.put(ForgeMod.ENTITY_REACH.get(), new AttributeModifier(
                    REACH_UUID, "weapon_reach", 2.0, AttributeModifier.Operation.ADDITION));
        }
        return map;
    }

    /** Any golem type can wield the staff, not just metal golems (players always can). */
    @Override
    public boolean isFor(EntityType<?> type) {
        return true;
    }

    @Override
    public EquipmentSlot getSlot() {
        return EquipmentSlot.MAINHAND;
    }

    /** Give the full weapon attributes to every golem type. */
    @Override
    public Multimap<Attribute, AttributeModifier> getGolemModifiers(ItemStack stack, Entity entity,
                                                                    EquipmentSlot slot) {
        return stack.getAttributeModifiers(slot);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> list,
                                TooltipFlag flag) {
        list.add(Component.translatable("tooltip.golems_arsenal.shen_tong_staff"));
    }

    /** Flat +10 attack damage (no tier bonus), so the staff's own modifier shows +10 in tooltip. */
    private static final Tier STAFF_TIER = new Tier() {
        @Override
        public int getUses() {
            return 1561;
        }

        @Override
        public float getSpeed() {
            return 1.0f;
        }

        @Override
        public float getAttackDamageBonus() {
            return 0;
        }

        @Override
        public int getLevel() {
            return 3;
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
