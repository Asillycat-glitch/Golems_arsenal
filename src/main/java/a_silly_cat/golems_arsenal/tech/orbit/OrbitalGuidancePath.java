package a_silly_cat.golems_arsenal.tech.orbit;

/**
 * 引导阶段的**运动轨迹**（不是图形！）。
 *
 * <p><b>2026-10-19 返工</b>：上一版把这条轨迹当图案画在地上，出来是一个发光六芒星
 * ——那是魔法侧的观感，和 MEK 科技联动不搭。用户要的是"落地的激光按这个轨迹移动"，
 * 所以这里只提供**位置数学**，一行渲染都没有。
 *
 * <p><b>轨迹</b>（对应用户给的那张参考图的运动方式）：
 * <ol>
 *   <li>外圈——顺时针一圈；</li>
 *   <li>六芒星——两个等边三角形的 6 条弦；</li>
 *   <li>内圈——小圆；</li>
 *   <li>收束——从内圈螺旋收到圆心（"逆向聚集到中央"）。</li>
 * </ol>
 *
 * <p><b>为什么用线段表而不是点序列</b>：六芒星是两个**不连续**的三角形，画完 A 的
 * 最后一条弦要跳回 B 的起点，点序列表达不了这种断开。线段表可以任意断开。
 *
 * <p>所有坐标都是**单位半径**下的相对量，实际使用时乘以半径即可。
 */
public final class OrbitalGuidancePath {

    public static final int OUTER_SEGS = 96;
    public static final int STAR_SEGS = 6;
    public static final int INNER_SEGS = 48;
    /** 图形段总数。 */
    public static final int FIGURE_SEGS = OUTER_SEGS + STAR_SEGS + INNER_SEGS;

    /** 六芒星离圆心的距离（相对半径）。 */
    private static final float STAR_R = 0.94F;
    /** 内圈相对半径。 */
    private static final float INNER_RATIO = 0.45F;

    /** 图形阶段占总进度的比例，剩下的（0.88..1）用于收束。 */
    public static final float FIGURE_END = 0.88F;

    /** 每条线段 {x0, z0, x1, z1}，单位半径。 */
    private static final float[][] SEGMENTS = build();

    private OrbitalGuidancePath() {
    }

    private static float[][] build() {
        float[][] out = new float[FIGURE_SEGS][];
        int k = 0;
        // ① 外圈：从正上方（-Z）顺时针
        for (int i = 0; i < OUTER_SEGS; i++) {
            double a0 = -Math.PI / 2.0 - (i / (double) OUTER_SEGS) * Math.PI * 2.0;
            double a1 = -Math.PI / 2.0 - ((i + 1) / (double) OUTER_SEGS) * Math.PI * 2.0;
            out[k++] = seg(a0, a1, 1.0F);
        }
        // ② 六芒星：两个等边三角形，各自闭合
        double[] triA = {-Math.PI / 2.0, Math.PI / 6.0, 5 * Math.PI / 6.0};
        double[] triB = {-Math.PI / 6.0, Math.PI / 2.0, 7 * Math.PI / 6.0};
        for (double[] tri : new double[][]{triA, triB}) {
            for (int i = 0; i < 3; i++) {
                out[k++] = seg(tri[i], tri[(i + 1) % 3], STAR_R);
            }
        }
        // ③ 内圈：正方向（和外圈相反，让运动有来回感）
        for (int i = 0; i < INNER_SEGS; i++) {
            double a0 = -Math.PI / 2.0 + (i / (double) INNER_SEGS) * Math.PI * 2.0;
            double a1 = -Math.PI / 2.0 + ((i + 1) / (double) INNER_SEGS) * Math.PI * 2.0;
            out[k++] = seg(a0, a1, INNER_RATIO);
        }
        return out;
    }

    private static float[] seg(double a0, double a1, float r) {
        return new float[]{
                (float) (Math.cos(a0) * r), (float) (Math.sin(a0) * r),
                (float) (Math.cos(a1) * r), (float) (Math.sin(a1) * r)};
    }

    /**
     * 图形部分的线段数（外圈 + 六芒星 + 内圈）。
     *
     * <p>这个访问器是给「把轨迹当图案画出来」的场合用的（见
     * {@code client/OrbitalSigilRenderer}：一个保留下来做渲染示例的法阵）。
     * 同一份线段表，一边用来驱动落点运动，一边用来画图案——**不各维护一份**。
     */
    public static int figureSegmentCount() {
        return FIGURE_SEGS;
    }

    /**
     * 取第 {@code index} 条图形线段（已乘以半径）。
     *
     * @return {x0, z0, x1, z1}，单位格
     */
    public static float[] figureSegment(int index, float radius) {
        if (index < 0 || index >= FIGURE_SEGS) {
            return null;
        }
        float[] s = SEGMENTS[index];
        return new float[]{s[0] * radius, s[1] * radius, s[2] * radius, s[3] * radius};
    }

