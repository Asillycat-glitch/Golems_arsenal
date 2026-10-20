package a_silly_cat.golems_arsenal.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;

/**
 * <b>真正的 3D 体积</b>几何生成器 —— 用来做"有体积感的能量体"。
 * <p>
 * <b>为什么需要它</b>：之前用 {@link TrailMesh#crescentOriented} 做月牙，本质是
 * <b>一个曲面</b>（零厚度），所以从任何非正面角度看都是"剪影/贴纸"，
 * 无论怎么调参数、加几片交叉都只是骗眼睛。
 * <p>
 * 参考 arcanevortex 的棱形法阵核心（{@code NebulaBowArrowDeadEffect1Renderer}）：
 * 它用的是 <b>octahedron（八面体）</b> —— 两个尖 + 一个四边形的赤道，共 8 个三角面，
 * 配白色贴图、半透明、自转。体积感来自两件事：
 * <ol>
 *   <li>它真是立体的，各个面朝向不同；</li>
 *   <li>半透明 + 不写深度，所以能透过近处的面看到远处的面，层次自然出现。</li>
 * </ol>
 * 本类沿用了同样的构造，另外给每个面加了 <b>fresnel 透明度</b>：
 * 正对镜头的面更透明、轮廓处的面更实 —— 这是"能量壳"的观感来源，
 * 也让实心体不会糊成一个色块。
 */
public final class AuraMesh {

    private AuraMesh() {
    }

    /**
     * 一个沿 {@code axis} 拉长、赤道为<b>椭圆</b>的八面体 —— 这是"剑气"的形状。
     * <p>
     * 赤道给成圆（两个半径相等）出来是一颗<b>宝石/晶体</b>；
     * 把两个半径拉开（例如竖直方向 1.6、侧向 0.45）才是<b>扁平的刀锋</b>，
     * 因为剑的截面是细长的、不是圆的。这是"从八面体变形成剑气"的关键一步。
     *
     * @param vc        目标顶点消费者（需支持顶点色）
     * @param pose      姿态，原点必须已在实体位置
     * @param origin    中心点世界坐标
     * @param axis      长轴方向（会被归一化），通常取飞行方向
     * @param equatorA  赤道的第一条轴（例如世界正上方）——构成刀身的"高"
     * @param equatorB  赤道的第二条轴（例如水平侧向）——构成刀身的"厚"
     * @param radiusA   {@code equatorA} 方向的半径（格）
     * @param radiusB   {@code equatorB} 方向的半径（格）
     * @param halfLength 沿长轴的半长（格）
     * @param spinRadians 绕长轴的自转角（弧度）；刀锋一般给 0，晶体才自转
     * @param eye       相机世界坐标，用于 fresnel
     * @param rgb       颜色 0xRRGGBB
     * @param alpha     基准不透明度
     * @param fresnelPow fresnel 陡度：&gt;1 只有很边缘才实
     */
    public static void octahedron(VertexConsumer vc, PoseStack.Pose pose, Vec3 origin,
                                  Vec3 axis, Vec3 equatorA, Vec3 equatorB,
                                  float radiusA, float radiusB, float halfLength,
                                  float spinRadians, Vec3 eye,
                                  int rgb, float alpha, float fresnelPow) {
        if (axis.lengthSqr() < 1.0E-8
                || equatorA.lengthSqr() < 1.0E-8
                || equatorB.lengthSqr() < 1.0E-8) {
            return;
        }
        Vec3 ax = axis.normalize();
        Vec3 ea = equatorA.normalize();
        Vec3 eb = equatorB.normalize();

        Vec3 tipA = origin.add(ax.scale(halfLength));
        Vec3 tipB = origin.subtract(ax.scale(halfLength));

        // 4 个赤道点（椭圆上的四个极值点）
        Vec3[] eq = new Vec3[4];
        double[] ang = {spinRadians, spinRadians + Math.PI / 2.0,
                spinRadians + Math.PI, spinRadians + Math.PI * 1.5};
        for (int i = 0; i < 4; i++) {
            double c = Math.cos(ang[i]);
            double s = Math.sin(ang[i]);
            eq[i] = origin.add(ea.scale(c * radiusA)).add(eb.scale(s * radiusB));
        }

        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;

        // 8 个三角面：上四 + 下四
        for (int i = 0; i < 4; i++) {
            Vec3 e0 = eq[i];
            Vec3 e1 = eq[(i + 1) % 4];
            face(vc, pose, origin, eye, tipA, e0, e1, r, g, b, alpha, fresnelPow);
            face(vc, pose, origin, eye, tipB, e1, e0, r, g, b, alpha, fresnelPow);
        }
    }

