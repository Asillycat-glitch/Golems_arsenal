package a_silly_cat.golems_arsenal.client;

import a_silly_cat.golems_arsenal.base.entity.LightPillarEntity;
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
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/**
 * 受击光柱的渲染 —— <b>伪造体积光</b>，做法移植自本模组天基炮的激光
 * （{@code OrbitalBeamRenderer}），只换了配色并加了顶部渐隐与地面光环。
 * <p>
 * <b>为什么不再用"四棱柱 + 信标贴图"</b>：
 * <ul>
 *   <li>四棱柱只有 4 个面，任何角度都能看到硬棱边；</li>
 *   <li>整面共用同一个 alpha，面内没有衰减 → 读起来像"半透明的玻璃板"；</li>
 *   <li>信标贴图自带竖向花纹，放大后变成一条条扫描线。</li>
 * </ul>
 * <p>
 * <b>现在这套为什么有体积感</b>（三条都来自天基炮那套已验证的实现）：
 * <ol>
 *   <li>沿高度切 {@link #SLICES} 片、环向 {@link #SEGMENTS} 段，逐段算朝向 ——
 *       于是<b>正对镜头的部分亮、侧过去的转暗</b>，这是柱体有厚度的直接原因；</li>
 *   <li>正对镜头处填"热色"（近白薄荷），轮廓处填"冷色"（饱和绿）——
 *       颜色沿柱面变化，进一步强化圆柱感；</li>
 *   <li>内外两层：外层宽而淡（热晕），内芯窄而亮（白热芯）。</li>
 * </ol>
 * <p>
 * 贴图用本模组的纯白 {@code orbital_laser.png}，颜色全部由顶点色决定。
 */
@OnlyIn(Dist.CLIENT)
public class BeaconPillarRenderer extends EntityRenderer<LightPillarEntity> {

    /** 纯白 16×16，颜色靠顶点色。 */
    private static final ResourceLocation BEAM_TEXTURE =
            new ResourceLocation("golems_arsenal", "textures/entity/orbital_laser.png");

    // ---- 细分 ----

    /** 沿高度的切片数。8 片就够看出起伏，16 片更顺滑。 */
    private static final int SLICES = 14;
    /** 环向分段。10 段足以让朝向渐变看不出多边形。 */
    private static final int SEGMENTS = 10;

    // ---- 两层：外层热晕 + 内芯 ----

    private static final float OUTER_ALPHA = 0.30F;
    private static final float OUTER_FACING_POW = 0.55F;
    private static final float OUTER_DIM = 0.85F;

    private static final float INNER_SCALE = 0.42F;
    private static final float INNER_ALPHA = 0.85F;
    private static final float INNER_FACING_POW = 1.10F;

    // ---- 冷暖两色：正对镜头 = 热，轮廓 = 冷 ----

    private static final float HOT_R = 0.91F;
    private static final float HOT_G = 1.00F;
    private static final float HOT_B = 0.96F;
    /** 轮廓色：饱和绿（与幻梦零剑气同一色系）。 */
    private static final float COOL_R = 0.18F;
    private static final float COOL_G = 0.85F;
    private static final float COOL_B = 0.48F;

    // ---- 抖动：让边缘不像规则几何 ----

    /** 轴心横向偏移幅度（格）。 */
    private static final float WOBBLE_AMP = 0.10F;
    private static final float WOBBLE_FREQ = 0.25F;
    private static final float WOBBLE_SPEED = 0.18F;
    /** 半径扰动幅度（比例）。 */
    private static final float RADIUS_WAVE = 0.16F;
    private static final float RADIUS_FREQ = 0.60F;
    private static final float RADIUS_SPEED = 0.35F;

    // ---- 竖向轮廓 ----

    /** 顶部相对底部的半径比例（略收一点，光柱不该是等粗的桶）。 */
    private static final float TAPER_TOP = 0.86F;
    /** 从这个高度开始向顶部渐隐（0~1）。 */
    private static final float TOP_FADE_START = 0.60F;
    /** 底部淡入区高度（避免与地面出现硬切边）。 */
    private static final float BOTTOM_FADE = 0.07F;

    // ---- 自转与 UV ----

    /** 光柱自转（度/tick）。 */
    private static final float SPIN_DEGREES_PER_TICK = 7.5F;
    private static final float UV_TILES_PER_WORLD = 0.25F;
    private static final float UV_TILES_PER_SECOND = -1.2F;

    // ---- 地面光环 ----

    /** 是否画底部光环。 */
    private static final boolean GROUND_RING = true;
    /** 光环外径相对光柱半径的倍数。 */
    private static final float RING_OUTER_SCALE = 2.60F;
    /** 光环内径相对光柱半径的倍数。 */
    private static final float RING_INNER_SCALE = 0.55F;
    private static final float RING_ALPHA = 0.34F;
    private static final int RING_SEGMENTS = 24;

