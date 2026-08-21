package a_silly_cat.golems_arsenal.mixin.golemmagicka;

import a_silly_cat.golems_arsenal.compat.golemmagicka.GolemScrollData;
import a_silly_cat.golems_arsenal.compat.golemmagicka.GolemScrollModifier;
import com.mojang.logging.LogUtils;
import dev.xkmc.golemmagicka.content.entity.SpellEntry;
import dev.xkmc.golemmagicka.util.SpellCategoryUtil;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds the spell recorded on a scroll-upgrade golem to Golem Magicka's available spell pool.
 * The golem then picks it up through the normal wizard goal (mana/cooldown/bans still apply).
 */
@Mixin(SpellCategoryUtil.class)
public abstract class SpellCategoryUtilMixin {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Inject(method = "getSpells", at = @At("RETURN"), cancellable = true)
    private static void golemsArsenal$addScrollSpell(LivingEntity entity,
                                                     CallbackInfoReturnable<List<SpellEntry>> cir) {
        if (!(entity instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        if (!GolemScrollModifier.hasUpgrade(golem)) {
            return;
        }
        if (golem.tickCount % 40 == 0) {
            LOGGER.info("[GolemsArsenal] scroll upgrade detected on golem {}, spell pool size before: {}",
                    golem, cir.getReturnValue().size());
        }
        ResourceLocation spellId = GolemScrollData.getSpellId(golem);
        int level = GolemScrollData.getLevel(golem);
        if (spellId == null || level <= 0) {
            if (golem.tickCount % 40 == 0) {
                LOGGER.info("[GolemsArsenal] scroll golem {} has no recorded spell (id={}, level={})",
                        golem, spellId, level);
            }
            return;
        }
        AbstractSpell spell = SpellRegistry.getSpell(spellId);
        if (spell == null || spell == SpellRegistry.none()) {
            LOGGER.info("[GolemsArsenal] scroll golem {} recorded spell {} not found in registry", golem, spellId);
            return;
        }
        List<SpellEntry> list = new ArrayList<>(cir.getReturnValue());
        list.add(new SpellEntry(spell, level, CastSource.SCROLL));
        cir.setReturnValue(list);
        LOGGER.info("[GolemsArsenal] scroll golem {} added spell {} lv{} to pool, pool size after: {}",
                golem, spellId, level, list.size());
    }
}
