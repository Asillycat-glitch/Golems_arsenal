package a_silly_cat.golems_arsenal.base.item;

import com.google.common.collect.Multimap;
import dev.xkmc.modulargolems.content.item.equipments.IGolemEquipmentItem;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;

/**
 * Base class for this mod's golem swords that players can also wield (currently the Shen Tong
 * Staff and the Key Sword). Registers as a sword for swing/sweep logic, lets every golem type
 * equip it in the main hand, and hands the golem the same attribute modifiers the player gets.
 */
public abstract class GolemSwordItem extends SwordItem implements IGolemEquipmentItem {

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

    /** Give the full weapon attributes to every golem type. */
    @Override
    public Multimap<Attribute, AttributeModifier> getGolemModifiers(ItemStack stack, Entity entity,
                                                                    EquipmentSlot slot) {
        return stack.getAttributeModifiers(slot);
    }
}
