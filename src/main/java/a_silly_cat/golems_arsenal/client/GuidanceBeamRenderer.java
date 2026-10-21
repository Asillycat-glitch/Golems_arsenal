package a_silly_cat.golems_arsenal.client;

import a_silly_cat.golems_arsenal.base.entity.GuidanceBeamEntity;
import a_silly_cat.golems_arsenal.tech.orbit.OrbitalGuidancePath;
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
import org.joml.Vector3f;

/**
 * 引导光束渲染 —— **竖直细光柱 + 极短余晖**，落点沿轨迹移动。
 *
 * <p><b>落点怎么来的</b>：实体只同步 {@code progress}，这里用
 * {@code OrbitalGuidancePath.pointAt(progress, radius)} 算出当前落点。
 * 所以移动是纯客户端的插值，服务端不挪实体、不发位置包。
 *
 * <p><b>余晖为什么不用历史缓冲</b>：轨迹是确定的，知道当前 progress 就能算出
 * "稍早一点的 progress 对应哪个点"。所以余晖直接用
 * {@code pointAt(progress - k·dt)} 反推，k = 1..TRAIL_COUNT。
 * 这样不需要每帧存历史、也不会因为丢包而断裂。
 *
 * <p><b>观感</b>：细、亮、偏白——它是"指示光"，不是主炮。
 * 外层一圈更宽的暖色辉光用来在夜里可读。
 */
@OnlyIn(Dist.CLIENT)
public class GuidanceBeamRenderer extends EntityRenderer<GuidanceBeamEntity> {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation("golems_arsenal", "textures/entity/orbital_laser.png");

    /** 圆柱段数。细光柱用 10 段已经看不出棱角。 */
    private static final int SEGMENTS = 16;

    // 主光（细、亮）
    private static final float CORE_ALPHA = 0.85F;
    private static final float CORE_R = 1.00F;
    private static final float CORE_G = 0.97F;
    private static final float CORE_B = 0.86F;
    private static final float CORE_FACING_POW = 1.30F;

    // 辉光（宽、淡、暖）
    private static final float GLOW_SCALE = 2.6F;
    private static final float GLOW_ALPHA = 0.28F;
    private static final float GLOW_R = 1.00F;
    private static final float GLOW_G = 0.72F;
    private static final float GLOW_B = 0.28F;
    private static final float GLOW_FACING_POW = 0.60F;

    /** 余晖：往回追多少个采样点、每点间隔多少进度。 */
    private static final int TRAIL_COUNT = 5;
    private static final float TRAIL_STEP = 0.010F;
    /** 余晖最亮的那一条相对主体的强度（很弱，只是"扫过"的痕迹）。 */
    private static final float TRAIL_ALPHA = 0.22F;

    /** 只画相机上下各这么长的一段（格）。 */
    private static final double RENDER_WINDOW = 96.0;
    private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;

