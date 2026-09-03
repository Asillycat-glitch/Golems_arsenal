package a_silly_cat.golems_arsenal.base.item;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Key Sword (钥匙剑): a sword usable by players and every golem type. +12 attack damage,
 * +0.3 attack speed and (when Iron's Spells / Golem Magicka is present) +40% spell resistance.
 * The special attacks (blaze fireballs, lightning, frost nova) and the low-HP execute bonus are
 * handled by {@code KeySwordEventHandler}, because they need the attacker's current stats and
 * event context rather than static item modifiers.
 */
public class KeySwordItem extends GolemSwordItem {
    private static final UUID SPELL_RESIST_UUID =
            UUID.nameUUIDFromBytes("golems_arsenal:key_sword_spell_resist".getBytes());

    private static Attribute spellResistAttributeCache;

    public KeySwordItem(Properties properties) {
        super(KEY_SWORD_TIER, 0, -2.1f, properties);
    }

    @Override
    public Multimap<Attribute, AttributeModifier> getAttributeModifiers(EquipmentSlot slot,
                                                                        ItemStack stack) {
        Multimap<Attribute, AttributeModifier> map =
                HashMultimap.create(super.getAttributeModifiers(slot, stack));
        if (slot == EquipmentSlot.MAINHAND) {
            Attribute resist = spellResistAttribute();
            if (resist != null) {
                map.put(resist, new AttributeModifier(
                        SPELL_RESIST_UUID, "key_sword_spell_resist", 0.4,
                        AttributeModifier.Operation.ADDITION));
            }
        }
        return map;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> list,
                                TooltipFlag flag) {
        list.add(Component.translatable("tooltip.golems_arsenal.key_sword"));
    }

    /**
     * Iron's Spells {@code spell_resist} is a percentage attribute (default 1.0 = 100%), so an
     * ADDITION modifier of +0.4 means +40% spell resistance. Resolved by registry name through
     * {@link ForgeRegistries}, so this mod never needs a hard dependency on Iron's Spells.
     */
    private static Attribute spellResistAttribute() {
        if (spellResistAttributeCache == null) {
            spellResistAttributeCache = ForgeRegistries.ATTRIBUTES.getValue(
                    new ResourceLocation("irons_spellbooks", "spell_resist"));
        }
        return spellResistAttributeCache;
    }

    /** +12 attack damage (tier bonus), +0.3 attack speed (1.9 total = sword 1.6 + 0.3). */
    private static final Tier KEY_SWORD_TIER = new Tier() {
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
            return 12.0f;
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