    /**
     * <b>内凹锥（凹四棱锥 / 漏斗形尖头）</b> —— 用来表现"立体的月牙剑气"。
     * <p>
     * 构造思路：拿一个八面体，把<b>后尖推向前尖</b>，直到背面凹进去，就变成一个
     * <b>尖头朝前、背面内凹的锥体</b>。它有两条其它做法都没有的性质：
     * <ul>
     *   <li><b>从任何角度看都有体积</b>（它是个闭合的锥，不是一张面）；</li>
     *   <li><b>侧面轮廓是月牙弧</b>（因为背面凹、正面尖，边缘自然形成弯月）。</li>
     * </ul>
     * 这就是"平面月牙"和"实心宝石"之外的第三条路。
     * <p>
     * 几何要素：
     * <ul>
     *   <li><b>尖</b>：沿 {@code axis} 在环的前方 {@code length} 处；</li>
     *   <li><b>环</b>：{@code ringPoints} 个点，在垂直于 {@code axis} 的平面里铺成椭圆
     *       （{@code radiusUp} × {@code radiusSide}）—— 两个半径拉开就是"刀锋截面"；</li>
     *   <li><b>环的内凹</b>：{@code rimBow} 把环在"上下极点"处沿轴向<b>往后压</b>，
     *       于是环本身是弯的 → 侧面看就是月牙弧；</li>
     *   <li><b>背心</b>：沿轴向<b>往前</b>推 {@code backDepth}，构成凹进去的背面。</li>
     * </ul>
     * 面数 = {@code ringPoints × 2}（外锥面 + 内凹背面）。
     *
     * @param axis        尖的朝向（通常是飞行方向）
     * @param upAxis      环的"长"轴（竖直，让底面与地面垂直）
     * @param sideAxis    环的"宽"轴（水平侧向）
     * @param radiusUp    环在 {@code upAxis} 方向的半径
     * @param radiusSide  环在 {@code sideAxis} 方向的半径
     * @param length      尖相对环平面的前伸距离
     * @param backDepth   背心相对环平面前推的距离（&gt;0 才是内凹）
     * @param rimBow      环在上下极点处往后压的量（制造月牙弧）
     * @param ringPoints  环上点数：4 = 内凹四棱锥；8~12 = 更圆的漏斗
     */
    public static void concaveCone(VertexConsumer vc, PoseStack.Pose pose, Vec3 origin,
                                   Vec3 axis, Vec3 upAxis, Vec3 sideAxis,
                                   float radiusUp, float radiusSide,
                                   float length, float backDepth, float rimBow,
                                   int ringPoints,
                                   Vec3 eye,
                                   int rgbFront, int rgbBack,
                                   float alphaFront, float alphaBack,
                                   float fresnelPow) {
        if (axis.lengthSqr() < 1.0E-8 || upAxis.lengthSqr() < 1.0E-8
                || sideAxis.lengthSqr() < 1.0E-8) {
            return;
        }
        Vec3 ax = axis.normalize();
        Vec3 up = upAxis.normalize();
        Vec3 side = sideAxis.normalize();

        int n = Math.max(3, ringPoints);
        Vec3 apex = origin.add(ax.scale(length));
        // 背心往前推 → 背面凹进去
        Vec3 backCenter = origin.add(ax.scale(backDepth));

        Vec3[] ring = new Vec3[n];
        for (int i = 0; i < n; i++) {
            double th = i * 2.0 * Math.PI / n;
            double c = Math.cos(th);
            double s = Math.sin(th);
            // 上下极点（|cos| 大）处往后压 → 环是弯的，侧面看就是月牙弧
            double pushBack = rimBow * Math.abs(c);
            ring[i] = origin.add(up.scale(c * radiusUp))
                    .add(side.scale(s * radiusSide))
                    .subtract(ax.scale(pushBack));
        }

        // 轴向渐变：t=0 在环平面（后），t=1 在尖（前）。
        // 尖白、环绿 —— 这就是"前面白色刃口、背后荧光绿"的做法，靠顶点色实现，不用额外几何。
        float span = Math.max(0.01F, length);
        for (int i = 0; i < n; i++) {
            Vec3 cur = ring[i];
            Vec3 nxt = ring[(i + 1) % n];
            // 外锥面：尖 → 环
            faceGradient(vc, pose, origin, eye, apex, cur, nxt, ax, span,
                    rgbFront, rgbBack, alphaFront, alphaBack, fresnelPow);
            // 内凹背面：背心 → 环（绕序反过来，让法线朝内）
            faceGradient(vc, pose, origin, eye, backCenter, nxt, cur, ax, span,
                    rgbFront, rgbBack, alphaFront, alphaBack, fresnelPow);
        }
    }

