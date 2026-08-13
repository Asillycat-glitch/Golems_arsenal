package a_silly_cat.golems_arsenal.upgrade;

import dev.xkmc.modulargolems.content.item.upgrade.IUpgradeItem;
import dev.xkmc.modulargolems.content.modifier.base.AttributeGolemModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * Repeatable expansion modifier.
 * <p>
 * Each level applied to a golem grants:
 * <ul>
 *   <li>{@code slotsPerLevel} extra upgrade slots (via {@link #addSlot})</li>
 *   <li>per-level stat values from the {@link AttrEntry}s (collected by
 *   {@code GolemMaterial.collectAttributes} because this extends {@link AttributeGolemModifier})</li>
 * </ul>
 * <p>
 * It extends {@link AttributeGolemModifier} (instead of just {@code GolemModifier}) so the
 * attribute part is handled by Modular Golems' normal stat pipeline, and only {@code addSlot}
 * is overridden to make the slot part repeatable (Modular Golems' own {@code AddSlotModifier}
 * returns {@code level}, i.e. +1 slot per level).
 */
public class RepeatableExpansionModifier extends AttributeGolemModifier {

    private final int slotsPerLevel;

    /**
     * @param langKey base language key (e.g. {@code upgrade.golems_arsenal.tech_expansion});
     *                tooltip uses it directly, detail lines use {@code langKey + ".desc"}
     */
    private final String langKey;

    public RepeatableExpansionModifier(int maxLevel, int slotsPerLevel, String langKey, AttrEntry... entries) {
        super(maxLevel, entries);
        this.slotsPerLevel = slotsPerLevel;
        this.langKey = langKey;
    }

    @Override
    public Component getTooltip(int level) {
        return Component.translatable(langKey).withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    /**
     * Called by {@code GolemHolder.getRemaining} with the golem's current modifier level
     * (levels are summed across repeated applications of the same template, capped at maxLevel).
     * Keeps the vanilla stat-conflict penalty, then adds {@code slotsPerLevel} per level.
     */
    @Override
    public int addSlot(List<IUpgradeItem> upgrades, int level) {
        int conflict = super.addSlot(upgrades, level);
        return conflict < 0 ? conflict : level * slotsPerLevel;
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        List<MutableComponent> list = new ArrayList<>();
        list.add(Component.translatable(langKey + ".desc").withStyle(ChatFormatting.GREEN));
        list.addAll(super.getDetail(level));
        list.add(Component.translatable("upgrade.golems_arsenal.expansion.slot", level * slotsPerLevel)
                .withStyle(ChatFormatting.GREEN));
        return list;
    }
}
