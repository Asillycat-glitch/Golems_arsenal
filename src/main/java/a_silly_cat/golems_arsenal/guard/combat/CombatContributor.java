package a_silly_cat.golems_arsenal.guard.combat;

import java.util.Set;

/** 一个 CombatAction 的贡献者（物品、投射物、法术、全局 handler 都可实现）。 */
public interface CombatContributor {

    String source();

    Set<CombatCapability> capabilities();

    default boolean supported() {
        return ItemProfile.ALLOWED.containsAll(capabilities());
    }
}
