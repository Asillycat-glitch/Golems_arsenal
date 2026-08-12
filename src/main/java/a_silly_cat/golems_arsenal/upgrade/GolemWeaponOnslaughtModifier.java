package a_silly_cat.golems_arsenal.upgrade;

import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/**
 * Full onslaught upgrade (humanoid golems only): while armor exceeds the threshold, every extra
 * armor point grants melee attack bonus (percentage or flat, configurable) and percentage TACZ
 * gun damage.
 */
public class GolemWeaponOnslaughtModifier extends GolemModifier {
    public static final int MAX_LEVEL = 1;

    public GolemWeaponOnslaughtModifier() {
        super(StatFilterType.ATTACK, MAX_LEVEL);
    }

    @Override
    public Component getTooltip(int level) {
        return Component.translatable("upgrade.golems_arsenal.golem_weapon_onslaught")
                .withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        return List.of(
                Component.translatable("upgrade.golems_arsenal.golem_weapon_onslaught.desc")
                        .withStyle(ChatFormatting.GREEN));
    }

    public static boolean hasUpgrade(AbstractGolemEntity<?, ?> entity) {
        return GolemUpgrades.hasUpgrade(entity, GolemWeaponOnslaughtModifier.class);
    }
}
