package a_silly_cat.golems_arsenal.compat.l2artifacts;

import dev.xkmc.l2artifacts.content.core.ArtifactSet;
import dev.xkmc.l2artifacts.content.core.BaseArtifact;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads any L2Artifacts set's state through the mod's own API. Lives in the compat package: only
 * call after {@code ModList.isLoaded("l2artifacts")}.
 */
public final class ArtifactSetHelper {

    private static final int MAX_RANK = 5;
    private static final Map<String, ArtifactSet> CACHE = new ConcurrentHashMap<>();

    /**
     * Rarity tier of the given set while the entity wears at least {@code pieces} pieces of it, or 0
     * when the set is not active.
     * <p>
     * L2Artifacts itself hands effects {@code ranks()[pieceCount]}, which is "how many worn pieces
     * are at least as rare as that count" - a full set of common pieces reports 0. That value only
     * scales the numbers here, so it is clamped to 1..5 and the activation test is the piece count,
     * never the tier.
     */
    public static int rank(LivingEntity entity, String set, int pieces) {
        if (!ModList.get().isLoaded("l2artifacts")) {
            return 0;
        }
        ArtifactSet artifactSet = set(set);
        if (artifactSet == null) {
            return 0;
        }
        Optional<ArtifactSet.SetContext> ctx = artifactSet.getSetCount(entity);
        if (ctx.isEmpty() || ctx.get().count() < pieces) {
            return 0;
        }
        int[] ranks = ctx.get().ranks();
        int rank = pieces < ranks.length ? ranks[pieces] : 0;
        return Math.max(1, Math.min(MAX_RANK, rank));
    }

    private static ArtifactSet set(String name) {
        ArtifactSet cached = CACHE.get(name);
        if (cached != null) {
            return cached;
        }
        ResourceLocation icon = ResourceLocation.tryParse("l2artifacts:" + name + "_head_1");
        Item item = icon == null ? null : ForgeRegistries.ITEMS.getValue(icon);
        if (item instanceof BaseArtifact artifact) {
            ArtifactSet found = artifact.set.get();
            CACHE.put(name, found);
            return found;
        }
        return null;
    }

    private ArtifactSetHelper() {
    }
}
