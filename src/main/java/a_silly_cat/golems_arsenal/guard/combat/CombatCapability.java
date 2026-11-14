package a_silly_cat.golems_arsenal.guard.combat;

/**
 * 战斗物品的“能力”，不是 mod id / item id。
 *
 * <p>白名单是 {@link #NORMAL_MELEE} / {@link #NORMAL_RANGED} 这类正常管线能力；
 * {@link #CUSTOM_HOOK} 表示物品自己覆写了战斗/使用钩子，默认没有适配器，整个交互拒绝。</p>
 */
public enum CombatCapability {
    NORMAL_MELEE,
    NORMAL_RANGED,
    CUSTOM_HOOK,
    CONTINUOUS_HOOK,
    GLOBAL_EVENT_HOOK,
    PROJECTILE_OR_SPELL
}
