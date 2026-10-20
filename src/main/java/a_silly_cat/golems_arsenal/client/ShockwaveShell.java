package a_silly_cat.golems_arsenal.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * 球形冲击波：落点处一个**压扁的上半球**（罩子），向外膨胀到冲击半径。
 *
 * <p>参考的是"天基激光砸地"那种冲击波：贴地的亮环 + 低矮的半球形气浪 +
 * 从落点向外/向上的放射状火星，而不是一颗完整的透明球。
 *
 * <p><b>为什么是压扁的</b>：完整球在贴地时上半球高度 = 半径，看起来像"玻璃球"；
 * 参考图的罩子高度只有大约半径的一半，所以这里 y 方向乘 {@link #HEIGHT}。
 *
 * <p><b>为什么只画上半球</b>：下半球在地下，深度测试会挡住，白白浪费顶点；
 * 直接从地平线扫到天顶即可。
 *
 * <p><b>为什么每个 quad 只发 4 个顶点</b>：{@code entityTranslucentEmissive}
 * 的顶点模式是 QUADS，不是 TRIANGLES。之前误发了 6 个顶点（两个三角形），
 * 缓冲区会把不同 quad 的顶点拼错，看起来就是"蓝色线框球"——这张图里的网格感就是它。
 *
 * <p>顶点格式走 {@code RenderType.entityTranslucentEmissive}，和主光束同一条渲染路径，
 * 避免自定义 {@code POSITION_COLOR} RenderType 在 Oculus 下出现块状伪影。
 */
public final class ShockwaveShell {

    /** 经度分段。48 段在近处也够圆。 */
    private static final int LON = 48;
    /** 纬度分段（从地平线到天顶）。 */
    private static final int LAT = 16;
    /** 罩子高度相对半径的比例。0.55 接近参考图的低矮半球。 */
    private static final float HEIGHT = 0.55F;

    private ShockwaveShell() {
    }

    /**
     * @param vc         {@code RenderType.entityTranslucentEmissive} 的顶点消费者
     * @param m          已经平移到冲击点的 pose 矩阵
     * @param camLocal   相机相对冲击点的位置
     * @param radius     当前罩子半径
     * @param alpha      整体透明度（调用方已乘光影倍率）
     * @param brightness 光影倍率，只用于边缘轻微提亮
     * @param ticks      动画时间，用来让罩面有轻微起伏
     */
    public static void draw(VertexConsumer vc, Matrix4f m, Vec3 camLocal, float radius,
                            float alpha, float brightness, float ticks) {
        if (radius <= 0.01F || alpha <= 0.001F) {
            return;
        }
        boolean inside = camLocal.lengthSqr() < (double) radius * radius;

        float cr = ShaderEffects.tintR(1.00F);
        float cg = ShaderEffects.tintG(0.88F);
        float cb = ShaderEffects.tintB(0.62F);

        for (int i = 0; i < LAT; i++) {
            double phi0 = Math.PI * 0.5D * i / LAT;
            double phi1 = Math.PI * 0.5D * (i + 1) / LAT;
            double cp0 = Math.cos(phi0);
            double sp0 = Math.sin(phi0);
            double cp1 = Math.cos(phi1);
            double sp1 = Math.sin(phi1);
            for (int j = 0; j < LON; j++) {
                double th0 = Math.PI * 2.0 * j / LON;
                double th1 = Math.PI * 2.0 * (j + 1) / LON;

                Vec3 p00 = point(radius, cp0, sp0, th0, ticks);
                Vec3 p01 = point(radius, cp0, sp0, th1, ticks);
                Vec3 p11 = point(radius, cp1, sp1, th1, ticks);
                Vec3 p10 = point(radius, cp1, sp1, th0, ticks);

                double phiMid = (phi0 + phi1) * 0.5D;
                double thMid = (th0 + th1) * 0.5D;
                Vec3 normal = ellipsoidNormal(phiMid, thMid);
                Vec3 centerPoint = point(radius, Math.cos(phiMid), Math.sin(phiMid), thMid, ticks);
                if (!inside && normal.dot(camLocal.subtract(centerPoint)) <= 0.0D) {
                    continue;
                }

                // 高度越低越亮（贴地亮环），越靠近天顶越淡；每个 quad 用同一个 alpha，
                // 避免 QUADS 内部按三角形插值出现对角线接缝。
                float base = 1.0F - (float) (phiMid / (Math.PI * 0.5D));
                float a = alpha * (0.10F + 0.90F * (float) Math.pow(base, 1.25D));
                float glow = 1.0F + 0.20F * base * brightness;

                vertex(vc, m, p00, normal, cr * glow, cg * glow, cb * glow, a);
                vertex(vc, m, p01, normal, cr * glow, cg * glow, cb * glow, a);
                vertex(vc, m, p11, normal, cr * glow, cg * glow, cb * glow, a);
                vertex(vc, m, p10, normal, cr * glow, cg * glow, cb * glow, a);
            }
        }
    }

    private static Vec3 point(float radius, double cosPhi, double sinPhi, double theta, float ticks) {
        // 轻微起伏：让罩面不是完美的几何面，读起来更像"被推开的空气"。
        float wobble = 1.0F + 0.020F * (float) Math.sin(theta * 3.0D + sinPhi * 7.0D + ticks * 0.35F);
        float x = (float) (Math.cos(theta) * cosPhi * radius * wobble);
        float y = (float) (sinPhi * radius * HEIGHT * wobble);
        float z = (float) (Math.sin(theta) * cosPhi * radius * wobble);
        return new Vec3(x, y, z);
    }

    private static Vec3 ellipsoidNormal(double phi, double theta) {
        double cp = Math.cos(phi);
        double sp = Math.sin(phi);
        // 椭球面 x²/R² + y²/(R·HEIGHT)² + z²/R² = 1 的梯度
        double x = Math.cos(theta) * cp;
        double z = Math.sin(theta) * cp;
        double y = sp / (HEIGHT * HEIGHT);
        return new Vec3(x, y, z).normalize();
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, Vec3 p, Vec3 normal,
                               float r, float g, float b, float a) {
        vc.vertex(m, (float) p.x, (float) p.y, (float) p.z)
                .color(r, g, b, a)
                .uv(0.5F, 0.5F)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal((float) normal.x, (float) normal.y, (float) normal.z)
                .endVertex();
    }
}
