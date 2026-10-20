package a_silly_cat.golems_arsenal.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * 冲击波周围的**空气波**：一个几乎透明、只有轮廓略亮的球壳。
 *
 * <p>它不是"光罩"。参考图里真正读得出来的冲击波是：
 * <ul>
 *   <li>贴地扩散波（{@code OrbitalImpactRenderer} 里的 {@code shockWall} + 平面环）；</li>
 *   <li>向外/向上的火花爆炸；</li>
 *   <li>以及一圈很淡、只在边缘才看得见的空气扰动——就是这个壳。</li>
 * </ul>
 *
 * <p>所以这里的 alpha 压得非常低（内部约 0.01、边缘约 0.1），
 * 颜色也保持中性，不再是一个绿色/蓝色的实心罩子。真正"亮"的部分交给地面波和火花。
 *
 * <p><b>顶点模式</b>：{@code entityTranslucentEmissive} 是 QUADS，
 * 每个四边形必须恰好 4 个顶点；上一版 6 个顶点把缓冲区拼错，才出现"线框球"。
 */
public final class ShockwaveShell {

    /** 经度分段。 */
    private static final int LON = 40;
    /** 纬度分段。 */
    private static final int LAT = 18;

    private ShockwaveShell() {
    }

    /**
     * @param vc         {@code RenderType.entityTranslucentEmissive} 的顶点消费者
     * @param m          已经平移到冲击点的 pose 矩阵
     * @param camLocal   相机相对冲击点的位置
     * @param radius     当前空气波半径
     * @param alpha      整体透明度（调用方已乘光影倍率）
     * @param brightness 光影倍率，只用于边缘轻微提亮
     * @param ticks      动画时间，用来让壳面有轻微起伏
     */
    public static void draw(VertexConsumer vc, Matrix4f m, Vec3 camLocal, float radius,
                            float alpha, float brightness, float ticks) {
        if (radius <= 0.01F || alpha <= 0.001F) {
            return;
        }
        boolean inside = camLocal.lengthSqr() < (double) radius * radius;

        // 暖白：空气波不抢地面波的颜色，也不按参考图那种蓝去调（蓝色是背景/阵营叠加）。
        float cr = 0.95F;
        float cg = 0.90F;
        float cb = 0.82F;

        for (int i = 0; i < LAT; i++) {
            double phi0 = -Math.PI / 2.0 + Math.PI * i / LAT;
            double phi1 = -Math.PI / 2.0 + Math.PI * (i + 1) / LAT;
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
                Vec3 normal = new Vec3(Math.cos(thMid) * Math.cos(phiMid),
                        Math.sin(phiMid),
                        Math.sin(thMid) * Math.cos(phiMid)).normalize();
                Vec3 centerPoint = point(radius, Math.cos(phiMid), Math.sin(phiMid), thMid, ticks);
                if (!inside && normal.dot(camLocal.subtract(centerPoint)) <= 0.0D) {
                    continue;
                }

                // 只在轮廓亮：正对视线几乎完全透明。
                Vec3 view = camLocal.subtract(centerPoint);
                float facing = view.lengthSqr() < 1.0E-8D
                        ? 1.0F
                        : (float) Math.abs(normal.dot(view.normalize()));
                float rim = (float) Math.pow(1.0F - facing, 2.6D);
                float a = alpha * (0.012F + 0.135F * rim);
                float glow = 1.0F + 0.25F * rim * brightness;

                vertex(vc, m, p00, normal, cr * glow, cg * glow, cb * glow, a);
                vertex(vc, m, p01, normal, cr * glow, cg * glow, cb * glow, a);
                vertex(vc, m, p11, normal, cr * glow, cg * glow, cb * glow, a);
                vertex(vc, m, p10, normal, cr * glow, cg * glow, cb * glow, a);
            }
        }
    }

    private static Vec3 point(float radius, double cosPhi, double sinPhi, double theta, float ticks) {
        // 极轻微的起伏，让轮廓不是完美的圆。
        float wobble = 1.0F + 0.015F * (float) Math.sin(theta * 3.0D + sinPhi * 7.0D + ticks * 0.35F);
        float x = (float) (Math.cos(theta) * cosPhi * radius * wobble);
        float y = (float) (sinPhi * radius * wobble);
        float z = (float) (Math.sin(theta) * cosPhi * radius * wobble);
        return new Vec3(x, y, z);
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