    /**
     * 画一个三角面。
     * <p>
     * <b>两个调制</b>，分别负责两件事：
     * <ul>
     *   <li>{@code alphaMul} —— fresnel，边缘更实（轮廓清晰）；</li>
     *   <li>{@code colorMul} —— <b>正对镜头更亮</b>。这才是"体积感"的来源：
     *       一个圆柱如果通体同色，在眼里就是一条平带；必须有表面朝向带来的明暗渐变，
     *       才会被读成有厚度的立体。</li>
     * </ul>
     * 之前的版本只有 alphaMul，所以无论管子做多粗，看上去都是扁的。
     */
    private static void faceGradient(VertexConsumer vc, PoseStack.Pose pose, Vec3 origin, Vec3 eye,
                                     Vec3 a, Vec3 b, Vec3 c,
                                     Vec3 axis, float span,
                                     int rgbFront, int rgbBack,
                                     float alphaFront, float alphaBack,
                                     float fresnelPow) {
        Vec3 n = b.subtract(a).cross(c.subtract(a));
        double alphaMul = 1.0;
        double colorMul = 1.0;
        if (n.lengthSqr() > 1.0E-12) {
            n = n.normalize();
            Vec3 centroid = a.add(b).add(c).scale(1.0 / 3.0);
            Vec3 toEye = eye.subtract(centroid);
            if (toEye.lengthSqr() > 1.0E-8) {
                double facing = Math.abs(n.dot(toEye.normalize()));
                alphaMul = 0.25 + 0.75 * Math.pow(1.0 - facing, fresnelPow);
                colorMul = 0.45 + 0.55 * facing;
            }
        }
        vertexGradient(vc, pose, origin, a, axis, span, rgbFront, rgbBack,
                alphaFront, alphaBack, (float) alphaMul, (float) colorMul);
        vertexGradient(vc, pose, origin, b, axis, span, rgbFront, rgbBack,
                alphaFront, alphaBack, (float) alphaMul, (float) colorMul);
        vertexGradient(vc, pose, origin, c, axis, span, rgbFront, rgbBack,
                alphaFront, alphaBack, (float) alphaMul, (float) colorMul);
    }

    private static void vertexGradient(VertexConsumer vc, PoseStack.Pose pose, Vec3 origin,
                                       Vec3 world, Vec3 axis, float span,
                                       int rgbFront, int rgbBack,
                                       float alphaFront, float alphaBack,
                                       float alphaMul, float colorMul) {
        // 该点在轴上的位置：0 = 环平面，1 = 尖
        double axial = world.subtract(origin).dot(axis) / span;
        float t = (float) net.minecraft.util.Mth.clamp(axial, 0.0, 1.0);

        int fr = (rgbFront >> 16) & 0xFF;
        int fg = (rgbFront >> 8) & 0xFF;
        int fb = rgbFront & 0xFF;
        int br = (rgbBack >> 16) & 0xFF;
        int bg = (rgbBack >> 8) & 0xFF;
        int bb = rgbBack & 0xFF;

        float shade = (float) net.minecraft.util.Mth.clamp(colorMul, 0.0F, 1.0F);
        int r = (int) ((br + (fr - br) * t) * shade);
        int g = (int) ((bg + (fg - bg) * t) * shade);
        int b = (int) ((bb + (fb - bb) * t) * shade);
        float alpha = (alphaBack + (alphaFront - alphaBack) * t) * alphaMul;
        int a = (int) (net.minecraft.util.Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F);

        vertex(vc, pose, origin, world, r, g, b, a);
    }

