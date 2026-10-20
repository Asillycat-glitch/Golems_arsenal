package a_silly_cat.golems_arsenal.client;

import a_silly_cat.golems_arsenal.base.entity.OrbitalImpactEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/**
 * 发射段落点特效渲染：**贴地冲击环（3 圈）+ 中心短闪柱**。
 *
 * <p>参考秘法涡流 {@code Shockwave} 的参数结构（size / expandSpeed / intensity / fade），
 * 但这里不引入它的类，只借"每一圈有自己的出生时间、速度、淡出"这个结构：
 * <pre>
 *   圈 0  出生 0%    速度 1.00x   宽度 0.30   颜色 近白
 *   圈 1  出生 12%   速度 0.78x   宽度 0.22   颜色 暖金
 *   圈 2  出生 26%   速度 0.55x   宽度 0.16   颜色 暖橙（最淡）
 * </pre>
 *
 * <p><b>闪柱</b>：前 40% 的时间里，中心有一根很短的亮柱（高 {@link #FLASH_HEIGHT}），
 * 之后快速淡出。它是"炮口火光"的对应物——用户的瞄准段已经把能量聚到中心，
 * 发射的瞬间需要一根短促的爆闪，而不是继续留着细光柱。
 *
 * <p><b>过曝控制</b>：三层都是加法混合，所以每层 alpha 都压得很低（0.2~0.5），
 * 靠叠加出亮度而不是单独一层就拉满。
 */
@OnlyIn(Dist.CLIENT)
public class OrbitalImpactRenderer extends EntityRenderer<OrbitalImpactEntity> {

    /** 复用光束那张纯白贴图：白色才能被顶点色染成任意颜色。 */
    private static final ResourceLocation TEXTURE =
            new ResourceLocation("golems_arsenal", "textures/entity/orbital_laser.png");

    private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;

