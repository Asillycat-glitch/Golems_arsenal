package a_silly_cat.golems_arsenal.client;

import a_silly_cat.golems_arsenal.base.entity.OrbitalBeamEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
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
 * 天基激光光束渲染 —— **过热空气柱**（2026-10-19 二次重做）。
 *
 * <p><b>为什么不是雷电柱</b>：参考图（RA3 日冕的白虹贯空）里那根东西不是闪电，
 * 而是<b>弹体速度过快把空气烧红、扭曲</b>产生的尾迹。所以要做的是"看起来烫且在翻腾"，
 * 而不是"分叉的电流"。这同时也更符合 MEK 的工业气质。
 *
 * <p><b>四个特征怎么实现</b>（全部是几何 + 顶点色，不需要自定义着色器）：
 * <pre>
 *   不规则边缘   每片半径乘一个随高度/时间变化的正弦扰动（RADIUS_WAVE）
 *   轴心扭曲     每片的圆心按低频噪声横向偏移（WOBBLE_*）
 *   温度渐变     顶点色按"正对相机的程度"在 白热芯 ↔ 红热边 之间插值
 *   持续翻腾     所有噪声的相位随时间推进（ticks）
 * </pre>
 *
 * <p><b>几何结构</b>：以前是"底部 2 个顶点 → 顶部 2 个顶点"的一段四边形，
 * 只有上下两处，做不出沿途扰动。现在沿高度切成 {@link #SLICES} 片、
 * 每片绕一圈 {@link #SEGMENTS} 段，逐片扫掠——扰动才有地方落。
 *
 * <p>贴图仍是本模组自己的纯白 16×16（顶点色相乘决定颜色）；
 * 亮度走 {@code entityTranslucentEmissive} + {@code uv2 = (240,240)} 全亮。
 */
@OnlyIn(Dist.CLIENT)
public class OrbitalBeamRenderer extends EntityRenderer<OrbitalBeamEntity> {

    private static final ResourceLocation BEAM_TEXTURE =
            new ResourceLocation("golems_arsenal", "textures/entity/orbital_laser.png");

    /** 沿高度的切片数。16 片足以看出沿途起伏；再多只是浪费顶点。 */
    private static final int SLICES = 16;
    /**
     * 绕一圈的段数。
     *
     * <p>10 段时，在近距离/宽光柱下能明显看出**四条竖直棱**（实测截图就是一根方柱），
     * 提到 20 段之后棱角消失；顶点量翻倍但仍在可接受范围。
     */
    private static final int SEGMENTS = 20;

    // ---- 外层热晕：宽、淡、偏红 ----
    private static final float OUTER_WIDTH = 1.15F;
    private static final float OUTER_ALPHA = 0.34F;
    private static final float OUTER_FACING_POW = 0.55F;

    // ---- 内芯：窄、白热 ----
    private static final float INNER_SCALE = 0.42F;
    private static final float INNER_ALPHA = 0.80F;
    private static final float INNER_FACING_POW = 1.20F;

    // ---- 温度色：正对相机 = 白热芯，轮廓 = 红热 ----
    private static final float HOT_R = 1.00F;
    private static final float HOT_G = 0.98F;
    private static final float HOT_B = 0.90F;
    private static final float COOL_R = 1.00F;
    private static final float COOL_G = 0.26F;
    private static final float COOL_B = 0.10F;

    // ---- 扰动参数 ----
    /** 轴心横向偏移幅度（格）。 */
    private static final float WOBBLE_AMP = 0.22F;
    /** 轴心偏移的沿高度频率（rad/格）。 */
    private static final float WOBBLE_FREQ = 0.30F;
    /** 轴心偏移的时间推进（rad/tick）。 */
    private static final float WOBBLE_SPEED = 0.22F;
    /** 半径扰动幅度（比例）。 */
    private static final float RADIUS_WAVE = 0.26F;
    /** 半径扰动的沿高度频率。 */
    private static final float RADIUS_FREQ = 0.85F;
    /** 半径扰动的时间推进。 */
    private static final float RADIUS_SPEED = 0.45F;

    /** 越靠近落点越细。 */
    private static final float TAPER_TIP = 0.50F;

    /** UV 滚动（白贴图看不出平铺，只给 UV 一个定义）。 */
    private static final float UV_TILES_PER_WORLD = 0.25F;
    private static final float UV_TILES_PER_SECOND = -1.2F;
    /** 光柱自转（度/tick）。 */
    private static final float SPIN_DEGREES_PER_TICK = 2.0F;

    private static final double RENDER_WINDOW = 96.0;
    private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;

    public OrbitalBeamRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0F;
    }

    @Override
    public void render(OrbitalBeamEntity entity, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int light) {
        // 全流程测试里，伤害光束负责结算冲击伤+余热，但**不做视觉**——
        // 画面由弹体（OrbitalProjectileRenderer）和落点冲击（OrbitalImpactRenderer）承担。
        if (entity.isInvisible()) {
            super.render(entity, entityYaw, partialTick, pose, buffer, light);
            return;
        }

        double baseY = entity.beamTipY();
        double topY = entity.beamTopY();
        double camY = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().y;
        double from = Math.max(baseY, camY - RENDER_WINDOW);
        double to = Math.min(topY, camY + RENDER_WINDOW);
        if (to <= from) {
            super.render(entity, entityYaw, partialTick, pose, buffer, light);
            return;
        }

        float scale = Math.max(0.05F, entity.beamWidth() / OUTER_WIDTH);
        float outerHalf = OUTER_WIDTH * 0.5F * scale;
        float innerHalf = outerHalf * INNER_SCALE;

        float ticks = entity.tickCount + partialTick;
        float scroll = ticks * UV_TILES_PER_SECOND / 20.0F;
        float v0 = (float) (from * UV_TILES_PER_WORLD) + scroll;
        float v1 = (float) (to * UV_TILES_PER_WORLD) + scroll;

        var camPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        float camAngle = (float) Math.atan2(camPos.z - entity.getZ(), camPos.x - entity.getX());

        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucentEmissive(BEAM_TEXTURE));
        float brightness = ShaderEffects.brightness();
        pose.pushPose();
        pose.translate(0.0, from - entity.getY(), 0.0);
        pose.mulPose(Axis.YP.rotationDegrees(-SPIN_DEGREES_PER_TICK * ticks));
        Matrix4f m = pose.last().pose();

        // 外层热晕（宽、偏红），再画内芯（窄、白热）
        swept(m, vc, outerHalf, (float) (to - from), v0, v1, camAngle,
                OUTER_ALPHA * brightness, OUTER_FACING_POW, ticks, true);
        swept(m, vc, innerHalf, (float) (to - from), v0, v1, camAngle,
                INNER_ALPHA * brightness, INNER_FACING_POW, ticks, false);
        pose.popPose();

        super.render(entity, entityYaw, partialTick, pose, buffer, light);
    }

    /**
     * 扫掠出一根过热气柱。
     *
     * @param outer true = 外层热晕（用偏红的 COOL 色），false = 内芯（白热）
     */
    private static void swept(Matrix4f m, VertexConsumer vc, float half, float height,
                              float v0, float v1, float camAngle,
                              float alpha, float facingPow, float ticks, boolean outer) {
        float hotR = ShaderEffects.tintR(HOT_R);
        float hotG = ShaderEffects.tintG(HOT_G);
        float hotB = ShaderEffects.tintB(HOT_B);
        float coolR = ShaderEffects.tintR(COOL_R);
        float coolG = ShaderEffects.tintG(COOL_G);
        float coolB = ShaderEffects.tintB(COOL_B);

        for (int i = 0; i < SLICES; i++) {
            float y0 = height * i / SLICES;
            float y1 = height * (i + 1) / SLICES;
            float f0 = i / (float) SLICES;
            float f1 = (i + 1) / (float) SLICES;

            // 轴心横向偏移：两个方向用不同频率，避免看起来像规则的螺旋
            float ox0 = WOBBLE_AMP * (float) Math.sin(y0 * WOBBLE_FREQ + ticks * WOBBLE_SPEED);
            float oz0 = WOBBLE_AMP * (float) Math.cos(y0 * WOBBLE_FREQ * 0.7F + ticks * WOBBLE_SPEED * 1.3F);
            float ox1 = WOBBLE_AMP * (float) Math.sin(y1 * WOBBLE_FREQ + ticks * WOBBLE_SPEED);
            float oz1 = WOBBLE_AMP * (float) Math.cos(y1 * WOBBLE_FREQ * 0.7F + ticks * WOBBLE_SPEED * 1.3F);

            // 收束：越靠下越细（y=0 是下端口，所以用 (1-f) 表示靠下）
            float taper0 = TAPER_TIP + (1.0F - TAPER_TIP) * f0;
            float taper1 = TAPER_TIP + (1.0F - TAPER_TIP) * f1;

            float r0 = half * taper0;
            float r1 = half * taper1;

            for (int j = 0; j < SEGMENTS; j++) {
                float a0 = (float) (j * Math.PI * 2.0 / SEGMENTS);
                float a1 = (float) ((j + 1) * Math.PI * 2.0 / SEGMENTS);
                float am = (a0 + a1) * 0.5F;

                // 只画朝向相机的那半圈，避免正反两层叠加把 alpha 推到饱和
                float signedFacing = (float) Math.cos(am - camAngle);
                if (signedFacing <= 0.0F) {
                    continue;
                }

                // 该段的朝向（用于 alpha 与温度渐变）
                float facing = signedFacing;
                float shade = (float) Math.pow(facing, facingPow);
                float a = alpha * (0.04F + 0.96F * shade);

                // 温度：正对相机处白热，轮廓处红热
                float t = shade;
                float cr = coolR + (hotR - coolR) * t;
                float cg = coolG + (hotG - coolG) * t;
                float cb = coolB + (hotB - coolB) * t;
                if (outer) {
                    // 外层整体压暗一档，别抢内芯
                    cr *= 0.85F;
                    cg *= 0.85F;
                    cb *= 0.85F;
                }

                // 半径扰动：随高度与角度变化，做出毛刺感的边缘
                float wave0 = 1.0F + RADIUS_WAVE * (float) Math.sin(
                        y0 * RADIUS_FREQ + ticks * RADIUS_SPEED + a0 * 2.0F);
                float wave1 = 1.0F + RADIUS_WAVE * (float) Math.sin(
                        y1 * RADIUS_FREQ + ticks * RADIUS_SPEED + a0 * 2.0F);

                float x00 = ox0 + (float) Math.cos(a0) * r0 * wave0;
                float z00 = oz0 + (float) Math.sin(a0) * r0 * wave0;
                float x10 = ox0 + (float) Math.cos(a1) * r0 * wave0;
                float z10 = oz0 + (float) Math.sin(a1) * r0 * wave0;
                float x01 = ox1 + (float) Math.cos(a0) * r1 * wave1;
                float z01 = oz1 + (float) Math.sin(a0) * r1 * wave1;
                float x11 = ox1 + (float) Math.cos(a1) * r1 * wave1;
                float z11 = oz1 + (float) Math.sin(a1) * r1 * wave1;

                float u0 = v0 + (v1 - v0) * f0;
                float u1 = v0 + (v1 - v0) * f1;

                // 正反各一遍会翻倍 alpha；这里只画正面
                quad(vc, m, x00, z00, x10, z10, y0, y1, x01, z01, x11, z11,
                        u0, u1, cr, cg, cb, a);
            }
        }
    }

    private static void quad(VertexConsumer vc, Matrix4f m,
                             float xb0, float zb0, float xb1, float zb1,
                             float yb, float yt,
                             float xt0, float zt0, float xt1, float zt1,
                             float vBottom, float vTop,
                             float r, float g, float b, float alpha) {
        vc.vertex(m, xb0, yb, zb0).color(r, g, b, alpha)
                .uv(0.0F, vBottom).overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(FULL_BRIGHT).normal(0.0F, 0.0F, 1.0F).endVertex();
        vc.vertex(m, xb1, yb, zb1).color(r, g, b, alpha)
                .uv(1.0F, vBottom).overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(FULL_BRIGHT).normal(0.0F, 0.0F, 1.0F).endVertex();
        vc.vertex(m, xt1, yt, zt1).color(r, g, b, alpha)
                .uv(1.0F, vTop).overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(FULL_BRIGHT).normal(0.0F, 0.0F, 1.0F).endVertex();
        vc.vertex(m, xt0, yt, zt0).color(r, g, b, alpha)
                .uv(0.0F, vTop).overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(FULL_BRIGHT).normal(0.0F, 0.0F, 1.0F).endVertex();
    }

    @Override
    public ResourceLocation getTextureLocation(OrbitalBeamEntity entity) {
        return BEAM_TEXTURE;
    }
}
