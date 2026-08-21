package a_silly_cat.golems_arsenal.base.upgrade;

import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/** Ranged weapon upgrade: arrows shot by a golem bow fly faster and deal bonus projectile damage. */
public class GolemWeaponRangedModifier extends GolemModifier {
    public static final int MAX_LEVEL = 1;

    public GolemWeaponRangedModifier() {
        super(StatFilterType.ATTACK, MAX_LEVEL);
    }

    @Override
    public Component getTooltip(int level) {
        return Component.translatable("upgrade.golems_arsenal.golem_weapon_ranged")
                .withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        return List.of(
                Component.translatable("upgrade.golems_arsenal.golem_weapon_ranged.desc")
                        .withStyle(ChatFormatting.GREEN));
    }

    public static boolean hasUpgrade(AbstractGolemEntity<?, ?> entity) {
        return GolemUpgrades.hasUpgrade(entity, GolemWeaponRangedModifier.class);
    }
}