    public OrbitalImpactRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0F;
    }

    @Override
    public void render(OrbitalImpactEntity entity, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int light) {
        // **用 tickCount + partialTick 算进度**，而不是读同步值：
        // 同步值每 tick 才更新一次，快速扩散阶段会"一顿一顿"。
        // 参考秘法涡流的 getLerpedSize / getLerpedIntensity 也是这个思路。
        float p = Math.min(1.0F, (entity.tickCount + partialTick) / OrbitalImpactEntity.LIFE);
        float radius = entity.radius();
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucentEmissive(TEXTURE));

        // 光影包 bloom 刹车：压 alpha、把近白拉成暖金。
        float brightness = ShaderEffects.brightness();

        pose.pushPose();
        Matrix4f m = pose.last().pose();

        // 冲击波本体已经交给 ImpactShockwaveFx（屏幕空间无色折射）；
        // 这里只保留弹体残留热痕和爆开的火星，作为"弹体 / 爆炸 / 火花"里的后两者。

        // 弹体残留线（从落点往上到发射高度）
        drawTrail(m, vc, entity.trailTopY() - (float) entity.getY(), p, brightness);

        // **溅射光粒**：砸出来的细小火星，全部用几何画（不是原版粒子）
        drawSparks(m, vc, radius, p, brightness);

        pose.popPose();
        super.render(entity, entityYaw, partialTick, pose, buffer, light);
    }

    // ---- 溅射光粒（几何火星） ----
    /**
     * 火星数量。**不多**：靠"不规则"取胜，不是靠数量堆。
     */
    private static final int SPARKS = 48;
    /** 火星长度（拖尾长度，格）。 */
    private static final float SPARK_STREAK = 0.9F;
    /** 火星粗细（格）。 */
    private static final float SPARK_HALF = 0.045F;

    /**
     * 溅射光粒：从落点向外甩出、受重力回落的细小火星。
     *
     * <p><b>为什么用几何而不是原版粒子</b>：原版粒子有自己的贴图/生命周期/光照，
     * 混进我们的加法渲染里会显脏——这也是上一版"不够协调"的原因之一。
     * 用顶点画的话，配色、衰减、亮度都能和球/环共用同一套规则。
     *
     * <p><b>不规则怎么来</b>：每个火星的参数（方向、速度、起跳时间、寿命）
     * 全部由**索引哈希**决定——纯函数，所以客户端各自算都一致，
     * 不需要同步任何东西，也永远不会出现"两个玩家看到不同火星"。
     *
     * <p>配色沿用整段的温度曲线：白热 → 金黄 → 暗红。
     */
    private static void drawSparks(Matrix4f m, VertexConsumer vc, float radius, float p,
                                   float brightness) {
        for (int i = 0; i < SPARKS; i++) {
            // 用一个混合哈希把索引打散成 5 个 [0,1) 常数
            long h = (i * 0x9E3779B97F4A7C15L) ^ 0x5DEECE66DL;
            float r1 = hashUnit(h);
            float r2 = hashUnit(h * 6364136223846793005L + 1442695040888963407L);
            float r3 = hashUnit(h * 22695477L + 1L);
            float r4 = hashUnit(h * 1103515245L + 12345L);
            float r5 = hashUnit(h * 1664525L + 1013904223L);

            float launch = 0.02F + 0.22F * r1;
            if (p < launch) {
                continue;
            }
            float life = 0.40F + 0.50F * r2;
            float t = (p - launch) / life;
            if (t >= 1.0F) {
                continue;
            }

            // 方向：水平随机、向上初速随机 → 各自不同的抛物线
            double ang = r3 * Math.PI * 2.0;
            float speed = radius * (0.45F + 0.85F * r4);
            float dist = speed * t;
            float up = radius * (0.25F + 0.45F * r5);
            float y = up * t - radius * 0.85F * t * t;

            float x = (float) (Math.cos(ang) * dist);
            float z = (float) (Math.sin(ang) * dist);

            // 拖尾：沿"当前点 → 稍早一点的位置"画一条短亮线
            float back = 0.12F;
            float tBack = Math.max(0.0F, t - back);
            float distBack = speed * tBack;
            float yBack = up * tBack - radius * 0.85F * tBack * tBack;
            float bx = (float) (Math.cos(ang) * distBack);
            float bz = (float) (Math.sin(ang) * distBack);

            // 温度：白热 → 金黄 → 暗红
            float cr;
            float cg;
            float cb;
            if (t < 0.4F) {
                float k = t / 0.4F;
                cr = 1.0F;
                cg = 0.99F - 0.30F * k;
                cb = 0.92F - 0.75F * k;
            } else {
                float k = (t - 0.4F) / 0.6F;
                cr = 1.0F;
                cg = 0.69F - 0.52F * k;
                cb = 0.17F - 0.11F * k;
            }
            float alpha = 0.85F * (1.0F - t) * (1.0F - t) * brightness;

            // 用两片交叉细面近似一根短亮线（不做 billboard，省事且够看）
            sparkQuad(vc, m, bx, yBack, bz, x, y, z, SPARK_HALF,
                    ShaderEffects.tintR(cr),
                    ShaderEffects.tintG(cg),
                    ShaderEffects.tintB(cb), alpha);
        }
    }

    /** 0..1 的伪随机。 */
    private static float hashUnit(long v) {
        v ^= v >>> 33;
        v *= 0xFF51AFD7ED558CCDL;
        v ^= v >>> 33;
        v *= 0xC4CEB9FE1A85EC53L;
        v ^= v >>> 33;
        return (v >>> 11) / (float) (1L << 53);
    }

    /** 一段短亮线：沿 (b→a) 方向的两片交叉面。 */
    private static void sparkQuad(VertexConsumer vc, Matrix4f m,
                                  float bx, float by, float bz,
                                  float ax, float ay, float az,
                                  float half, float r, float g, float b, float alpha) {
        for (int k = 0; k < 2; k++) {
            float nx = k == 0 ? half : 0.0F;
            float nz = k == 0 ? 0.0F : half;
            vc.vertex(m, bx - nx, by, bz - nz).color(r, g, b, alpha * 0.25F)
                    .uv(0.0F, 0.0F).overlayCoords(OverlayTexture.NO_OVERLAY)
                    .uv2(FULL_BRIGHT).normal(0.0F, 0.0F, 1.0F).endVertex();
            vc.vertex(m, bx + nx, by, bz + nz).color(r, g, b, alpha * 0.25F)
                    .uv(1.0F, 0.0F).overlayCoords(OverlayTexture.NO_OVERLAY)
                    .uv2(FULL_BRIGHT).normal(0.0F, 0.0F, 1.0F).endVertex();
            vc.vertex(m, ax + nx, ay, az + nz).color(r, g, b, alpha)
                    .uv(1.0F, 1.0F).overlayCoords(OverlayTexture.NO_OVERLAY)
                    .uv2(FULL_BRIGHT).normal(0.0F, 0.0F, 1.0F).endVertex();
            vc.vertex(m, ax - nx, ay, az - nz).color(r, g, b, alpha)
                    .uv(0.0F, 1.0F).overlayCoords(OverlayTexture.NO_OVERLAY)
                    .uv2(FULL_BRIGHT).normal(0.0F, 0.0F, 1.0F).endVertex();
        }
    }

    /**
     * 弹体残留线：从落点竖直往上到发射高度的一条热痕。
     *
     * <p>用户要求"弹体经过的空气要留下一条像激光的线"。这里用两片交叉的竖面近似，
     * 亮度沿高度和时间同时衰减——所以是"热气散掉"，不是"贴图消失"。
     */
    private static void drawTrail(Matrix4f m, VertexConsumer vc, float topLocal, float p,
                                  float brightness) {
        if (topLocal <= 1.0F) {
            return;
        }
        if (p > 0.95F) {
            return;
        }
        float fade = 1.0F - p / 0.95F;
        float alpha = 0.55F * fade * brightness;
        float half = 0.85F * (0.45F + 0.55F * fade);
        float r = ShaderEffects.tintR(1.0F);
        float g = ShaderEffects.tintG(0.45F);
        float b = ShaderEffects.tintB(0.16F);
        float tr = ShaderEffects.tintR(0.85F);
        float tg = ShaderEffects.tintG(0.16F);
        float tb = ShaderEffects.tintB(0.06F);

        for (int k = 0; k < 2; k++) {
            float nx = k == 0 ? half : 0.0F;
            float nz = k == 0 ? 0.0F : half;
            vc.vertex(m, -nx, 0.0F, -nz).color(r, g, b, alpha)
                    .uv(0.0F, 0.0F).overlayCoords(OverlayTexture.NO_OVERLAY)
                    .uv2(FULL_BRIGHT).normal(0.0F, 0.0F, 1.0F).endVertex();
            vc.vertex(m, nx, 0.0F, nz).color(r, g, b, alpha)
                    .uv(1.0F, 0.0F).overlayCoords(OverlayTexture.NO_OVERLAY)
                    .uv2(FULL_BRIGHT).normal(0.0F, 0.0F, 1.0F).endVertex();
            vc.vertex(m, nx, topLocal, nz).color(tr, tg, tb, alpha * 0.35F)
                    .uv(1.0F, 1.0F).overlayCoords(OverlayTexture.NO_OVERLAY)
                    .uv2(FULL_BRIGHT).normal(0.0F, 0.0F, 1.0F).endVertex();
            vc.vertex(m, -nx, topLocal, -nz).color(tr, tg, tb, alpha * 0.35F)
                    .uv(0.0F, 1.0F).overlayCoords(OverlayTexture.NO_OVERLAY)
                    .uv2(FULL_BRIGHT).normal(0.0F, 0.0F, 1.0F).endVertex();
        }
    }


    /** 平躺与竖直两种朝向共用：这里 vertex 的 y 就是高度，圆环传 0。 */
    private static void vertex(VertexConsumer vc, Matrix4f m, float x, float z, float u,
                               float r, float g, float b, float alpha) {
        vc.vertex(m, x, 0.0F, z)
                .color(r, g, b, alpha)
                .uv(u, 0.5F)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(FULL_BRIGHT)
                .normal(0.0F, 1.0F, 0.0F)
                .endVertex();
    }

    /** 竖直面用这个（带 y 的顶点）。 */
    private static void vertex(VertexConsumer vc, Matrix4f m, float x, float y, float z,
                               float u, float r, float g, float b, float alpha) {
        vc.vertex(m, x, y, z)
                .color(r, g, b, alpha)
                .uv(u, 0.5F)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(FULL_BRIGHT)
                .normal(0.0F, 1.0F, 0.0F)
                .endVertex();
    }

    @Override
    public ResourceLocation getTextureLocation(OrbitalImpactEntity entity) {
        return TEXTURE;
    }
}
