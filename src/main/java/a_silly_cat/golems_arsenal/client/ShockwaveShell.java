package a_silly_cat.golems_arsenal.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * 球形冲击波壳：落点处一层向外膨胀的**球面**，边缘亮、正对视线处几乎透明。
 *
 * <p>它替代原来的"白热球"：
 * <ul>
 *   <li>白热球是实心加法球，正反两面叠加，在 MakeUp 的 bloom 里必然烧成白罩；</li>
 *   <li>球壳只画朝向相机的一面，用半透明混合 + 菲涅耳边缘，亮度可以压到 bloom 阈值下；</li>
 *   <li>壳的膨胀终点正好是 {@code OrbitalImpactEntity.radius()}，所以"看到的边界 = 伤害范围"。</li>
 * </ul>
 *
 * <p>画在实体渲染阶段（不是后处理），所以不依赖 Oculus/光影的后处理管线；
 * 带深度测试，球心贴地时地面以下的部分会被地形挡住，剩下的正好是"扣在地上的罩子"。
 * 顶点格式走 {@code RenderType.entityTranslucentEmissive}，和主光束同一条渲染路径，
 * 避免自定义 {@code POSITION_COLOR} RenderType 在 Oculus 下被当成未知几何而出现块状伪影。
 */
public final class ShockwaveShell {

    /** 经纬网格。20×40 在近处也够圆；再高只是浪费顶点。 */
    private static final int LAT = 20;
    private static final int LON = 40;

    private ShockwaveShell() {
    }

    /**
     * @param vc        {@code RenderType.entityTranslucentEmissive} 的顶点消费者
     * @param m         已经平移到球心的 pose 矩阵
     * @param camLocal  相机相对球心的位置
     * @param radius    当前壳半径
     * @param alpha     整体透明度（调用方已乘光影倍率）
     * @param brightness 光影倍率，仅用于边缘轻微提亮
     * @param ticks     动画时间，用来让壳面有轻微起伏
     */
    public static void draw(VertexConsumer vc, Matrix4f m, Vec3 camLocal,
                            float radius, float alpha, float brightness, float ticks) {
        if (radius <= 0.01F || alpha <= 0.001F) {
            return;
        }
        boolean inside = camLocal.lengthSqr() < (double) radius * radius;

        float cr = ShaderEffects.tintR(1.00F);
        float cg = ShaderEffects.tintG(0.86F);
        float cb = ShaderEffects.tintB(0.52F);

        for (int i = 0; i < LAT; i++) {
            double phi0 = -Math.PI / 2.0 + Math.PI * i / LAT;
            double phi1 = -Math.PI / 2.0 + Math.PI * (i + 1) / LAT;
            double cp0 = Math.cos(phi0);
            double sp0 = Math.sin(phi0);
            double cp1 = Math.cos(phi1);
            double sp1 = Math.sin(phi1);
            if (Math.abs(cp0) < 1.0E-4D || Math.abs(cp1) < 1.0E-4D) {
                continue;
            }
            for (int j = 0; j < LON; j++) {
                double th0 = Math.PI * 2.0 * j / LON;
                double th1 = Math.PI * 2.0 * (j + 1) / LON;

                Vec3 p00 = point(radius, cp0, sp0, th0, ticks);
                Vec3 p01 = point(radius, cp0, sp0, th1, ticks);
                Vec3 p11 = point(radius, cp1, sp1, th1, ticks);
                Vec3 p10 = point(radius, cp1, sp1, th0, ticks);

                Vec3 normal = p00.add(p01).add(p11).add(p10).scale(0.25D).normalize();
                if (!inside && normal.dot(camLocal.subtract(p00)) <= 0.0D) {
                    continue;
                }

                vertex(vc, m, camLocal, p00, radius, alpha, brightness, cr, cg, cb);
                vertex(vc, m, camLocal, p01, radius, alpha, brightness, cr, cg, cb);
                vertex(vc, m, camLocal, p11, radius, alpha, brightness, cr, cg, cb);

                vertex(vc, m, camLocal, p00, radius, alpha, brightness, cr, cg, cb);
                vertex(vc, m, camLocal, p11, radius, alpha, brightness, cr, cg, cb);
                vertex(vc, m, camLocal, p10, radius, alpha, brightness, cr, cg, cb);
            }
        }
    }

    private static Vec3 point(float radius, double cosPhi, double sinPhi, double theta, float ticks) {
        // 轻微起伏：让壳面不是完美的几何球，读起来更像"被推开的空气"。
        float wobble = 1.0F + 0.025F * (float) Math.sin(theta * 3.0D + cosPhi * 5.0D + ticks * 0.35F);
        float r = radius * wobble;
        return new Vec3(Math.cos(theta) * cosPhi * r,
                sinPhi * r,
                Math.sin(theta) * cosPhi * r);
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, Vec3 camLocal, Vec3 p,
                               float radius, float alpha, float brightness,
                               float cr, float cg, float cb) {
        Vec3 normal = p.scale(1.0D / radius);
        Vec3 view = camLocal.subtract(p);
        float facing = view.lengthSqr() < 1.0E-8D
                ? 1.0F
                : (float) Math.abs(normal.dot(view.normalize()));
        // 菲涅耳：正对视线透明，轮廓亮。
        float rim = (float) Math.pow(1.0F - facing, 2.2D);
        float a = alpha * (0.035F + 0.965F * rim);
        // 边缘略提亮，但提亮幅度受光影倍率限制，避免又冲进 bloom。
        float glow = 1.0F + 0.35F * rim * brightness;
        vc.vertex(m, (float) p.x, (float) p.y, (float) p.z)
                .color(cr * glow, cg * glow, cb * glow, a)
                .uv(0.5F, 0.5F)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal((float) normal.x, (float) normal.y, (float) normal.z)
                .endVertex();
    }
}
