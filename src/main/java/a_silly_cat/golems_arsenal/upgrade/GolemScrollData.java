package a_silly_cat.golems_arsenal.upgrade;

import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Per-golem spell storage for the scroll upgrade. Uses the entity's persistent data, which Forge
 * saves automatically together with the golem, so no extra capability is needed.
 */
public final class GolemScrollData {
    private static final String SPELL_KEY = "GolemsArsenalScrollSpell";
    private static final String LEVEL_KEY = "GolemsArsenalScrollLevel";

    public static void set(AbstractGolemEntity<?, ?> golem, ResourceLocation spellId, int level) {
        CompoundTag tag = golem.getPersistentData();
        tag.putString(SPELL_KEY, spellId.toString());
        tag.putInt(LEVEL_KEY, level);
    }

    public static void clear(AbstractGolemEntity<?, ?> golem) {
        golem.getPersistentData().remove(SPELL_KEY);
        golem.getPersistentData().remove(LEVEL_KEY);
    }

    @Nullable
    public static ResourceLocation getSpellId(AbstractGolemEntity<?, ?> golem) {
        String s = golem.getPersistentData().getString(SPELL_KEY);
        return s.isEmpty() ? null : ResourceLocation.tryParse(s);
    }

    public static int getLevel(AbstractGolemEntity<?, ?> golem) {
        return golem.getPersistentData().getInt(LEVEL_KEY);
    }

    private GolemScrollData() {
    }
}
