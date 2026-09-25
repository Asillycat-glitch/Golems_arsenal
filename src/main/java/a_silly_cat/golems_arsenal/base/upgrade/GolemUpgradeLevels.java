package a_silly_cat.golems_arsenal.base.upgrade;

import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.item.upgrade.IUpgradeItem;
import dev.xkmc.modulargolems.content.modifier.base.ModifierInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the effective level of a Modular Golems modifier contributed by the upgrade items installed
 * on a golem.
 * <p>
 * Only upgrade items are counted: materials can grant the same modifier (e.g. a material that also
 * carries damage levels), and those must not unlock upgrade-gated features. Repeated installations
 * sum up, matching how Modular Golems aggregates levels in {@code GolemMaterial.collectModifiers}.
 */
public final class GolemUpgradeLevels {

    /**
     * Per-modifier cache of "how much level does this upgrade item grant towards it". The modifier is
     * the outer key on purpose: the same item answers differently for different modifiers (石英升级
     * grants {@code damage_up} but nothing towards {@code regeneration_up}), so a cache keyed by the
     * item alone would freeze the first answer for every later query.
     */
    private static final Map<ResourceLocation, Map<Item, Integer>> CACHE = new ConcurrentHashMap<>();

    /** Effective level of {@code modifierId} (e.g. {@code modulargolems:damage_up}) on the golem. */
    public static int levelOf(AbstractGolemEntity<?, ?> golem, String modifierId) {
        ResourceLocation target = ResourceLocation.tryParse(modifierId);
        if (target == null) {
            return 0;
        }
        Map<Item, Integer> byItem = CACHE.computeIfAbsent(target, key -> new ConcurrentHashMap<>());
        int level = 0;
        for (Item item : golem.getUpgrades()) {
            level += byItem.computeIfAbsent(item, key -> levelOfItem(key, target));
        }
        return level;
    }

    private static int levelOfItem(Item item, ResourceLocation target) {
        if (!(item instanceof IUpgradeItem upgrade)) {
            return 0;
        }
        int level = 0;
        for (ModifierInstance instance : upgrade.get()) {
            if (target.equals(instance.mod().getRegistryName())) {
                level += instance.level();
            }
        }
        return level;
    }

    private GolemUpgradeLevels() {
    }
}
