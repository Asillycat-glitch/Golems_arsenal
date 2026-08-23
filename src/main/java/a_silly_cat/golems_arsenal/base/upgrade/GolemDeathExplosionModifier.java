package a_silly_cat.golems_arsenal.base.upgrade;

import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/**
 * Deathrattle upgrade: when the golem dies it detonates once - the blast never breaks blocks and
 * deals fixed damage plus a percentage of the golem's max health (see Config).
 */
public class GolemDeathExplosionModifier extends GolemModifier {
    public static final int MAX_LEVEL = 1;

    public GolemDeathExplosionModifier() {
        super(StatFilterType.HEALTH, MAX_LEVEL);
    }

    @Override
    public Component getTooltip(int level) {
        return Component.translatable("upgrade.golems_arsenal.golem_death_explosion")
                .withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        return List.of(Component.translatable("upgrade.golems_arsenal.golem_death_explosion.desc")
                .withStyle(ChatFormatting.GREEN));
    }

    public static boolean hasUpgrade(AbstractGolemEntity<?, ?> entity) {
        return GolemUpgrades.hasUpgrade(entity, GolemDeathExplosionModifier.class);
    }
}
