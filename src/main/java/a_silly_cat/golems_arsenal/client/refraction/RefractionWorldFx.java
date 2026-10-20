package a_silly_cat.golems_arsenal.client.refraction;

import a_silly_cat.golems_arsenal.client.TrailRenderTypes;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * 纯几何两条路线：{@link RefractionFx.Mode#SHELL} 与 {@link RefractionFx.Mode#FLOW}。
 *
 * <p>它们<b>不依赖后处理</b>，所以在 Oculus/光影包里也能工作——这是"如果屏幕空间折射
 * 走不通"时的兜底。视觉目标不是"真的扭背景"，而是让玩家读到三件事：
 * <ol>
 *   <li>这里有一个球形的空间范围（球壳的轮廓）；</li>
 *   <li>这个范围正在从中心撑开（半径缓出）；</li>
 *   <li>能量/空间在向中心收（粒子流的方向）。</li>
 * </ol>
 *
 * <p>顶点色用<b>预乘 alpha</b>：加法混合（ONE/ONE）不会拿 alpha 去乘 RGB，
 * 所以想淡出必须自己把 RGB 乘上 alpha，否则 alpha=0 的顶点仍然会加光。
 * （这是 {@code TrailRenderTypes.ADDITIVE_TRIANGLES} 的已知性质。）
 */
public final class RefractionWorldFx {

    private static final int SHELL_LAT = 18;
    private static final int SHELL_LON = 36;
    private static final int FLOW_STREAKS = 72;

    private RefractionWorldFx() {
    }

    public static void render(RenderLevelStageEvent event) {
        RefractionFx.Mode mode = RefractionFx.mode();
        if (mode == null || mode == RefractionFx.Mode.WARP || mode == RefractionFx.Mode.HEAT) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        float partial = event.getPartialTick();
        float p = RefractionFx.progress(partial);
        float env = RefractionFx.envelope(partial);
        float radius = RefractionFx.radiusNow(partial);
        if (env <= 0.001F || radius <= 0.01F) {
            return;
        }

        Vec3 center = RefractionFx.center();
        Vec3 camWorld = event.getCamera().getPosition();
        Vec3 camLocal = camWorld.subtract(center);

        // AFTER_PARTICLES 的 poseStack 就是世界渲染用的 modelview（相机旋转），
        // 平移分量不在里面，所以只要减掉相机世界坐标，再平移到球心。
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(center.x - camWorld.x, center.y - camWorld.y, center.z - camWorld.z);
        Matrix4f m = pose.last().pose();

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(TrailRenderTypes.ADDITIVE_TRIANGLES);

        if (mode == RefractionFx.Mode.SHELL || mode == RefractionFx.Mode.ALL) {
            // 外层冷蓝：主轮廓
            drawShell(vc, m, camLocal, radius, env * 0.75F, 0.42F, 0.80F, 1.00F);
            // 内层紫：让球有"厚度"，不是一张贴纸
            drawShell(vc, m, camLocal, radius * 0.86F, env * 0.30F, 0.68F, 0.45F, 1.00F);
        }
        if (mode == RefractionFx.Mode.FLOW || mode == RefractionFx.Mode.ALL) {
            drawFlow(vc, m, camLocal, radius, p, env);
        }
        buffers.endBatch();
        pose.popPose();
    }

    // ------------------------------------------------------------------ 球壳

    /**
     * 一层球壳：正面朝向相机的四边形才画，alpha 用菲涅耳边缘（正对视线处透明、轮廓处亮）。
     *
     * <p>手动剔除背面是必要的：{@code TrailRenderTypes} 是 {@code NO_CULL}，
     * 不剔除的话背面也会加一遍光，球会变成"中心也亮"的一团雾。
     */
    private static void drawShell(VertexConsumer vc, Matrix4f m, Vec3 camLocal,
                                  float radius, float env, float cr, float cg, float cb) {
        for (int i = 0; i < SHELL_LAT; i++) {
            double phi0 = -Math.PI / 2.0 + Math.PI * i / SHELL_LAT;
            double phi1 = -Math.PI / 2.0 + Math.PI * (i + 1) / SHELL_LAT;
            double cp0 = Math.cos(phi0);
            double sp0 = Math.sin(phi0);
            double cp1 = Math.cos(phi1);
            double sp1 = Math.sin(phi1);
            if (Math.abs(cp0) < 1.0E-4D || Math.abs(cp1) < 1.0E-4D) {
                continue;
            }
            for (int j = 0; j < SHELL_LON; j++) {
                double th0 = Math.PI * 2.0 * j / SHELL_LON;
                double th1 = Math.PI * 2.0 * (j + 1) / SHELL_LON;

                Vec3 p00 = sphere(radius, cp0, sp0, th0);
                Vec3 p01 = sphere(radius, cp0, sp0, th1);
                Vec3 p11 = sphere(radius, cp1, sp1, th1);
                Vec3 p10 = sphere(radius, cp1, sp1, th0);

                // 四边形的粗略法线：四条边中点归一化
                Vec3 n = p00.add(p01).add(p11).add(p10).scale(0.25D).normalize();
                if (n.dot(camLocal.subtract(p00)) <= 0.0D) {
                    continue; // 背面
                }

                shellVertex(vc, m, camLocal, p00, radius, env, cr, cg, cb);
                shellVertex(vc, m, camLocal, p01, radius, env, cr, cg, cb);
                shellVertex(vc, m, camLocal, p11, radius, env, cr, cg, cb);

                shellVertex(vc, m, camLocal, p00, radius, env, cr, cg, cb);
                shellVertex(vc, m, camLocal, p11, radius, env, cr, cg, cb);
                shellVertex(vc, m, camLocal, p10, radius, env, cr, cg, cb);
            }
        }
    }

    private static Vec3 sphere(float radius, double cosPhi, double sinPhi, double theta) {
        return new Vec3(Math.cos(theta) * cosPhi * radius,
                sinPhi * radius,
                Math.sin(theta) * cosPhi * radius);
    }

    private static void shellVertex(VertexConsumer vc, Matrix4f m, Vec3 camLocal, Vec3 p,
                                    float radius, float env, float cr, float cg, float cb) {
        Vec3 normal = p.scale(1.0D / radius);
        Vec3 view = camLocal.subtract(p).normalize();
        float facing = (float) Math.abs(normal.dot(view));
        // 菲涅耳：正对视线 = 0，轮廓 = 1。指数越大，亮边越窄。
        float rim = (float) Math.pow(1.0F - facing, 2.3D);
        float alpha = env * (0.03F + 0.62F * rim);
        // 预乘 alpha（加法混合不会自己乘）
        vc.vertex(m, (float) p.x, (float) p.y, (float) p.z)
                .color(cr * alpha, cg * alpha, cb * alpha, 1.0F)
                .endVertex();
    }

    // ------------------------------------------------------------------ 向内收束的光流

    /**
     * 从球外 1.18R 螺旋收到 0.16R 的短光条。
     *
     * <p>参数全部由索引哈希决定：客户端纯函数，不需要同步，也不会出现"两个玩家看到不同粒子"。
     * 颜色取参考图那种低饱和的糖果色（青 / 紫 / 粉 / 淡黄），在暗背景上很跳。
     */
    private static void drawFlow(VertexConsumer vc, Matrix4f m, Vec3 camLocal,
                                 float radius, float p, float env) {
        for (int i = 0; i < FLOW_STREAKS; i++) {
            long h = (i * 0x9E3779B97F4A7C15L) ^ 0x5DEECE66DL;
            float r1 = hashUnit(h);
            float r2 = hashUnit(h * 6364136223846793005L + 1442695040888963407L);
            float r3 = hashUnit(h * 22695477L + 1L);
            float r4 = hashUnit(h * 1103515245L + 12345L);

            float born = r1 * 0.20F;
            float t = (p - born) / (1.0F - born);
            if (t <= 0.0F || t >= 1.0F) {
                continue;
            }
            float alpha = env * (float) Math.sin(Math.PI * t);
            if (alpha <= 0.01F) {
                continue;
            }

            float ease = easeOutCubic(t);
            Vec3 cur = flowPoint(radius, r2, r3, ease);
            float tPrev = Math.max(0.0F, t - 0.085F);
            Vec3 prev = flowPoint(radius, r2, r3, easeOutCubic(tPrev));

            float cr;
            float cg;
            float cb;
            int color = (int) (r4 * 4.0F) & 3;
            switch (color) {
                case 0 -> {
                    cr = 0.55F;
                    cg = 0.95F;
                    cb = 1.00F;
                }
                case 1 -> {
                    cr = 0.78F;
                    cg = 0.60F;
                    cb = 1.00F;
                }
                case 2 -> {
                    cr = 1.00F;
                    cg = 0.62F;
                    cb = 0.92F;
                }
                default -> {
                    cr = 1.00F;
                    cg = 0.94F;
                    cb = 0.62F;
                }
            }
            streak(vc, m, camLocal, prev, cur, radius * 0.020F, cr, cg, cb, alpha * 0.85F);
        }
    }

    /** 光流在进度 ease（0..1）时的位置：半径缩小、绕中心转、向赤道面收。 */
    private static Vec3 flowPoint(float radius, float angleSeed, float heightSeed, float ease) {
        float rr = radius * (1.18F - 1.02F * ease);
        float angle = angleSeed * (float) (Math.PI * 2.0) + (1.0F - ease) * 2.1F;
        float y = (heightSeed * 2.0F - 1.0F) * radius * 0.82F * (1.0F - ease * 0.80F);
        return new Vec3(Math.cos(angle) * rr, y, Math.sin(angle) * rr);
    }

    /** 一小段朝向相机的光条（两个三角形）。 */
    private static void streak(VertexConsumer vc, Matrix4f m, Vec3 camLocal,
                               Vec3 from, Vec3 to, float width,
                               float cr, float cg, float cb, float alpha) {
        Vec3 seg = to.subtract(from);
        if (seg.lengthSqr() < 1.0E-7D) {
            return;
        }
        Vec3 view = camLocal.subtract(from);
        if (view.lengthSqr() < 1.0E-7D) {
            return;
        }
        Vec3 side = seg.cross(view).normalize().scale(width);
        if (side.lengthSqr() < 1.0E-9D) {
            return;
        }
        Vec3 a = from.subtract(side);
        Vec3 b = from.add(side);
        Vec3 c = to.add(side);
        Vec3 d = to.subtract(side);

        // 预乘 alpha：加法混合下 RGB 才是真正被加进去的量
        float r = cr * alpha;
        float g = cg * alpha;
        float bl = cb * alpha;
        vertex(vc, m, a, r, g, bl);
        vertex(vc, m, b, r, g, bl);
        vertex(vc, m, c, r, g, bl);

        vertex(vc, m, a, r, g, bl);
        vertex(vc, m, c, r, g, bl);
        vertex(vc, m, d, r, g, bl);
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, Vec3 p, float r, float g, float b) {
        vc.vertex(m, (float) p.x, (float) p.y, (float) p.z)
                .color(r, g, b, 1.0F)
                .endVertex();
    }

    // ------------------------------------------------------------------ 工具

    private static float easeOutCubic(float t) {
        float inv = 1.0F - t;
        return 1.0F - inv * inv * inv;
    }

    private static float hashUnit(long v) {
        v ^= v >>> 33;
        v *= 0xFF51AFD7ED558CCDL;
        v ^= v >>> 33;
        v *= 0xC4CEB9FE1A85EC53L;
        v ^= v >>> 33;
        return (v >>> 11) / (float) (1L << 53);
    }
}
