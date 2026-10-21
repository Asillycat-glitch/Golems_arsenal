package a_silly_cat.golems_arsenal.client;

import a_silly_cat.golems_arsenal.base.entity.OrbitalProjectileEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
 * 白热射弹渲染：**亮白热核 + 红热拖尾**。
 *
 * <p>用户定的分工：弹体本身是白热的，**速度感交给拖尾**（红热、向后拉长、逐渐变透明）。
 * 所以这里不是画一根光柱，而是一个"泪滴"：
 * <pre>
 *   头部（下端）   白热、最宽处
 *   中段           白 → 橙
 *   拖尾（上端）   橙 → 暗红 → 透明，长度 {#TRAIL_LENGTH} 格
 * </pre>
 *
 * <p>弹体宽度由实体同步（{@code DATA_WIDTH}），默认比瞄准光柱粗，落地前更醒目。
 * 拖尾长度是固定的，因为弹速恒定（{@link OrbitalProjectileEntity} 里 12 格/tick），
 * 视觉上"尾巴拖多长"只取决于曝光，不需要按速度换算。
 */
@OnlyIn(Dist.CLIENT)
public class OrbitalProjectileRenderer extends EntityRenderer<OrbitalProjectileEntity> {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation("golems_arsenal", "textures/entity/orbital_laser.png");

    /**
     * 拖尾长度（格）。
     *
     * <p>参考图六：弹体亮点头上拖着**一整条长而扭的热痕**，不是一小截。
     * 60 格 ≈ 下落 5 tick 的距离（12 格/tick），看上去才像"划破空气"。
     */
    private static final float TRAIL_LENGTH = 80.0F;
    /** 沿拖尾的切片数。切片多一点，扭曲才平滑。 */
    private static final int SLICES = 20;
    /** 拖尾的横向扭曲幅度（格）与频率——参考图六那种"扭动"的形态。 */
    private static final float TRAIL_WOBBLE = 0.90F;
    private static final float TRAIL_WOBBLE_FREQ = 0.20F;
    /** 绕一圈的段数。 */
    private static final int SEGMENTS = 16;

    /** 头部半径相对宽度的比例。 */
    private static final float HEAD_SCALE = 1.8F;
    /** 拖尾根部半径相对头部的比例（越往上越细）。 */
    private static final float TAIL_TAPER = 0.08F;

    // 温度色：白蓝 → 电蓝 → 深蓝
    private static final float HOT_R = 0.90F;
    private static final float HOT_G = 0.97F;
    private static final float HOT_B = 1.00F;
    private static final float MID_R = 0.35F;
    private static final float MID_G = 0.65F;
    private static final float MID_B = 1.00F;
    private static final float TAIL_R = 0.10F;
    private static final float TAIL_G = 0.22F;
    private static final float TAIL_B = 0.75F;

    /** 整体亮度（加法混合，别拉满）。 */
    private static final float ALPHA = 1.0F;

    private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;

    public OrbitalProjectileRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0F;
    }

    @Override
    public void render(OrbitalProjectileEntity entity, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int light) {
        // 淡入：弹体是在 140 格高处凭空出现的，直接全亮会"啪"地闪一下。
        // 前 3 tick 从 0 渐亮，出现过程就看不出来了。
        float fadeIn = Math.min(1.0F, (entity.tickCount + partialTick) / 3.0F);
        float width = entity.beamWidth();
        float half = width * 0.5F * HEAD_SCALE;

        var camPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        float camAngle = (float) Math.atan2(camPos.z - entity.getZ(), camPos.x - entity.getX());

        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucentEmissive(TEXTURE));
        float brightness = ShaderEffects.brightness();
        pose.pushPose();
        // 以弹体位置为原点，向上画拖尾（y 从 0 向上）
        Matrix4f m = pose.last().pose();

        for (int i = 0; i < SLICES; i++) {
            float f0 = i / (float) SLICES;
            float f1 = (i + 1) / (float) SLICES;
            float y0 = TRAIL_LENGTH * f0;
            float y1 = TRAIL_LENGTH * f1;

            // 半径：头部最粗，向上收细
            float r0 = half * (1.0F - (1.0F - TAIL_TAPER) * f0);
            float r1 = half * (1.0F - (1.0F - TAIL_TAPER) * f1);

            // 横向扭曲：沿拖尾长度做低频偏移，模拟"烧热的空气在扭"
            float wob = TRAIL_WOBBLE * (f0 * f0);
            float ox0 = (float) (Math.sin(y0 * TRAIL_WOBBLE_FREQ + entity.tickCount * 0.35F) * wob);
            float oz0 = (float) (Math.cos(y0 * TRAIL_WOBBLE_FREQ * 0.8F + entity.tickCount * 0.35F) * wob);
            float ox1 = (float) (Math.sin(y1 * TRAIL_WOBBLE_FREQ + entity.tickCount * 0.35F) * wob);
            float oz1 = (float) (Math.cos(y1 * TRAIL_WOBBLE_FREQ * 0.8F + entity.tickCount * 0.35F) * wob);

            // 颜色/透明度：白 → 橙 → 暗红 → 透明
            float[] c0 = temp(f0);
            float[] c1 = temp(f1);
            c0[3] *= brightness;
            c1[3] *= brightness;

            // 沿长度的亮度闪动：像烧红的空气在翻腾，而不是一根均匀的柱子
            float flick = 0.78F + 0.22F * (float) Math.sin(
                    entity.tickCount * 0.9F + f0 * 9.0F);

            for (int j = 0; j < SEGMENTS; j++) {
                float a0 = (float) (j * Math.PI * 2.0 / SEGMENTS);
                float a1 = (float) ((j + 1) * Math.PI * 2.0 / SEGMENTS);
                float am = (a0 + a1) * 0.5F;
                float signedFacing = (float) Math.cos(am - camAngle);
                if (signedFacing <= 0.0F) {
                    continue;
                }
                float facing = signedFacing;
                float shade = (0.25F + 0.75F * facing) * flick;

                float x00 = ox0 + (float) Math.cos(a0) * r0;
                float z00 = oz0 + (float) Math.sin(a0) * r0;
                float x10 = ox0 + (float) Math.cos(a1) * r0;
                float z10 = oz0 + (float) Math.sin(a1) * r0;
                float x01 = ox1 + (float) Math.cos(a0) * r1;
                float z01 = oz1 + (float) Math.sin(a0) * r1;
                float x11 = ox1 + (float) Math.cos(a1) * r1;
                float z11 = oz1 + (float) Math.sin(a1) * r1;

                quad(vc, m, x00, z00, x10, z10, y0, y1, x01, z01, x11, z11,
                        c0, c1, shade * fadeIn);
            }
        }

        // 头部冲击套环：让"这一发"比一条细光柱更粗、更像会砸出坑的东西。
        drawShockCollar(vc, m, half, fadeIn, brightness);

        pose.popPose();

        super.render(entity, entityYaw, partialTick, pose, buffer, light);
    }

    /**
     * 头部冲击套环：一圈很淡的白热圆柱，底亮顶透明。
     * 它不改变伤害，只让弹体在画面上有"体积"和"排开空气"的感觉。
     */
    private static void drawShockCollar(VertexConsumer vc, Matrix4f m, float half,
                                        float fadeIn, float brightness) {
        float radius = half * 2.2F;
        float y0 = -half * 0.6F;
        float y1 = half * 1.3F;
        float alpha = 0.34F * fadeIn * brightness;
        if (alpha <= 0.002F) {
            return;
        }
        float r = ShaderEffects.tintR(0.62F);
        float g = ShaderEffects.tintG(0.82F);
        float b = ShaderEffects.tintB(1.00F);
        for (int j = 0; j < SEGMENTS; j++) {
            float a0 = (float) (j * Math.PI * 2.0 / SEGMENTS);
            float a1 = (float) ((j + 1) * Math.PI * 2.0 / SEGMENTS);
            float c0 = (float) Math.cos(a0);
            float s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1);
            float s1 = (float) Math.sin(a1);

            collarVertex(vc, m, c0 * radius, y0, s0 * radius, r, g, b, alpha);
            collarVertex(vc, m, c1 * radius, y0, s1 * radius, r, g, b, alpha);
            collarVertex(vc, m, c1 * radius, y1, s1 * radius, r, g, b, 0.0F);
            collarVertex(vc, m, c0 * radius, y1, s0 * radius, r, g, b, 0.0F);
        }
    }

    private static void collarVertex(VertexConsumer vc, Matrix4f m, float x, float y, float z,
                                     float r, float g, float b, float alpha) {
        vc.vertex(m, x, y, z)
                .color(r, g, b, alpha)
                .uv(0.5F, 0.5F)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(FULL_BRIGHT)
                .normal(0.0F, 1.0F, 0.0F)
                .endVertex();
    }

    /** f=0 头部（白热）→ f=1 拖尾末端（暗红、近透明）。 */
    private static float[] temp(float f) {
        float r;
        float g;
        float b;
        if (f < 0.35F) {
            float t = f / 0.35F;
            r = HOT_R + (MID_R - HOT_R) * t;
            g = HOT_G + (MID_G - HOT_G) * t;
            b = HOT_B + (MID_B - HOT_B) * t;
        } else {
            float t = (f - 0.35F) / 0.65F;
            r = MID_R + (TAIL_R - MID_R) * t;
            g = MID_G + (TAIL_G - MID_G) * t;
            b = MID_B + (TAIL_B - MID_B) * t;
        }
        // 末端淡出
        float a = ALPHA * (1.0F - f) * (1.0F - 0.35F * f);
        return new float[]{
                ShaderEffects.tintR(r),
                ShaderEffects.tintG(g),
                ShaderEffects.tintB(b),
                a
        };
    }

    private static void quad(VertexConsumer vc, Matrix4f m,
                             float xb0, float zb0, float xb1, float zb1,
                             float yb, float yt,
                             float xt0, float zt0, float xt1, float zt1,
                             float[] cBottom, float[] cTop, float shade) {
        vc.vertex(m, xb0, yb, zb0).color(cBottom[0], cBottom[1], cBottom[2], cBottom[3] * shade)
                .uv(0.0F, 0.0F).overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(FULL_BRIGHT).normal(0.0F, 0.0F, 1.0F).endVertex();
        vc.vertex(m, xb1, yb, zb1).color(cBottom[0], cBottom[1], cBottom[2], cBottom[3] * shade)
                .uv(1.0F, 0.0F).overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(FULL_BRIGHT).normal(0.0F, 0.0F, 1.0F).endVertex();
        vc.vertex(m, xt1, yt, zt1).color(cTop[0], cTop[1], cTop[2], cTop[3] * shade)
                .uv(1.0F, 1.0F).overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(FULL_BRIGHT).normal(0.0F, 0.0F, 1.0F).endVertex();
        vc.vertex(m, xt0, yt, zt0).color(cTop[0], cTop[1], cTop[2], cTop[3] * shade)
                .uv(0.0F, 1.0F).overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(FULL_BRIGHT).normal(0.0F, 0.0F, 1.0F).endVertex();
    }

    @Override
    public ResourceLocation getTextureLocation(OrbitalProjectileEntity entity) {
        return TEXTURE;
    }
}
