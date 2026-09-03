package a_silly_cat.golems_arsenal.compat.golemmagicka;

import a_silly_cat.golems_arsenal.base.upgrade.GolemFlagModifier;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.SchoolType;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Reads the school of the spell recorded on a golem's scroll upgrade. Lives in the Golem Magicka
 * compat package and references Iron's Spells' API directly; callers must only invoke it after a
 * {@code ModList.isLoaded("golemmagicka")} check so this class is never loaded without the mods.
 */
public final class ScrollSchoolHelper {

    /** True when the scroll upgrade is installed and its recorded spell belongs to the school. */
    public static boolean hasMatchingSchool(AbstractGolemEntity<?, ?> golem, String school) {
        if (!GolemFlagModifier.hasUpgrade(golem, GolemMagickaCompat.SCROLL.get())) {
            return false;
        }
        return school.equals(schoolOf(golem));
    }

    /** School id path of the recorded spell: "fire", "ice", "lightning", ... or null. */
    @Nullable
    public static String schoolOf(AbstractGolemEntity<?, ?> golem) {
        ResourceLocation id = GolemScrollData.getSpellId(golem);
        if (id == null) {
            return null;
        }
        AbstractSpell spell = SpellRegistry.REGISTRY.get().getValue(id);
        if (spell == null) {
            return null;
        }
        SchoolType school = spell.getSchoolType();
        if (school == null) {
            return null;
        }
        ResourceLocation schoolId = school.getId();
        return schoolId == null ? null : schoolId.getPath();
    }

    private ScrollSchoolHelper() {
    }
}