    /**
     * <b>沿弯曲路径扫出的管状月牙</b> —— 真正立体的月牙剑气。
     * <p>
     * 与 {@link #concaveCone} 的区别：那个是"一个尖 + 直边"，天生是<b>箭头</b>
     * （其实对应的是 Zero 系列"下 + 攻击"那个技能）。月牙的特征是
     * <b>前缘是凸弧、两个尖在上下两端</b>，所以不能用单尖锥。
     * <p>
     * 构造：
     * <ol>
     *   <li><b>路径</b>沿 {@code spanAxis}（竖直），并向 {@code bowAxis}（前方）按
     *       {@code bow·(1-u²)} 鼓出 —— u=±1 时鼓出量为 0，所以两个尖落在同一条竖直线上；</li>
     *   <li><b>截面</b>是椭圆：沿 {@code bowAxis} 给 {@code thickAxis}（刀身厚度）、
     *       沿 {@code thinAxis} 给 {@code thinRadius}（刀背的薄）；</li>
     *   <li><b>半径</b>按 {@code √(1-u²)} 收窄 → 两端收成尖，中段最厚。</li>
     * </ol>
     * 结果：侧面看是一道<b>月牙弧</b>，任何角度都有体积，截面是刀锋而不是圆柱。
     */
    public static void crescentTube(VertexConsumer vc, PoseStack.Pose pose, Vec3 origin,
                                    Vec3 spanAxis, Vec3 bowAxis, Vec3 thinAxis,
                                    float span, float bow,
                                    float thickAxis, float thinRadius,
                                    int alongSegs, int aroundSegs,
                                    Vec3 eye, int rgbFront, int rgbBack,
                                    float alphaFront, float alphaBack, float fresnelPow) {
        if (spanAxis.lengthSqr() < 1.0E-8 || bowAxis.lengthSqr() < 1.0E-8
                || thinAxis.lengthSqr() < 1.0E-8) {
            return;
        }
        Vec3 up = spanAxis.normalize();
        Vec3 fw = bowAxis.normalize();
        Vec3 th = thinAxis.normalize();

        int along = Math.max(2, alongSegs);
        int around = Math.max(3, aroundSegs);

        Vec3[] centers = new Vec3[along + 1];
        Vec3[] normals = new Vec3[along + 1];
        float[] radii = new float[along + 1];
        for (int i = 0; i <= along; i++) {
            double u = -1.0 + 2.0 * i / along;
            double bulge = bow * (1.0 - u * u);
            centers[i] = origin.add(up.scale(span * u)).add(fw.scale(bulge));
            Vec3 tangent = up.scale(span).subtract(fw.scale(2.0 * bow * u));
            if (tangent.lengthSqr() < 1.0E-8) {
                tangent = up;
            }
            tangent = tangent.normalize();
            // 面内法线：与切线垂直、且落在 (up, fw) 平面内
            Vec3 nrm = fw.scale(span).add(up.scale(2.0 * bow * u));
            if (nrm.lengthSqr() < 1.0E-8) {
                nrm = fw;
            }
            normals[i] = nrm.normalize();
            radii[i] = (float) Math.sqrt(Math.max(0.0, 1.0 - u * u));
        }

        for (int i = 0; i < along; i++) {
            for (int j = 0; j < around; j++) {
                double t0 = j * 2.0 * Math.PI / around;
                double t1 = (j + 1) * 2.0 * Math.PI / around;

                Vec3 a0 = ringPoint(centers[i], normals[i], th, radii[i], thickAxis, thinRadius, t0);
                Vec3 a1 = ringPoint(centers[i], normals[i], th, radii[i], thickAxis, thinRadius, t1);
                Vec3 b0 = ringPoint(centers[i + 1], normals[i + 1], th, radii[i + 1],
                        thickAxis, thinRadius, t0);
                Vec3 b1 = ringPoint(centers[i + 1], normals[i + 1], th, radii[i + 1],
                        thickAxis, thinRadius, t1);

                faceGradient(vc, pose, origin, eye, a0, a1, b1, fw, Math.max(0.01F, bow),
                        rgbFront, rgbBack, alphaFront, alphaBack, fresnelPow);
                faceGradient(vc, pose, origin, eye, a0, b1, b0, fw, Math.max(0.01F, bow),
                        rgbFront, rgbBack, alphaFront, alphaBack, fresnelPow);
            }
        }
    }