    // ------------------------------------------------------------------
    // 聚合瞄准（2026-10-19 新增）
    //
    // 用户的设计：引导和发射之间还有一段"聚合瞄准"，由**六条**引导激光完成。
    // 只有一条是做不出聚合感的——这是我之前漏掉的前提。
    //   ① 外部六条先整体顺时针转 60°；
    //   ② 然后以恒定速度向中心聚合（只做一次，不像护体光柱那样循环）；
    //   ③ 聚合后不立刻消失，在瞄准中心稍微放大（亮度要压住，六条叠一起极易过曝）。
    // ------------------------------------------------------------------

    /**
     * 相位时长（tick）。整段 50 tick = 2.5 秒。
     *
     * <p>用户 2026-10-19 定的节奏：
     * <pre>
     *   0.0 – 0.5s   10 tick   只转不收（圆心方向速度为 0）
     *   0.5 – 1.5s   20 tick   边转边收（角速度保持不变）
     *   1.5 – 2.5s   20 tick   中心放大
     * </pre>
     */
    public static final int AIM_ROTATE_TICKS = 8;
    public static final int AIM_CONVERGE_TICKS = 16;
    public static final int AIM_GROW_TICKS = 16;
    public static final int AIM_TOTAL_TICKS = AIM_ROTATE_TICKS + AIM_CONVERGE_TICKS + AIM_GROW_TICKS;

    /** 相位边界（按 progress 0..1 换算）。 */
    public static final float AIM_ROTATE_END = AIM_ROTATE_TICKS / (float) AIM_TOTAL_TICKS;
    public static final float AIM_CONVERGE_END =
            (AIM_ROTATE_TICKS + AIM_CONVERGE_TICKS) / (float) AIM_TOTAL_TICKS;

    /** 六条激光的条数。 */
    public static final int AIM_BEAM_COUNT = 6;
    /**
     * **全程不变的角速度（度/tick）**。
     *
     * <p>外圈那 0.5 秒（10 tick）转 60° ⇒ 6°/tick。之后聚合的 20 tick 里
     * **继续用同一个角速度**（用户原话："期间参考外圈旋转的角速度一直不变"），
     * 所以聚合阶段再转 120°，总转角 180°。
     */
    public static final float AIM_ANGULAR_DEG_PER_TICK = 6.0F;

    /** 外圈阶段转过的角度（由角速度推出）。 */
    public static final float AIM_ROTATE_DEGREES =
            AIM_ANGULAR_DEG_PER_TICK * AIM_ROTATE_TICKS;

    /**
     * **聚合阶段单根光柱走完的总转角（度）**。
     *
     * <p>参考本模组「护体光柱」的 {@code BLOOM_CLOSE_ANGLE_DEG = 90°}：
     * 它在 20 tick 里一边加速外扩、一边转 90°（4.5°/tick），所以轨迹是**弧线**而不是
     * 从圆心射出的直线——用户形容成"车轮的辐条，但辐条是弯的"。
     *
     * <p>我们是六根、等分 60°，所以聚合时每根正好转过**一个邻居间距（60°）**
     * ——视觉上刚好"补位"，读起来才像整个圈在收拢（3°/tick）。
     */
    public static final float AIM_SPOKE_SWEEP_DEGREES =
            AIM_ANGULAR_DEG_PER_TICK * AIM_CONVERGE_TICKS;
    /** 聚合到中心后放大的上限倍数。 */
    public static final float AIM_CENTER_SCALE = 1.35F;

    // ------------------------------------------------------------------
    // 聚合曲线直接取自本模组自己的「护体光柱」（LightPillarEntity.BLOOM_*）：
    //     BLOOM_RADIAL_SPEED = 0.2475, BLOOM_RADIAL_ACCEL = 0.015
    //     BLOOM_START_RADIUS = 1.2, BLOOM_MAX_RADIUS = 9.15 → 行程 7.95 格 / 20 tick
    // 它做的是「加速向外扩散」；我们做「加速向内聚合」——同一套 v0/a，
    // 把行程归一化后当作半径的收缩比例，所以两者的运动"手感"是一致的。
    // ------------------------------------------------------------------
    private static final double PILLAR_V0 = 0.2475D;
    private static final double PILLAR_ACCEL = 0.015D;
    private static final double PILLAR_TRAVEL = 7.95D;

    /**
     * 护体光柱的行程曲线（归一化到 0..1）。
     *
     * @param ticks 已经走的 tick（0..AIM_CONVERGE_TICKS）
     */
    private static double pillarTravelFraction(double ticks) {
        double d = PILLAR_V0 * ticks + 0.5D * PILLAR_ACCEL * ticks * ticks;
        return Math.min(1.0D, d / PILLAR_TRAVEL);
    }

