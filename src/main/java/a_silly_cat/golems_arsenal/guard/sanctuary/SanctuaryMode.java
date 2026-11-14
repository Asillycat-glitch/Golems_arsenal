package a_silly_cat.golems_arsenal.guard.sanctuary;

/**
 * Sanctuary 的三档。
 *
 * <p>与 {@code CombatMode} 同构：OFF 不做判定（所有 hurt 直接准入）；SHADOW 只记录
 * {@code would_reject}，仍然准入；ENFORCE 才真的取消事件。</p>
 */
public enum SanctuaryMode {
    OFF,
    SHADOW,
    ENFORCE
}
