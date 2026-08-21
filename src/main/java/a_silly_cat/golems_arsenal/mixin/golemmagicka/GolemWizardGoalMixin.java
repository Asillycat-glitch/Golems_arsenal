package a_silly_cat.golems_arsenal.mixin.golemmagicka;

import a_silly_cat.golems_arsenal.compat.golemmagicka.GolemScrollData;
import a_silly_cat.golems_arsenal.compat.golemmagicka.GolemScrollModifier;
import com.mojang.logging.LogUtils;
import dev.xkmc.golemmagicka.content.entity.GolemMagicData;
import dev.xkmc.golemmagicka.content.entity.GolemWizardGoal;
import dev.xkmc.golemmagicka.content.entity.SpellEntry;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.random.SimpleWeightedRandomList;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Logs the final filtered spell pool and mana for scroll-upgrade golems, so we can see whether
 * the recorded spell is being dropped by mana/cooldown filtering.
 */
@Mixin(GolemWizardGoal.class)
public abstract class GolemWizardGoalMixin {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Shadow
    private GolemMagicData data;

    @Inject(method = "updateAvailableSpells", at = @At("RETURN"))
    private void golemsArsenal$logAvailableSpells(CallbackInfoReturnable<SimpleWeightedRandomList<SpellEntry>> cir) {
        AbstractGolemEntity<?, ?> golem = data.golem;
        if (golem == null || !GolemScrollModifier.hasUpgrade(golem)) {
            return;
        }
        if (golem.tickCount % 40 != 0) {
            return;
        }
        MagicData md = data.getMagicData();
        ResourceLocation spellId = GolemScrollData.getSpellId(golem);
        int level = GolemScrollData.getLevel(golem);
        AbstractSpell spell = spellId == null ? null : SpellRegistry.getSpell(spellId);
        boolean cooldown = spell != null && md.getPlayerCooldowns().isOnCooldown(spell);
        LOGGER.info("[GolemsArsenal] scroll golem {} final pool empty={}, mana={}/{}, spell={} lv{}, cost={}, cooldown={}",
                golem, cir.getReturnValue().isEmpty(), md.getMana(),
                (int) golem.getAttributeValue(AttributeRegistry.MAX_MANA.get()),
                spellId, level, spell == null ? -1 : spell.getManaCost(level), cooldown);
    }
}