    public GuidanceBeamRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0F;
    }

    @Override
    public void render(GuidanceBeamEntity entity, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int light) {
        float progress = entity.progress();
        float radius = entity.radius();
        float width = entity.beamWidth();

        double camY = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().y;
        double groundY = entity.getY();
        double topY = groundY + entity.altitude();
        double from = Math.max(groundY, camY - RENDER_WINDOW);
        double to = Math.min(topY, camY + RENDER_WINDOW);
        if (to <= from) {
            super.render(entity, entityYaw, partialTick, pose, buffer, light);
            return;
        }

        var camPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucentEmissive(TEXTURE));

        // 光影包（尤其 MakeUp）的 bloom 对近白自发光非常敏感：压 alpha + 拉暖色。
        float brightness = ShaderEffects.brightness();
        float coreR = ShaderEffects.tintR(CORE_R);
        float coreG = ShaderEffects.tintG(CORE_G);
        float coreB = ShaderEffects.tintB(CORE_B);
        float glowR = ShaderEffects.tintR(GLOW_R);
        float glowG = ShaderEffects.tintG(GLOW_G);
        float glowB = ShaderEffects.tintB(GLOW_B);

        OrbitalGuidancePath.AimSample aim = OrbitalGuidancePath.sampleAim(progress);

        // ① 余晖：六条各自往回追几个采样，做出"扫过"的痕迹（只在聚合阶段之前有意义）
        if (aim.radiusFactor() > 0.05F) {
            for (int k = TRAIL_COUNT; k >= 1; k--) {
                float oldP = progress - k * TRAIL_STEP;
                if (oldP <= 0.0F) {
                    continue;
                }
                OrbitalGuidancePath.AimSample oldAim = OrbitalGuidancePath.sampleAim(oldP);
                float alpha = TRAIL_ALPHA * (1.0F - (float) k / (TRAIL_COUNT + 1))
                        * oldAim.alphaMul() * brightness;
                drawSix(vc, pose, entity, oldAim, radius, from, to, width, camPos,
                        coreR, coreG, coreB, alpha, CORE_FACING_POW);
            }
        }

        // ② 六条引导激光：辉光层 + 亮芯。
        // 聚合完成后不要立刻把六条丢掉：它们已经到圆心，先用 4 tick 把六条淡出、
        // 中心大光柱淡入，避免"外侧突然消失 → 中心突然出现"的跳变。
        float baseAlpha = aim.alphaMul();
        boolean centerPhase = aim.centerScale() > 1.001F;
        float centerFade = 0.0F;
        if (centerPhase) {
            float centerT = (aim.centerScale() - 1.0F)
                    / (OrbitalGuidancePath.AIM_CENTER_SCALE - 1.0F);
            centerFade = Math.min(1.0F, Math.max(0.0F, centerT / 0.25F));
            centerFade = centerFade * centerFade * (3.0F - 2.0F * centerFade);
        }
        if (!centerPhase || centerFade < 0.999F) {
            float sixMul = centerPhase ? 1.0F - centerFade : 1.0F;
            drawSix(vc, pose, entity, aim, radius, from, to, width * GLOW_SCALE, camPos,
                    glowR, glowG, glowB, GLOW_ALPHA * baseAlpha * sixMul * brightness, GLOW_FACING_POW);
            drawSix(vc, pose, entity, aim, radius, from, to, width, camPos,
                    coreR, coreG, coreB, CORE_ALPHA * baseAlpha * sixMul * brightness, CORE_FACING_POW);
        }

        // ③ 聚合完成：中心那一条随 centerFade 淡入，宽度随 centerScale 放大。
        if (centerPhase) {
            float grow = width * aim.centerScale();
            drawBeam(vc, pose, entity, 0.0F, 0.0F, from, to, grow * GLOW_SCALE, camPos,
                    glowR, glowG, glowB, GLOW_ALPHA * centerFade * brightness, GLOW_FACING_POW);
            drawBeam(vc, pose, entity, 0.0F, 0.0F, from, to, grow, camPos,
                    coreR, coreG, coreB, CORE_ALPHA * centerFade * brightness, CORE_FACING_POW);
        }

        super.render(entity, entityYaw, partialTick, pose, buffer, light);
    }

    /** 按采样结果画六条等分 60° 的引导光。 */
    private static void drawSix(VertexConsumer vc, PoseStack pose, GuidanceBeamEntity entity,
                                OrbitalGuidancePath.AimSample aim, float baseRadius,
                                double from, double to, float width,
                                net.minecraft.world.phys.Vec3 camPos,
                                float r, float g, float b, float alpha, float facingPow) {
        float rNow = baseRadius * aim.radiusFactor();
        for (int i = 0; i < OrbitalGuidancePath.AIM_BEAM_COUNT; i++) {
            double ang = Math.toRadians(aim.rotationDeg() + i * (360.0 / OrbitalGuidancePath.AIM_BEAM_COUNT));
            float offX = (float) (Math.cos(ang) * rNow);
            float offZ = (float) (Math.sin(ang) * rNow);
            drawBeam(vc, pose, entity, offX, offZ, from, to, width, camPos,
                    r, g, b, alpha, facingPow);
        }
    }

    /**
     * 在世界坐标 {@code (centerX+offX, centerZ+offZ)} 处画一根竖直光柱。
     *
     */
    private static void drawBeam(VertexConsumer vc, PoseStack pose, GuidanceBeamEntity entity,
                                 float offX, float offZ, double from, double to,
                                 float width, net.minecraft.world.phys.Vec3 camPos,
                                 float r, float g, float b, float alpha,
                                 float facingPow) {
        double cx = entity.getX() + offX;
        double cz = entity.getZ() + offZ;
        float half = width * 0.5F;
        float height = (float) (to - from);

        // 相机相对这根光柱的方位角
        float camAngle = (float) Math.atan2(camPos.z - cz, camPos.x - cx);

        pose.pushPose();
        pose.translate(cx - entity.getX(), from - entity.getY(), cz - entity.getZ());
        Matrix4f m = pose.last().pose();

        for (int i = 0; i < SEGMENTS; i++) {
            float a0 = (float) (i * Math.PI * 2.0 / SEGMENTS);
            float a1 = (float) ((i + 1) * Math.PI * 2.0 / SEGMENTS);
            float am = (a0 + a1) * 0.5F;
            // 只画朝向相机的那半圈。以前正反各画一遍，等于每根光柱的 alpha 翻倍，
            // 在 bloom 里就是"两层 0.85 → 接近纯白"的直接原因。
            if (Math.cos(am - camAngle) <= 0.0F) {
                continue;
            }
            float aEnd0 = facingAlpha(a0, camAngle, facingPow, alpha);
            float aEnd1 = facingAlpha(a1, camAngle, facingPow, alpha);

            float x0 = (float) Math.cos(a0);
            float z0 = (float) Math.sin(a0);
            float x1 = (float) Math.cos(a1);
            float z1 = (float) Math.sin(a1);

            quad(vc, m, x0, z0, x1, z1, half, height, r, g, b, aEnd0, aEnd1);
        }
        pose.popPose();
    }

    /** 某一角度相对相机的朝向 → 顶点 alpha（正对最亮，轮廓淡出）。 */
    private static float facingAlpha(float angle, float camAngle, float pow, float alpha) {
        float facing = Math.abs((float) Math.cos(angle - camAngle));
        return alpha * (0.05F + 0.95F * (float) Math.pow(facing, pow));
    }

    private static void quad(VertexConsumer vc, Matrix4f m,
                             float x0, float z0, float x1, float z1,
                             float half, float height,
                             float r, float g, float b, float a0, float a1) {
        vertex(vc, m, x0 * half, 0.0F, z0 * half, 0.0F, 0.0F, r, g, b, a0);
        vertex(vc, m, x1 * half, 0.0F, z1 * half, 1.0F, 0.0F, r, g, b, a1);
        vertex(vc, m, x1 * half, height, z1 * half, 1.0F, 1.0F, r, g, b, a1);
        vertex(vc, m, x0 * half, height, z0 * half, 0.0F, 1.0F, r, g, b, a0);
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, float x, float y, float z,
                               float u, float v, float r, float g, float b, float alpha) {
        vc.vertex(m, x, y, z)
                .color(r, g, b, alpha)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(FULL_BRIGHT)
                .normal(0.0F, 0.0F, 1.0F)
                .endVertex();
    }

    @Override
    public ResourceLocation getTextureLocation(GuidanceBeamEntity entity) {
        return TEXTURE;
    }
}