    /**
     * <b>锥形管（拖尾）</b> —— 和 {@link #crescentTube} 同一套截面与明暗，所以能和月牙无缝接上。
     * <p>
     * <b>为什么拖尾不能用扁平 ribbon</b>：月牙是管状体（有明暗、有体积），
     * 而 ribbon 是零厚度的 billboard。两者放在一起就是"一个立体物体后面拖着一张纸"，
     * 无论怎么调位置和透明度都对不上 —— 差别在<span>材质和几何类型</b>，不在接缝。
     * <p>
     * 截面沿 {@code axis} 反方向延伸，半径按 {@code (1-t)^0.8} 收窄到 0。
     * 起点截面应该和月牙的最大截面同尺寸，接缝才会消失。
     *
     * @param origin      起点（应落在月牙的宽度最大处）
     * @param axis        飞行方向；管体沿其<b>反方向</b>延伸
     * @param upAxis      截面的"长"轴（竖直）
     * @param sideAxis    截面的"薄"轴（水平侧向）
     * @param radiusUp    起点处截面的竖直半半径
     * @param radiusSide  起点处截面的侧向半半径
     * @param length      管体长度
     * @param rgbNear     起点颜色（靠月牙）
     * @param rgbFar      远端颜色
     */
    public static void taperTube(VertexConsumer vc, PoseStack.Pose pose, Vec3 origin,
                                 Vec3 axis, Vec3 upAxis, Vec3 sideAxis,
                                 float radiusUp, float radiusSide, float length,
                                 int alongSegs, int aroundSegs,
                                 Vec3 eye, int rgbNear, int rgbFar,
                                 float alphaNear, float alphaFar, float fresnelPow) {
        if (axis.lengthSqr() < 1.0E-8 || upAxis.lengthSqr() < 1.0E-8
                || sideAxis.lengthSqr() < 1.0E-8) {
            return;
        }
        Vec3 ax = axis.normalize();
        Vec3 up = upAxis.normalize();
        Vec3 side = sideAxis.normalize();
        // 往后指 = 颜色渐变用的轴（t: 0 在起点，1 在远端）
        Vec3 back = ax.scale(-1.0);

        int along = Math.max(2, alongSegs);
        int around = Math.max(3, aroundSegs);

        Vec3[] centers = new Vec3[along + 1];
        float[] radii = new float[along + 1];
        for (int i = 0; i <= along; i++) {
            double t = (double) i / along;
            centers[i] = origin.subtract(ax.scale(length * t));
            radii[i] = (float) Math.pow(Math.max(0.0, 1.0 - t), 0.8);
        }

        for (int i = 0; i < along; i++) {
            for (int j = 0; j < around; j++) {
                double t0 = j * 2.0 * Math.PI / around;
                double t1 = (j + 1) * 2.0 * Math.PI / around;

                Vec3 a0 = ringPoint(centers[i], up, side, radii[i], radiusUp, radiusSide, t0);
                Vec3 a1 = ringPoint(centers[i], up, side, radii[i], radiusUp, radiusSide, t1);
                Vec3 b0 = ringPoint(centers[i + 1], up, side, radii[i + 1], radiusUp, radiusSide, t0);
                Vec3 b1 = ringPoint(centers[i + 1], up, side, radii[i + 1], radiusUp, radiusSide, t1);

                // 注意 rgbFront/rgbBack 的顺序：t=0 在起点，所以"Front"位置传的是远端颜色
                faceGradient(vc, pose, origin, eye, a0, a1, b1, back, Math.max(0.01F, length),
                        rgbFar, rgbNear, alphaFar, alphaNear, fresnelPow);
                faceGradient(vc, pose, origin, eye, a0, b1, b0, back, Math.max(0.01F, length),
                        rgbFar, rgbNear, alphaFar, alphaNear, fresnelPow);
            }
        }
    }

    private static Vec3 ringPoint(Vec3 center, Vec3 normal, Vec3 thinAxis, float radius,
                                  float thickAxis, float thinRadius, double theta) {
        double c = Math.cos(theta);
        double s = Math.sin(theta);
        return center
                .add(normal.scale(c * radius * thickAxis))
                .add(thinAxis.scale(s * radius * thinRadius));
    }

    /** 画一个三角面，并按 fresnel 调整该面的不透明度。 */
    private static void face(VertexConsumer vc, PoseStack.Pose pose, Vec3 origin, Vec3 eye,
                             Vec3 a, Vec3 b, Vec3 c,
                             int r, int g, int bl, float alpha, float fresnelPow) {
        Vec3 n = b.subtract(a).cross(c.subtract(a));
        int argb = (int) (net.minecraft.util.Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F);
        if (n.lengthSqr() > 1.0E-12) {
            n = n.normalize();
            Vec3 centroid = a.add(b).add(c).scale(1.0 / 3.0);
            Vec3 toEye = eye.subtract(centroid);
            if (toEye.lengthSqr() > 1.0E-8) {
                double facing = Math.abs(n.dot(toEye.normalize()));
                // facing=1（正对镜头）→ 透明；facing=0（侧面）→ 实
                double fres = Math.pow(1.0 - facing, fresnelPow);
                argb = (int) (net.minecraft.util.Mth.clamp(alpha * (0.25 + 0.75 * fres), 0.0F, 1.0F) * 255.0F);
            }
        }
        vertex(vc, pose, origin, a, r, g, bl, argb);
        vertex(vc, pose, origin, b, r, g, bl, argb);
        vertex(vc, pose, origin, c, r, g, bl, argb);
    }

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
