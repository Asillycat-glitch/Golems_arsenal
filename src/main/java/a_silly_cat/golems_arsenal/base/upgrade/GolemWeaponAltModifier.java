package a_silly_cat.golems_arsenal.base.upgrade;

import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/**
 * Atypical weapon upgrade: golem spears splash damage around the struck target, and the sculk
 * golem scythe deals bonus damage on hit.
 */
public class GolemWeaponAltModifier extends GolemModifier {
    public static final int MAX_LEVEL = 1;

    public GolemWeaponAltModifier() {
        super(StatFilterType.ATTACK, MAX_LEVEL);
    }

    @Override
    public Component getTooltip(int level) {
        return Component.translatable("upgrade.golems_arsenal.golem_weapon_alt")
                .withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        return List.of(
                Component.translatable("upgrade.golems_arsenal.golem_weapon_alt.desc")
                        .withStyle(ChatFormatting.GREEN));
    }

    public static boolean hasUpgrade(AbstractGolemEntity<?, ?> entity) {
        return GolemUpgrades.hasUpgrade(entity, GolemWeaponAltModifier.class);
    }
}