    /**
     * 聚合瞄准的一次采样。
     *
     * @param radiusFactor 当前半径相对初始半径的比例（1 = 外圈，0 = 圆心）
     * @param rotationDeg  整体旋转角（度）
     * @param alphaMul     单条的亮度系数（**随聚合下降**，避免六条叠在中心过曝）
     * @param centerScale  聚合完成后中心那一条的放大倍数（未到该阶段为 1）
     */
    public record AimSample(float radiusFactor, float rotationDeg, float alphaMul, float centerScale) {
    }

    /**
     * 采样聚合瞄准的阶段。
     *
     * <p>时间轴（progress）：
     * <pre>
     *   0.00 .. 0.20   原地顺时针转 60°（半径不变，无径向速度）  10 tick
     *   0.20 .. 0.60   加速向内聚合：半径 1 → 0，同时再转 120°   20 tick（角速度不变）
     *   0.60 .. 1.00   中心放大：1.0 → 1.35 倍，亮度回落     20 tick
     * </pre>
     * 合计 40 tick = 2 秒。
     */
    public static AimSample sampleAim(float progress) {
        float p = Math.max(0.0F, Math.min(1.0F, progress));
        if (p < AIM_ROTATE_END) {
            // ① 只转，不缩
            float t = p / AIM_ROTATE_END;
            return new AimSample(1.0F, AIM_ROTATE_DEGREES * t, 1.0F, 1.0F);
        }
        if (p < AIM_CONVERGE_END) {
            // ② 加速向内（曲线来自护体光柱，方向相反）
            float t = (p - AIM_ROTATE_END) / (AIM_CONVERGE_END - AIM_ROTATE_END);
            double ticks = t * AIM_CONVERGE_TICKS;
            float radiusFactor = (float) (1.0D - pillarTravelFraction(ticks));
            // 聚合过程中每根再转 AIM_SPOKE_SWEEP_DEGREES（= 6°/tick × 20 = 120°）。
            // **角速度和外圈阶段完全一致**（用户要求"期间角速度一直不变"），
            // 半径加速收缩 + 角度匀速转 = 弧线的"弯辐条"，不是直线射入。
            // 参考：护体光柱用 BLOOM_CLOSE_ANGLE_DEG = 90°/20tick。
            float rotation = AIM_ROTATE_DEGREES + AIM_SPOKE_SWEEP_DEGREES * t;
            // 六条越靠拢越要压亮度：外圈 1.0 → 贴到中心 1/6
            float alphaMul = 1.0F - (1.0F - 1.0F / AIM_BEAM_COUNT) * t;
            return new AimSample(radiusFactor, rotation, alphaMul, 1.0F);
        }
        // ③ 中心放大（六条已重合在圆心）
        float t = (p - AIM_CONVERGE_END) / (1.0F - AIM_CONVERGE_END);
        float scale = 1.0F + (AIM_CENTER_SCALE - 1.0F) * t;
        // 面积变大而能量不变：不降亮度就会过曝
        float alphaMul = (1.0F / AIM_BEAM_COUNT) * (1.0F - 0.45F * t);
        return new AimSample(0.0F, AIM_ROTATE_DEGREES + AIM_SPOKE_SWEEP_DEGREES, alphaMul, scale);
    }

    /**
     * 进度 → 落点（相对圆心的 XZ 偏移，单位格）。
     *
     * @param progress 0..1
     * @param radius   法阵半径（格）
     * @return 长度 2 的数组 {x, z}
     */
    public static float[] pointAt(float progress, float radius) {
        float p = Math.max(0.0F, Math.min(1.0F, progress));
        if (p >= 1.0F) {
            return new float[]{0.0F, 0.0F};
        }
        if (p < FIGURE_END) {
            // 图形阶段：进度折算到线段表的浮点位置，段内线性插值
            float t = p / FIGURE_END * FIGURE_SEGS;
            int idx = (int) Math.floor(t);
            if (idx >= FIGURE_SEGS) {
                idx = FIGURE_SEGS - 1;
            }
            float local = t - idx;
            float[] s = SEGMENTS[idx];
            float x = s[0] + (s[2] - s[0]) * local;
            float z = s[1] + (s[3] - s[1]) * local;
            return new float[]{x * radius, z * radius};
        }
        // 收束阶段：从内圈半径螺旋收到圆心，同时多转两圈（"逆向聚集"）
        float t = (p - FIGURE_END) / (1.0F - FIGURE_END);
        float spin = t * 3.0F * (float) (Math.PI * 2.0);
        float r = INNER_RATIO * (1.0F - t);
        float ang = (float) (-Math.PI / 2.0) + spin;
        return new float[]{(float) (Math.cos(ang) * r * radius), (float) (Math.sin(ang) * r * radius)};
    }
}
