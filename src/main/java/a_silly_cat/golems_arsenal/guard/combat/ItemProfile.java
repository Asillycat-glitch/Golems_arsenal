package a_silly_cat.golems_arsenal.guard.combat;

import java.util.Set;

/**
 * 一个物品类的能力画像。
 *
 * <p>{@code supported} 是“所有能力都在白名单里”的 all-or-nothing 结果：
 * 复合物品只要多出一个 {@link CombatCapability#CUSTOM_HOOK}，整个交互就不支持。</p>
 */
public record ItemProfile(String source, Set<CombatCapability> capabilities,
                          boolean combat, boolean supported) implements CombatContributor {

    public static final Set<CombatCapability> ALLOWED =
            Set.of(CombatCapability.NORMAL_MELEE, CombatCapability.NORMAL_RANGED);

    public static ItemProfile of(String source, Set<CombatCapability> capabilities) {
        Set<CombatCapability> copy = Set.copyOf(capabilities);
        boolean combat = !copy.isEmpty();
        boolean supported = ALLOWED.containsAll(copy);
        return new ItemProfile(source, copy, combat, supported);
    }

    public String describe() {
        return "class=" + source + " caps=" + capabilities
                + " combat=" + combat + " supported=" + supported;
    }
}
