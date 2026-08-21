package a_silly_cat.golems_arsenal.compat.golemmagicka;

import a_silly_cat.golems_arsenal.base.upgrade.GolemUpgrades;
import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/**
 * Scroll upgrade marker. With Golem Magicka installed, a golem carrying this upgrade can have
 * one spell recorded onto it (right-click with a scroll) and will cast it in combat.
 */
public class GolemScrollModifier extends GolemModifier {
    public static final int MAX_LEVEL = 1;

    public GolemScrollModifier() {
        super(StatFilterType.MASS, MAX_LEVEL);
    }

    @Override
    public Component getTooltip(int level) {
        return Component.translatable("upgrade.golems_arsenal.golem_scroll")
                .withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        return List.of(Component.translatable("upgrade.golems_arsenal.golem_scroll.desc")
                .withStyle(ChatFormatting.GREEN));
    }

    public static boolean hasUpgrade(AbstractGolemEntity<?, ?> entity) {
        return GolemUpgrades.hasUpgrade(entity, GolemScrollModifier.class);
    }
}
