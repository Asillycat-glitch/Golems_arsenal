package a_silly_cat.golems_arsenal.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 拖尾 / 月牙的几何生成器。
 * <p>
 * 两种剖面，用途不同：
 * <ul>
 *   <li>{@link #ribbon} —— 「头粗 → 尾细」，出来是<b>尾迹/飘带</b>；</li>
 *   <li>{@link #crescent} —— 「两端收尖、中段最厚」，出来是<b>月牙/刃口</b>。</li>
 * </ul>
 * 两者的带宽轴都是「飞行方向 × 视线方向」，所以几何永远正对相机，侧看不会变成一条线。
 * <p>
 * 本类不持有状态、不依赖具体实体，任何投射物都能直接复用。
 */
public final class TrailMesh {

    private TrailMesh() {
    }

    // ==================== 剖面一：尾迹（头粗 → 尾细） ====================

    public static boolean ribbon(VertexConsumer vc, PoseStack.Pose pose,
                                 Vec3 origin, Vec3 dir, Vec3 eye,
                                 float length, float headWidth, float tailWidth,
                                 int headRgb, int tailRgb,
                                 float headAlpha, float tailAlpha,
                                 int segments, float taperPow) {
        return ribbon(vc, pose, origin, dir, eye, length, headWidth, tailWidth,
                headRgb, tailRgb, headAlpha, tailAlpha, segments, taperPow, 0.0F);
    }

    /**
     * 铺一条锥形三角带（尾迹）。
     *
     * @param bow 中线在中段向侧向偏移的最大距离（格），沿带宽轴；0 = 笔直
     */
    public static boolean ribbon(VertexConsumer vc, PoseStack.Pose pose,
                                 Vec3 origin, Vec3 dir, Vec3 eye,
                                 float length, float headWidth, float tailWidth,
                                 int headRgb, int tailRgb,
                                 float headAlpha, float tailAlpha,
                                 int segments, float taperPow, float bow) {
        Vec3 toEye = eye.subtract(origin);
        if (toEye.lengthSqr() < 1.0E-8) {
            return false;
        }
        Vec3 side = dir.cross(toEye);
        // 退化兜底：投射物"朝着相机飞来/朝远处飞去"时 dir 与 toEye 接近平行，
        // 叉积趋近零，带宽方向不可定义。此时**不能直接放弃**（否则拖尾会整条消失，
        // 而那恰恰是最常见的观察角度），改用世界锚定的侧向量。
        if (side.lengthSqr() < 1.0E-6) {
            Vec3 up = new Vec3(0.0, 1.0, 0.0);
            side = dir.cross(up);
            if (side.lengthSqr() < 1.0E-6) {
                side = new Vec3(1.0, 0.0, 0.0);
            }
        }
        side = side.normalize();

        int hr = (headRgb >> 16) & 0xFF;
        int hg = (headRgb >> 8) & 0xFF;
        int hb = headRgb & 0xFF;
        int tr = (tailRgb >> 16) & 0xFF;
        int tg = (tailRgb >> 8) & 0xFF;
        int tb = tailRgb & 0xFF;

        Vec3 prevL = null;
        Vec3 prevR = null;
        int prevRr = 0, prevGg = 0, prevBb = 0, prevAa = 0;

        for (int i = 0; i <= segments; i++) {
            float t = i / (float) segments;

            float w = Mth.lerp((float) Math.pow(t, taperPow), headWidth, tailWidth);
            float lateral = bow == 0.0F ? 0.0F : bow * Mth.sin((float) Math.PI * t);
            Vec3 center = origin.subtract(dir.scale(length * t)).add(side.scale(lateral));

            Vec3 left = center.subtract(side.scale(w));
            Vec3 right = center.add(side.scale(w));

            int r = (int) Mth.lerp(t, hr, tr);
            int g = (int) Mth.lerp(t, hg, tg);
            int b = (int) Mth.lerp(t, hb, tb);
            int a = (int) (Mth.lerp(t, headAlpha, tailAlpha) * 255.0F);

            if (prevL != null) {
                vertex(vc, pose, origin, prevL, prevRr, prevGg, prevBb, prevAa);
                vertex(vc, pose, origin, prevR, prevRr, prevGg, prevBb, prevAa);
                vertex(vc, pose, origin, right, r, g, b, a);

                vertex(vc, pose, origin, prevL, prevRr, prevGg, prevBb, prevAa);
                vertex(vc, pose, origin, right, r, g, b, a);
                vertex(vc, pose, origin, left, r, g, b, a);
            }

            prevL = left;
            prevR = right;
            prevRr = r;
            prevGg = g;
            prevBb = b;
            prevAa = a;
        }
        return true;
    }

    // ==================== 剖面二：月牙（两端收尖、中段最厚） ====================

    /**
     * 铺一块<b>月牙</b>（斩击弧）。
     * <p>
     * 三个要点，缺一个就不像月牙：
     * <ol>
     *   <li><b>剖面</b>用 {@code sin}：两端宽度为 0、中段最厚。用 ribbon 那种「头粗尾细」的
     *       剖面无论怎么弯都只会得到飘带/肋骨；</li>
     *   <li><b>弓起方向要世界锚定</b>（见 {@code bowDir}）。用相机侧向量会让弧随视角翻面，
     *       而且对水平飞行的东西常常朝下，表现就是"弧垂向地面"；</li>
     *   <li><b>前段比例</b>（见 {@code frontRatio}）。全铺在实体后方的话，最前方只有一个尖，
     *       读起来不像"朝前的刃口"。给它一部分在前方，月牙才是骑在投射物上的。</li>
     * </ol>
     *
     * @param maxWidth     最厚处（t=0.5）的半宽，格
     * @param bowDir       弓起方向（会被归一化）；零向量 = 不弓起
     * @param bow          沿 {@code bowDir} 的最大偏移（格）
     * @param frontRatio   长度中位于<b>前方</b>的比例：0 = 全在实体后方；0.5 = 以实体为中心；
     *                     1 = 全在前方
     * @param thicknessPow 剖面锐度：1 = 标准 sin 弧；&gt;1 更瘦（厚度集中中段，像刃）；&lt;1 更胖
     */
    public static boolean crescent(VertexConsumer vc, PoseStack.Pose pose,
                                   Vec3 origin, Vec3 dir, Vec3 eye,
                                   float length, float maxWidth,
                                   Vec3 bowDir, float bow, float frontRatio,
                                   int headRgb, int tailRgb,
                                   float headAlpha, float tailAlpha,
                                   int segments, float thicknessPow) {
        Vec3 toEye = eye.subtract(origin);
        if (toEye.lengthSqr() < 1.0E-8) {
            return false;
        }
        // 带宽轴仍跟随相机：保证带子有厚度、不会侧看成一条线
        Vec3 side = dir.cross(toEye);
        if (side.lengthSqr() < 1.0E-8) {
            return false;
        }
        side = side.normalize();

        Vec3 bowAxis = bowDir.lengthSqr() < 1.0E-8 ? Vec3.ZERO : bowDir.normalize();

        int hr = (headRgb >> 16) & 0xFF;
        int hg = (headRgb >> 8) & 0xFF;
        int hb = headRgb & 0xFF;
        int tr = (tailRgb >> 16) & 0xFF;
        int tg = (tailRgb >> 8) & 0xFF;
        int tb = tailRgb & 0xFF;

        Vec3 prevL = null;
        Vec3 prevR = null;
        int prevRr = 0, prevGg = 0, prevBb = 0, prevAa = 0;

        for (int i = 0; i <= segments; i++) {
            float t = i / (float) segments;

            float w = maxWidth * (float) Math.pow(Mth.sin((float) Math.PI * t), thicknessPow);
            float lateral = bow == 0.0F ? 0.0F : bow * Mth.sin((float) Math.PI * t);
            // t=0 落在实体<b>前方</b> frontRatio × length 处，t=1 落在后方
            Vec3 center = origin.add(dir.scale(length * (frontRatio - t)))
                    .add(bowAxis.scale(lateral));

            Vec3 left = center.subtract(side.scale(w));
            Vec3 right = center.add(side.scale(w));

            int r = (int) Mth.lerp(t, hr, tr);
            int g = (int) Mth.lerp(t, hg, tg);
            int b = (int) Mth.lerp(t, hb, tb);
            int a = (int) (Mth.lerp(t, headAlpha, tailAlpha) * 255.0F);

            if (prevL != null) {
                vertex(vc, pose, origin, prevL, prevRr, prevGg, prevBb, prevAa);
                vertex(vc, pose, origin, prevR, prevRr, prevGg, prevBb, prevAa);
                vertex(vc, pose, origin, right, r, g, b, a);

                vertex(vc, pose, origin, prevL, prevRr, prevGg, prevBb, prevAa);
                vertex(vc, pose, origin, right, r, g, b, a);
                vertex(vc, pose, origin, left, r, g, b, a);
            }

            prevL = left;
            prevR = right;
            prevRr = r;
            prevGg = g;
            prevBb = b;
            prevAa = a;
        }
        return true;
    }

    /**
     * 铺一块<b>有厚度的月牙</b>，三个轴由调用方显式给出 —— <b>不依赖相机</b>。
     * <p>
     * 这是"鼓包朝前"的正确构造。与 {@link #crescent} 的关键差别：
     * 那个版本把带子铺在"飞行方向 × 视线方向"的平面里（朝相机的 billboard），
     * 于是"前方"永远表达不出来（相机在背后时，飞行方向在屏幕平面内几乎没有分量）。
     * 这个版本改成：
     * <ul>
     *   <li>{@code spanAxis} —— 月牙<b>跨度</b>方向（两个尖在这条轴上，例如水平侧向）；</li>
     *   <li>{@code bowAxis} —— 月牙<b>鼓包</b>方向（例如飞行正前方）；</li>
     *   <li>{@code widthAxis} —— 带子的<b>厚度</b>方向（第三个轴，让侧看不会变成一条线）。</li>
     * </ul>
     * 三者应当互相垂直。结果是一块"向前兜出"的弧形刀片，从任何角度都有实体感。
     *
     * @param spanLength   两个尖之间的跨度（格）
     * @param maxWidth     带子最厚处的半厚（格）
     * @param bow          鼓包向外兜出的距离（格）
     * @param thicknessPow 剖面锐度：&gt;1 更瘦（厚度集中中段）
     */
    public static boolean crescentOriented(VertexConsumer vc, PoseStack.Pose pose, Vec3 origin,
                                           Vec3 spanAxis, Vec3 widthAxis, Vec3 bowAxis,
                                           float spanLength, float maxWidth, float bow,
                                           int headRgb, int tailRgb,
                                           float headAlpha, float tailAlpha,
                                           int segments, float thicknessPow) {
        if (spanAxis.lengthSqr() < 1.0E-8 || widthAxis.lengthSqr() < 1.0E-8
                || bowAxis.lengthSqr() < 1.0E-8) {
            return false;
        }
        Vec3 span = spanAxis.normalize();
        Vec3 width = widthAxis.normalize();
        Vec3 bowDir = bowAxis.normalize();

        int hr = (headRgb >> 16) & 0xFF;
        int hg = (headRgb >> 8) & 0xFF;
        int hb = headRgb & 0xFF;
        int tr = (tailRgb >> 16) & 0xFF;
        int tg = (tailRgb >> 8) & 0xFF;
        int tb = tailRgb & 0xFF;

        Vec3 prevL = null;
        Vec3 prevR = null;
        int prevRr = 0, prevGg = 0, prevBb = 0, prevAa = 0;

        for (int i = 0; i <= segments; i++) {
            float t = i / (float) segments;

            // 剖面：两端收尖、中段最厚
            float w = maxWidth * (float) Math.pow(Mth.sin((float) Math.PI * t), thicknessPow);
            // 跨度：t=0 在一个尖，t=1 在另一个尖
            // 鼓包：沿 bowAxis 兜出，两端归零 → 两个尖仍在跨度的两端
            Vec3 center = origin.add(span.scale(spanLength * (0.5F - t)))
                    .add(bowDir.scale(bow * Mth.sin((float) Math.PI * t)));

            Vec3 left = center.subtract(width.scale(w));
            Vec3 right = center.add(width.scale(w));

            int r = (int) Mth.lerp(t, hr, tr);
            int g = (int) Mth.lerp(t, hg, tg);
            int b = (int) Mth.lerp(t, hb, tb);
            int a = (int) (Mth.lerp(t, headAlpha, tailAlpha) * 255.0F);

            if (prevL != null) {
                vertex(vc, pose, origin, prevL, prevRr, prevGg, prevBb, prevAa);
                vertex(vc, pose, origin, prevR, prevRr, prevGg, prevBb, prevAa);
                vertex(vc, pose, origin, right, r, g, b, a);

                vertex(vc, pose, origin, prevL, prevRr, prevGg, prevBb, prevAa);
                vertex(vc, pose, origin, right, r, g, b, a);
                vertex(vc, pose, origin, left, r, g, b, a);
            }

            prevL = left;
            prevR = right;
            prevRr = r;
            prevGg = g;
            prevBb = b;
            prevAa = a;
        }
        return true;
    }

    // ==================== 尾迹（纺锤形） ====================

    /**
     * 铺一条<b>纺锤形尾迹</b>：宽度在两端都收到 0，中段最宽。
     * <p>
     * 与 {@link #ribbon} 的区别：ribbon 是"起点粗 → 终点细"的楔形，起点那端是一个
     * <b>硬边</b>（看起来像贴了一条胶带）；wake 两端都收尖，读起来才是"一段能量痕迹"。
     * <p>
     * 带宽轴跟随相机（退化时用世界正上方兜底），所以任何角度都看得见。
     *
     * @param peakAt 最宽处的位置：0.5 = 正中；偏小则靠近起点更粗
     */
    public static boolean wake(VertexConsumer vc, PoseStack.Pose pose,
                               Vec3 origin, Vec3 dir, Vec3 eye,
                               float length, float maxWidth,
                               int rgbNear, int rgbFar,
                               float alphaNear, float alphaFar,
                               int segments, float peakAt) {
        Vec3 toEye = eye.subtract(origin);
        if (toEye.lengthSqr() < 1.0E-8) {
            return false;
        }
        Vec3 side = dir.cross(toEye);
        if (side.lengthSqr() < 1.0E-6) {
            Vec3 up = new Vec3(0.0, 1.0, 0.0);
            side = dir.cross(up);
            if (side.lengthSqr() < 1.0E-6) {
                side = new Vec3(1.0, 0.0, 0.0);
            }
        }
        side = side.normalize();

        int nr = (rgbNear >> 16) & 0xFF;
        int ng = (rgbNear >> 8) & 0xFF;
        int nb = rgbNear & 0xFF;
        int fr = (rgbFar >> 16) & 0xFF;
        int fg = (rgbFar >> 8) & 0xFF;
        int fb = rgbFar & 0xFF;

        float peak = Mth.clamp(peakAt, 0.05F, 0.95F);

        Vec3 prevL = null;
        Vec3 prevR = null;
        int prevRr = 0, prevGg = 0, prevBb = 0, prevAa = 0;

        for (int i = 0; i <= segments; i++) {
            float t = i / (float) segments;
            // 纺锤：t=0 与 t=1 都是 0，在 peak 处最大
            float shape = t <= peak
                    ? Mth.sin((float) Math.PI * 0.5F * (t / peak))
                    : Mth.sin((float) Math.PI * 0.5F * ((1.0F - t) / (1.0F - peak)));
            float w = maxWidth * (float) Math.pow(Mth.clamp(shape, 0.0F, 1.0F), 0.85F);

            Vec3 center = origin.subtract(dir.scale(length * t));
            Vec3 left = center.subtract(side.scale(w));
            Vec3 right = center.add(side.scale(w));

            int r = (int) Mth.lerp(t, nr, fr);
            int g = (int) Mth.lerp(t, ng, fg);
            int b = (int) Mth.lerp(t, nb, fb);
            int a = (int) (Mth.lerp(t, alphaNear, alphaFar) * 255.0F);

            if (prevL != null) {
                vertex(vc, pose, origin, prevL, prevRr, prevGg, prevBb, prevAa);
                vertex(vc, pose, origin, prevR, prevRr, prevGg, prevBb, prevAa);
                vertex(vc, pose, origin, right, r, g, b, a);

                vertex(vc, pose, origin, prevL, prevRr, prevGg, prevBb, prevAa);
                vertex(vc, pose, origin, right, r, g, b, a);
                vertex(vc, pose, origin, left, r, g, b, a);
            }

            prevL = left;
            prevR = right;
            prevRr = r;
            prevGg = g;
            prevBb = b;
            prevAa = a;
        }
        return true;
    }

    // ==================== 内部 ====================

    /**
     * 写一个顶点。
     * <p>
     * <b>坐标系说明</b>：{@code EntityRenderDispatcher} 在调用 renderer 前已经把 PoseStack
     * 平移成"实体位置 = 原点"，所以这里要把世界坐标减去 {@code origin} 才是局部坐标。
     */
    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, Vec3 origin,
                               Vec3 world, int r, int g, int b, int a) {
        vc.vertex(pose.pose(),
                        (float) (world.x - origin.x),
                        (float) (world.y - origin.y),
                        (float) (world.z - origin.z))
                .color(r, g, b, a)
                .endVertex();
    }
}
