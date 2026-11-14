package a_silly_cat.golems_arsenal.client;

import a_silly_cat.golems_arsenal.base.entity.OrbitalSigilEntity;
import a_silly_cat.golems_arsenal.tech.orbit.OrbitalGuidancePath;
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
 * 法阵渲染 —— <b>保留下来做渲染示例</b>（用户 2026-10-19 要求）。
 *
 * <p>如果以后要做法阵类联动，这份可以直接改：分段绘制、按 {@code progress} 逐段出现、
 * 贴地光带、笔尖亮斑，四件事都齐了。
 *
 * <p><b>它不再自带一份线段表</b>：形状从 {@link OrbitalGuidancePath#figureSegment}
 * 取——同一份数据既驱动引导光束的落点运动，也画这个图案，**两边永远一致**。
 *
 * <p>注意引导阶段<b>不用</b>这个渲染器（引导是细光柱 + 落点移动，不画图案）。
 */
@OnlyIn(Dist.CLIENT)
public class OrbitalSigilRenderer extends EntityRenderer<OrbitalSigilEntity> {

    /** 复用光束那张纯白贴图：白色才能被顶点色染成任意颜色。 */
    private static final ResourceLocation TEXTURE =
            new ResourceLocation("golems_arsenal", "textures/entity/orbital_laser.png");

    /** 主光带（亮芯）。 */
    private static final float RIBBON_HALF = 0.055F;
    private static final float CORE_R = 1.00F;
    private static final float CORE_G = 0.96F;
    private static final float CORE_B = 0.80F;
    private static final float CORE_ALPHA = 0.95F;

    /** 辉光带（宽、淡、暖）。 */
    private static final float GLOW_HALF = 0.16F;
    private static final float GLOW_R = 1.00F;
    private static final float GLOW_G = 0.72F;
    private static final float GLOW_B = 0.28F;
    private static final float GLOW_ALPHA = 0.30F;

    /** 贴地高度偏移，避免和地面 z-fighting。 */
    private static final float LIFT = 0.03F;
    private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;

    public OrbitalSigilRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0F;
    }

    @Override
    public void render(OrbitalSigilEntity entity, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int light) {
        float progress = Math.min(1.0F, entity.progress());
        float fade = entity.fade();
        if (progress <= 0.0F || fade <= 0.0F) {
            super.render(entity, entityYaw, partialTick, pose, buffer, light);
            return;
        }
        float radius = Math.max(0.5F, entity.radius());
        int total = OrbitalGuidancePath.figureSegmentCount();
        int drawn = (int) Math.ceil(progress * total);

        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucentEmissive(TEXTURE));
        pose.pushPose();
        pose.translate(0.0, LIFT, 0.0);
        Matrix4f m = pose.last().pose();

        // 光影包 bloom 刹车：压 alpha、压 RGB、拉暖色，保留法阵色相。
        float brightness = ShaderEffects.brightness();
        float colorScale = ShaderEffects.colorScale();
        float coreR = ShaderEffects.tintR(CORE_R) * colorScale;
        float coreG = ShaderEffects.tintG(CORE_G) * colorScale;
        float coreB = ShaderEffects.tintB(CORE_B) * colorScale;
        float glowR = ShaderEffects.tintR(GLOW_R) * colorScale;
        float glowG = ShaderEffects.tintG(GLOW_G) * colorScale;
        float glowB = ShaderEffects.tintB(GLOW_B) * colorScale;

        // 先辉光后亮芯（混合顺序）
        drawSegments(m, vc, radius, drawn, GLOW_HALF,
                glowR, glowG, glowB, GLOW_ALPHA * fade * brightness);
        drawSegments(m, vc, radius, drawn, RIBBON_HALF,
                coreR, coreG, coreB, CORE_ALPHA * fade * brightness);

        // 笔尖亮斑
        if (progress < 1.0F && drawn > 0) {
            float[] s = OrbitalGuidancePath.figureSegment(drawn - 1, radius);
            if (s != null) {
                dot(m, vc, s[2], s[3], 0.32F * fade * brightness);
            }
        }
        pose.popPose();

        super.render(entity, entityYaw, partialTick, pose, buffer, light);
    }

    private static void drawSegments(Matrix4f m, VertexConsumer vc, float radius, int drawn,
                                     float halfW, float r, float g, float b, float alpha) {
        int n = Math.min(drawn, OrbitalGuidancePath.figureSegmentCount());
        for (int i = 0; i < n; i++) {
            float[] s = OrbitalGuidancePath.figureSegment(i, radius);
            if (s == null) {
                continue;
            }
            ribbon(m, vc, s[0], s[1], s[2], s[3], halfW, r, g, b, alpha);
        }
    }

    /** 一段光带：以 a→c 为中线，宽度 halfW，画在 XZ 平面上。 */
    private static void ribbon(Matrix4f m, VertexConsumer vc,
                               float ax, float az, float cx, float cz,
                               float halfW, float r, float g, float b, float alpha) {
        float dx = cx - ax;
        float dz = cz - az;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-5F) {
            return;
        }
        float nx = -dz / len * halfW;
        float nz = dx / len * halfW;
        vertex(vc, m, ax + nx, az + nz, 0.0F, r, g, b, alpha);
        vertex(vc, m, cx + nx, cz + nz, 1.0F, r, g, b, alpha);
        vertex(vc, m, cx - nx, cz - nz, 1.0F, r, g, b, alpha);
        vertex(vc, m, ax - nx, az - nz, 0.0F, r, g, b, alpha);
    }

    /** 笔尖亮斑（亮芯 + 更淡的一圈）。 */
    private static void dot(Matrix4f m, VertexConsumer vc, float x, float z, float alpha) {
        for (int layer = 0; layer < 2; layer++) {
            float half = layer == 0 ? 0.22F : 0.10F;
            float a = layer == 0 ? alpha * 0.35F : alpha;
            vertex(vc, m, x - half, z - half, 0.0F, 1.0F, 0.97F, 0.85F, a);
            vertex(vc, m, x + half, z - half, 1.0F, 1.0F, 0.97F, 0.85F, a);
            vertex(vc, m, x + half, z + half, 1.0F, 1.0F, 0.97F, 0.85F, a);
            vertex(vc, m, x - half, z + half, 0.0F, 1.0F, 0.97F, 0.85F, a);
        }
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, float x, float z,
                               float u, float r, float g, float b, float alpha) {
        vc.vertex(m, x, 0.0F, z)
                .color(r, g, b, alpha)
                .uv(u, 0.5F)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(FULL_BRIGHT)
                .normal(0.0F, 1.0F, 0.0F)
                .endVertex();
    }

    @Override
    public ResourceLocation getTextureLocation(OrbitalSigilEntity entity) {
        return TEXTURE;
    }
}
