package a_silly_cat.golems_arsenal.base.upgrade;

import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * Generic level-1 flag modifier shared by all marker upgrades (main/alt/ranged/shield/onslaught,
 * death explosion, scroll). Only the stat filter, language key and optional extra detail keys
 * differ between them; {@link #hasUpgrade} checks the exact registered instance.
 */
public class GolemFlagModifier extends GolemModifier {
    private final String langKey;
    private final String[] extraDetailKeys;

    public GolemFlagModifier(StatFilterType type, String langKey, String... extraDetailKeys) {
        super(type, 1);
        this.langKey = langKey;
        this.extraDetailKeys = extraDetailKeys;
    }

    @Override
    public Component getTooltip(int level) {
        return Component.translatable(langKey).withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        List<MutableComponent> list = new ArrayList<>();
        list.add(Component.translatable(langKey + ".desc").withStyle(ChatFormatting.GREEN));
        for (String key : extraDetailKeys) {
            list.add(Component.translatable(key).withStyle(ChatFormatting.GREEN));
        }
        return list;
    }

    /** True when the given modifier instance is installed on the golem. */
    public static boolean hasUpgrade(AbstractGolemEntity<?, ?> entity, GolemModifier modifier) {
        return entity.getModifiers().containsKey(modifier);
    }
}
