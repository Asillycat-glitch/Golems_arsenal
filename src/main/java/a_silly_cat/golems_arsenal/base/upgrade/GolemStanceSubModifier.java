package a_silly_cat.golems_arsenal.base.upgrade;

import a_silly_cat.golems_arsenal.Config;
import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.tags.DamageTypeTags;
import net.minecraftforge.event.entity.living.LivingAttackEvent;

import java.util.List;

/**
 * Stance sub-upgrade, following Modular Golems' pickup sub-upgrade pattern (e.g. pickup_mending):
 * it is an independent level-1 modifier whose effect is only meaningful while the parent stance
 * upgrade is installed. Attacks that arrive inside the golem's invulnerability frame window
 * (i.e. would be silently rejected by vanilla's {@code invulnerableTime > 10} check) fill the
 * stance gauge by {@code stance_sub_iframe_gain} points.
 */
public class GolemStanceSubModifier extends GolemModifier {
    public static final int MAX_LEVEL = 1;

    public GolemStanceSubModifier() {
        super(StatFilterType.ATTACK, MAX_LEVEL);
    }

    @Override
    public Component getTooltip(int level) {
        return Component.translatable("upgrade.golems_arsenal.golem_stance_sub")
                .withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        return List.of(Component.translatable("upgrade.golems_arsenal.golem_stance_sub.desc")
                .withStyle(ChatFormatting.GREEN));
    }

    /**
     * LivingAttackEvent fires for every attack attempt - including the ones the vanilla
     * i-frame check silently rejects - so this is the right hook for "hit during i-frames".
     * Damage types with {@code bypasses_cooldown} skip the i-frame window entirely and are excluded.
     */
    @Override
    public void onAttacked(AbstractGolemEntity<?, ?> golem, LivingAttackEvent event, int level) {
        if (golem.level().isClientSide
                || event.getSource().is(DamageTypeTags.BYPASSES_COOLDOWN)
                || golem.invulnerableTime <= 10) {
            return;
        }
        // Requires the parent stance upgrade; addProgress already no-ops without it.
        GolemStanceModifier.addProgress(golem, Config.STANCE_SUB_IFRAME_GAIN.get());
    }
}
