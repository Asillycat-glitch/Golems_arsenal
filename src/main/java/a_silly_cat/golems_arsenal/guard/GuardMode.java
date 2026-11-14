package a_silly_cat.golems_arsenal.guard;

/**
 * Guard 的三档状态。
 *
 * <p>OFF：不启用；SHADOW：只记录；ENFORCE：按策略执行。
 * 自动检测永远只到 SHADOW，ENFORCE 必须是显式动作。</p>
 */
public enum GuardMode {
    OFF,
    SHADOW,
    ENFORCE
}