    private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;

    public BeaconPillarRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0F;
    }

    @Override
    public void render(LightPillarEntity entity, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int light) {
        float height = entity.pillarHeight();
        float radius = entity.pillarWidth() * 0.5F;
        float ticks = entity.tickCount + partialTick;
        float scroll = ticks * UV_TILES_PER_SECOND / 20.0F;

        // 位置由服务端真实推进（螺旋外扩），渲染器不再自行公转 —— 否则判定与视觉会错位。
        double baseX = Mth.lerp(partialTick, entity.xOld, entity.getX());
        double baseY = Mth.lerp(partialTick, entity.yOld, entity.getY());
        double baseZ = Mth.lerp(partialTick, entity.zOld, entity.getZ());

        var camPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        // 相机相对光柱的水平方位角：用来判断"哪一段正对镜头"
        float camAngle = (float) Math.atan2(camPos.z - entity.getZ(), camPos.x - entity.getX());

        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucentEmissive(BEAM_TEXTURE));
        pose.pushPose();
        pose.translate(entity.getX() - baseX, entity.getY() - baseY, entity.getZ() - baseZ);
        pose.mulPose(Axis.YP.rotationDegrees(-SPIN_DEGREES_PER_TICK * ticks));
        Matrix4f m = pose.last().pose();

        float v0 = 0.0F + scroll;
        float v1 = height * UV_TILES_PER_WORLD + scroll;

        // 外层热晕（宽、淡、偏冷）→ 再画内芯（窄、白热）
        // 光影包 bloom 刹车：只压 alpha，保留绿色色相。
        float brightness = ShaderEffects.brightness();
        swept(m, vc, radius, height, v0, v1, camAngle, ticks,
                OUTER_ALPHA * brightness, OUTER_FACING_POW, true);
        swept(m, vc, radius * INNER_SCALE, height, v0, v1, camAngle, ticks,
                INNER_ALPHA * brightness, INNER_FACING_POW, false);

        if (GROUND_RING) {
            groundRing(m, vc, radius, ticks, brightness);
        }

        pose.popPose();

        super.render(entity, entityYaw, partialTick, pose, buffer, light);
    }

    /** 扫出一根柱体。{@code outer = true} 用冷色并整体压暗一档。 */
    private static void swept(Matrix4f m, VertexConsumer vc, float half, float height,
                              float v0, float v1, float camAngle, float ticks,
                              float alpha, float facingPow, boolean outer) {
        for (int i = 0; i < SLICES; i++) {
            float f0 = i / (float) SLICES;
            float f1 = (i + 1) / (float) SLICES;
            float y0 = height * f0;
            float y1 = height * f1;

            // 轴心横向偏移：两个方向不同频率，避免看起来像规则的螺旋
            float ox0 = WOBBLE_AMP * (float) Math.sin(y0 * WOBBLE_FREQ + ticks * WOBBLE_SPEED);
            float oz0 = WOBBLE_AMP * (float) Math.cos(y0 * WOBBLE_FREQ * 0.7F + ticks * WOBBLE_SPEED * 1.3F);
            float ox1 = WOBBLE_AMP * (float) Math.sin(y1 * WOBBLE_FREQ + ticks * WOBBLE_SPEED);
            float oz1 = WOBBLE_AMP * (float) Math.cos(y1 * WOBBLE_FREQ * 0.7F + ticks * WOBBLE_SPEED * 1.3F);

            // 竖向收腰 + 上下淡入淡出
            float taper0 = 1.0F - (1.0F - TAPER_TOP) * f0;
            float taper1 = 1.0F - (1.0F - TAPER_TOP) * f1;
            float fade0 = verticalFade(f0);
            float fade1 = verticalFade(f1);

            float r0 = half * taper0;
            float r1 = half * taper1;

            for (int j = 0; j < SEGMENTS; j++) {
                float a0 = (float) (j * Math.PI * 2.0 / SEGMENTS);
                float a1 = (float) ((j + 1) * Math.PI * 2.0 / SEGMENTS);
                float am = (a0 + a1) * 0.5F;

                // 该段朝向镜头的程度：1 = 正对，0 = 侧对
                float facing = Math.abs((float) Math.cos(am - camAngle));
                float shade = (float) Math.pow(facing, facingPow);
                float a = alpha * (0.04F + 0.96F * shade);

                // 冷暖渐变：正对镜头用热色，轮廓用冷色
                float cr = COOL_R + (HOT_R - COOL_R) * shade;
                float cg = COOL_G + (HOT_G - COOL_G) * shade;
                float cb = COOL_B + (HOT_B - COOL_B) * shade;
                if (outer) {
                    cr *= OUTER_DIM;
                    cg *= OUTER_DIM;
                    cb *= OUTER_DIM;
                }

                // 半径扰动：随高度与角度变化，让边缘有毛刺感
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
                int aa = (int) (Mth.clamp(a * 0.5F * (fade0 + fade1), 0.0F, 1.0F) * 255.0F);

                // 正反各一遍，任何角度都可见
                quad(vc, m, x00, z00, x10, z10, y0, y1, x01, z01, x11, z11,
                        u0, u1, cr, cg, cb, aa);
                quad(vc, m, x10, z10, x00, z00, y0, y1, x11, z11, x01, z01,
                        u0, u1, cr, cg, cb, aa);
            }
        }
    }

    /** 竖向淡入淡出：底部抬起、顶部渐隐。 */
    private static float verticalFade(float f) {
        float bottom = BOTTOM_FADE <= 0.0F ? 1.0F : Mth.clamp(f / BOTTOM_FADE, 0.0F, 1.0F);
        float top = f < TOP_FADE_START ? 1.0F
                : Mth.clamp((1.0F - f) / (1.0F - TOP_FADE_START), 0.0F, 1.0F);
        return bottom * top;
    }

    /**
     * 地面光环：一圈扁平的环带，中心亮、外缘淡。
     * <p>
     * 它的作用不是"装饰"，而是<b>把光柱锚在地上</b> —— 光柱下端如果直接悬空，
     * 会读成"一根悬着的管子"；有一圈光晕落在地面，才像"从地面打上来的光"。
     */
    private static void groundRing(Matrix4f m, VertexConsumer vc, float radius, float ticks,
                                   float brightness) {
        float rIn = radius * RING_INNER_SCALE;
        float rOut = radius * RING_OUTER_SCALE;
        // 轻微呼吸，避免完全静止
        float pulse = 1.0F + 0.06F * (float) Math.sin(ticks * 0.15F);
        rOut *= pulse;

        for (int j = 0; j < RING_SEGMENTS; j++) {
            float a0 = (float) (j * Math.PI * 2.0 / RING_SEGMENTS);
            float a1 = (float) ((j + 1) * Math.PI * 2.0 / RING_SEGMENTS);

            float ci0 = (float) Math.cos(a0);
            float si0 = (float) Math.sin(a0);
            float ci1 = (float) Math.cos(a1);
            float si1 = (float) Math.sin(a1);

            int innerAlpha = (int) (RING_ALPHA * brightness * 255.0F);
            int outerAlpha = 0;

            vertex(vc, m, ci0 * rIn, si0 * rIn, HOT_R, HOT_G, HOT_B, innerAlpha);
            vertex(vc, m, ci1 * rIn, si1 * rIn, HOT_R, HOT_G, HOT_B, innerAlpha);
            vertex(vc, m, ci1 * rOut, si1 * rOut, COOL_R, COOL_G, COOL_B, outerAlpha);
            vertex(vc, m, ci0 * rOut, si0 * rOut, COOL_R, COOL_G, COOL_B, outerAlpha);
        }
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, float x, float z,
                               float r, float g, float b, int a) {
        vc.vertex(m, x, 0.02F, z).color(r, g, b, a).uv(0.5F, 0.5F)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(FULL_BRIGHT)
                .normal(0.0F, 1.0F, 0.0F).endVertex();
    }

    private static void quad(VertexConsumer vc, Matrix4f m,
                             float xb0, float zb0, float xb1, float zb1,
                             float yb, float yt,
                             float xt0, float zt0, float xt1, float zt1,
                             float vBottom, float vTop,
                             float r, float g, float b, int a) {
        vc.vertex(m, xb0, yb, zb0).color(r, g, b, a).uv(0.0F, vBottom)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(FULL_BRIGHT)
                .normal(0.0F, 0.0F, 1.0F).endVertex();
        vc.vertex(m, xb1, yb, zb1).color(r, g, b, a).uv(1.0F, vBottom)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(FULL_BRIGHT)
                .normal(0.0F, 0.0F, 1.0F).endVertex();
        vc.vertex(m, xt1, yt, zt1).color(r, g, b, a).uv(1.0F, vTop)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(FULL_BRIGHT)
                .normal(0.0F, 0.0F, 1.0F).endVertex();
        vc.vertex(m, xt0, yt, zt0).color(r, g, b, a).uv(0.0F, vTop)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(FULL_BRIGHT)
                .normal(0.0F, 0.0F, 1.0F).endVertex();
    }

    @Override
    public ResourceLocation getTextureLocation(LightPillarEntity entity) {
        return BEAM_TEXTURE;
    }
}
