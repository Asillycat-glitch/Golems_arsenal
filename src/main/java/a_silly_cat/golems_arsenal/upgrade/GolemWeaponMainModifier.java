package a_silly_cat.golems_arsenal.upgrade;

import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/**
 * Main-class weapon upgrade: swords, axes, flame sword and forge hammer gain extra damage equal
 * to a percentage of the golem's own max health; the flame sword also leaves a magic flame cloud,
 * and sword-tag weapons grant +1 entity reach and +1 sweep range.
 */
public class GolemWeaponMainModifier extends GolemModifier {
    public static final int MAX_LEVEL = 1;

    public GolemWeaponMainModifier() {
        super(StatFilterType.ATTACK, MAX_LEVEL);
    }

    @Override
    public Component getTooltip(int level) {
        return Component.translatable("upgrade.golems_arsenal.golem_weapon_main")
                .withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        return List.of(
                Component.translatable("upgrade.golems_arsenal.golem_weapon_main.desc")
                        .withStyle(ChatFormatting.GREEN),
                Component.translatable("upgrade.golems_arsenal.golem_weapon_main.desc_sword")
                        .withStyle(ChatFormatting.GREEN));
    }

    public static boolean hasUpgrade(AbstractGolemEntity<?, ?> entity) {
        return GolemUpgrades.hasUpgrade(entity, GolemWeaponMainModifier.class);
    }
}
