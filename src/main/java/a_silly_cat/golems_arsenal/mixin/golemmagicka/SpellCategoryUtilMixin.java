package a_silly_cat.golems_arsenal.mixin.golemmagicka;

import dev.xkmc.golemmagicka.content.entity.SpellEntry;
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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds the spell recorded on a scroll-upgrade golem to Golem Magicka's available spell pool.
 * The golem then picks it up through the normal wizard goal (mana/cooldown/bans still apply).
 *
 * <p>All Golems Arsenal references are reflective; see GolemSpellManagerMixin.</p>
 */
@Mixin(targets = "dev.xkmc.golemmagicka.util.SpellCategoryUtil")
public abstract class SpellCategoryUtilMixin {
    @Inject(method = "getSpells", at = @At("RETURN"), cancellable = true)
    private static void golemsArsenal$addScrollSpell(LivingEntity entity,
                                                     CallbackInfoReturnable<List<SpellEntry>> cir) {
        if (!(entity instanceof AbstractGolemEntity<?, ?> golem)
                || !GolemMagickaReflection.hasScroll(golem)) {
            return;
        }
        ResourceLocation spellId = GolemMagickaReflection.spellId(golem);
        int level = GolemMagickaReflection.spellLevel(golem);
        if (spellId == null || level <= 0) {
            return;
        }
        AbstractSpell spell = SpellRegistry.getSpell(spellId);
        if (spell == null || spell == SpellRegistry.none()) {
            return;
        }
        List<SpellEntry> list = new ArrayList<>(cir.getReturnValue());
        list.add(new SpellEntry(spell, level, CastSource.SCROLL));
        cir.setReturnValue(list);
    }

    private static final class GolemMagickaReflection {
        private static Method hasUpgrade;
        private static Method getSpellId;
        private static Method getLevel;
        private static Object scrollModifier;

        private static boolean hasScroll(AbstractGolemEntity<?, ?> golem) {
            try {
                if (scrollModifier == null) {
                    Class<?> compat = Class.forName("a_silly_cat.golems_arsenal.compat.golemmagicka.GolemMagickaCompat");
                    Object entry = compat.getField("SCROLL").get(null);
                    scrollModifier = entry.getClass().getMethod("get").invoke(entry);
                }
                if (hasUpgrade == null) {
                    Class<?> flag = Class.forName("a_silly_cat.golems_arsenal.base.upgrade.GolemFlagModifier");
                    Class<?> modifier = Class.forName("dev.xkmc.modulargolems.content.modifier.base.GolemModifier");
                    hasUpgrade = flag.getMethod("hasUpgrade", AbstractGolemEntity.class, modifier);
                }
                return (boolean) hasUpgrade.invoke(null, golem, scrollModifier);
            } catch (Throwable ignored) {
                return false;
            }
        }

        private static ResourceLocation spellId(AbstractGolemEntity<?, ?> golem) {
            try {
                if (getSpellId == null) {
                    Class<?> data = Class.forName("a_silly_cat.golems_arsenal.compat.golemmagicka.GolemScrollData");
                    getSpellId = data.getMethod("getSpellId", AbstractGolemEntity.class);
                }
                return (ResourceLocation) getSpellId.invoke(null, golem);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static int spellLevel(AbstractGolemEntity<?, ?> golem) {
            try {
                if (getLevel == null) {
                    Class<?> data = Class.forName("a_silly_cat.golems_arsenal.compat.golemmagicka.GolemScrollData");
                    getLevel = data.getMethod("getLevel", AbstractGolemEntity.class);
                }
                return (int) getLevel.invoke(null, golem);
            } catch (Throwable ignored) {
                return 0;
            }
        }
    }
}
