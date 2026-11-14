package a_silly_cat.golems_arsenal.guard.sanctuary;

/**
 * 哑投影的两档（配置 {@code sanctuary.projection_mode}）。
 *
 * <p>为什么要拆出来：{@link Projection} 的"全封死"（伤害/交互/掉落全拒 + 无敌 + noAi）
 * 是<b>测试态</b>，目的是先测清楚"哪一层没挡住"。一旦确认挡住，正常游玩必须能把它关掉，
 * 而<b>关掉封</b>不等于<b>关掉护栏</b>——{@link Anchor} / 写门 / 移除守卫 / 账本要照常跑。</p>
 *
 * <ul>
 *   <li>{@link #SEALED}：测试态（默认）。傀儡在这一层被压成哑投影，只剩显示。</li>
 *   <li>{@link #NORMAL}：正常态。不拦伤害、不拦交互、不动 AI，锚点快照照旧拍。</li>
 * </ul>
 */
public enum ProjectionMode {
    SEALED,
    NORMAL
}
