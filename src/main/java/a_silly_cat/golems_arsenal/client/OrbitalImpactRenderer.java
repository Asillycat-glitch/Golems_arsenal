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
 * 发射段落点特效渲染：**一条带拖尾的贴地冲击环** + 弹体热痕 + 火星。
 *
 * <p>参考图 3–8 的「五雷天罚」主体：不是几圈各自扩散的独立环，而是
 * **一条领先的环带，后面拖着一段逐渐淡掉的尾迹**——像冲击波前沿扫过地面后
 * 留下的余波。环带本身用一条四边形带画出来，外缘亮、内缘透明，所以是一条
 * 连续的带，不是多条线。
 *
 * <p>更外层的空气波交给 {@link ImpactShockwaveFx}（无色屏幕折射）；
 * 这里只负责可见的贴地爆开。
 */
@OnlyIn(Dist.CLIENT)
public class OrbitalImpactRenderer extends EntityRenderer<OrbitalImpactEntity> {

    /** 复用光束那张纯白贴图：白色才能被顶点色染成任意颜色。 */
    private static final ResourceLocation TEXTURE =
            new ResourceLocation("golems_arsenal", "textures/entity/orbital_laser.png");

    /** 贴地环的分段数。48 段在近处也够圆。 */
    private static final int RING_SEGS = 48;

    /** 贴地抬升，避免 z-fighting。 */
    private static final float LIFT = 0.04F;

    private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;

    public OrbitalImpactRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0F;
    }

    @Override
    public void render(OrbitalImpactEntity entity, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int light) {
        // 同一次打击如果生成了两个重叠的冲击实体，只让 ID 最小的那个画主体，
        // 否则会看到两条完全一样的环带/闪光。
        if (!isPrimaryImpact(entity)) {
            return;
        }
        // **用 tickCount + partialTick 算进度**，而不是读同步值：
        // 同步值每 tick 才更新一次，快速扩散阶段会"一顿一顿"。
        // 参考秘法涡流的 getLerpedSize / getLerpedIntensity 也是这个思路。
        float p = Math.min(1.0F, (entity.tickCount + partialTick) / OrbitalImpactEntity.LIFE);
        float radius = entity.radius();
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucentEmissive(TEXTURE));

        // 光影包 bloom 刹车：压 alpha、把近白拉成暖金。
        float brightness = ShaderEffects.brightness();

        pose.pushPose();
        pose.translate(0.0, LIFT, 0.0);
        Matrix4f m = pose.last().pose();

        // 只保留一个冲击波：ImpactShockwaveFx 的屏幕空间空气波。
        // 这里不再画贴地环带/白蓝半球，否则会和空气波叠成“两拨”。
        drawFlashCore(m, vc, radius, p, brightness);

        // 不再画落点残留的实体光柱：它会在夜里变成一根巨大的白柱，
        // 把真正的空气折射完全盖住。拖尾交给 TrailRefractionFx。

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
    private static final float SPARK_HALF = 0.022F;

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
            float back = 0.055F;
            float tBack = Math.max(0.0F, t - back);
            float distBack = speed * tBack;
            float yBack = up * tBack - radius * 0.85F * tBack * tBack;
            float bx = (float) (Math.cos(ang) * distBack);
            float bz = (float) (Math.sin(ang) * distBack);

            // 温度：白蓝 → 电蓝 → 暗蓝
            float cr;
            float cg;
            float cb;
            if (t < 0.4F) {
                float k = t / 0.4F;
                cr = 0.88F - 0.20F * k;
                cg = 0.97F - 0.12F * k;
                cb = 1.00F;
            } else {
                float k = (t - 0.4F) / 0.6F;
                cr = 0.68F - 0.45F * k;
                cg = 0.85F - 0.50F * k;
                cb = 1.00F;
            }
            float alpha = 0.62F * (1.0F - t) * (1.0F - t) * brightness;

            // 用两片交叉细面近似一根短亮线（不做 billboard，省事且够看）
            sparkQuad(vc, m, bx, yBack, bz, x, y, z,
                    SPARK_HALF * (0.55F + 0.75F * r1),
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
     * 一条贴地环带 + 拖尾。
     *
     * <p>外缘在 {@code radius + lead/2}，内缘在 {@code radius - trail}：
     * 外缘是白热前沿，内缘 alpha 为 0、颜色偏橙红，所以看起来是**一条带**，
     * 前面亮、后面慢慢散掉——不是几圈各自独立的环。
     */
    private static void ringBandTrail(Matrix4f m, VertexConsumer vc, float radius,
                                      float lead, float trail, float alpha) {
        float outer = radius + lead * 0.5F;
        float inner = Math.max(0.01F, radius - trail);
        float rOuter = ShaderEffects.tintR(0.86F);
        float gOuter = ShaderEffects.tintG(0.95F);
        float bOuter = ShaderEffects.tintB(1.00F);
        float rInner = ShaderEffects.tintR(0.20F);
        float gInner = ShaderEffects.tintG(0.45F);
        float bInner = ShaderEffects.tintB(1.00F);
        for (int i = 0; i < RING_SEGS; i++) {
            float a0 = (float) (i * Math.PI * 2.0 / RING_SEGS);
            float a1 = (float) ((i + 1) * Math.PI * 2.0 / RING_SEGS);
            float c0 = (float) Math.cos(a0);
            float s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1);
            float s1 = (float) Math.sin(a1);

            vertex(vc, m, c0 * outer, s0 * outer, 1.0F, rOuter, gOuter, bOuter, alpha);
            vertex(vc, m, c1 * outer, s1 * outer, 1.0F, rOuter, gOuter, bOuter, alpha);
            vertex(vc, m, c1 * inner, s1 * inner, 0.0F, rInner, gInner, bInner, 0.0F);
            vertex(vc, m, c0 * inner, s0 * inner, 0.0F, rInner, gInner, bInner, 0.0F);
        }
    }

    /**
     * 爆炸核心：一根持续时间很短的金白闪光柱，四片交叉，不是球。
     *
     * <p>前 20% 时长内从 1.7R 缩到 0.9R，随后消失；它只负责给落点一个
     * “中心炸开”的瞬间，不承担烟尘/碎块。烟尘和二次爆留到下一版。
     */
    private static void drawFlashCore(Matrix4f m, VertexConsumer vc, float radius,
                                      float p, float brightness) {
        if (p <= 0.0F || p >= 0.20F) {
            return;
        }
        float f = 1.0F - p / 0.20F;
        float height = radius * (0.90F + 0.80F * f);
        float width = Math.max(0.06F, radius * 0.07F * f);
        float alpha = 0.55F * f * f * brightness;
        if (alpha <= 0.002F) {
            return;
        }
        float r = ShaderEffects.tintR(0.86F);
        float g = ShaderEffects.tintG(0.95F);
        float b = ShaderEffects.tintB(1.00F);
        float topR = ShaderEffects.tintR(0.30F);
        float topG = ShaderEffects.tintG(0.55F);
        float topB = ShaderEffects.tintB(1.00F);

        for (int plane = 0; plane < 4; plane++) {
            float angle = (float) (plane * Math.PI / 4.0D);
            float dx = (float) Math.cos(angle) * width;
            float dz = (float) Math.sin(angle) * width;

            // 正面
            vertex(vc, m, -dx, 0.0F, -dz, 0.0F, r, g, b, alpha);
            vertex(vc, m, dx, 0.0F, dz, 1.0F, r, g, b, alpha);
            vertex(vc, m, dx, height, dz, 1.0F, topR, topG, topB, 0.0F);
            vertex(vc, m, -dx, height, -dz, 0.0F, topR, topG, topB, 0.0F);

            // 反面（entityTranslucentEmissive 可能背面剔除）
            vertex(vc, m, -dx, height, -dz, 0.0F, topR, topG, topB, 0.0F);
            vertex(vc, m, dx, height, dz, 1.0F, topR, topG, topB, 0.0F);
            vertex(vc, m, dx, 0.0F, dz, 1.0F, r, g, b, alpha);
            vertex(vc, m, -dx, 0.0F, -dz, 0.0F, r, g, b, alpha);
        }
    }

    /**
     * 白蓝能量半球：贴地、向上鼓起的“能罩”，不是完整球。
     * 外层蓝、内层白，高 alpha 直接盖住目标；短寿命，随后交给烟和碎片。
     */
    private static void drawEnergyDome(Matrix4f m, VertexConsumer vc, float radius,
                                       float p, float brightness) {
        if (p <= 0.0F || p >= 0.38F) {
            return;
        }
        float life = p / 0.38F;
        float f = 1.0F - life;
        float ease = 1.0F - f * f * f;
        float domeR = radius * (0.16F + 0.58F * ease);
        float alpha = 0.95F * f * f * brightness;
        if (alpha <= 0.002F) {
            return;
        }
        domeLayer(m, vc, domeR, alpha * 0.48F,
                0.18F, 0.42F, 1.00F,
                0.82F, 0.94F, 1.00F);
        domeLayer(m, vc, domeR * 0.70F, alpha * 0.82F,
                0.72F, 0.88F, 1.00F,
                1.00F, 1.00F, 1.00F);
    }

    private static void domeLayer(Matrix4f m, VertexConsumer vc, float radius, float alpha,
                                  float baseR, float baseG, float baseB,
                                  float topR, float topG, float topB) {
        final int lat = 8;
        final int lon = 24;
        for (int i = 0; i < lat; i++) {
            double phi0 = Math.PI * 0.5D * i / lat;
            double phi1 = Math.PI * 0.5D * (i + 1) / lat;
            float y0 = (float) Math.sin(phi0) * radius;
            float y1 = (float) Math.sin(phi1) * radius;
            float r0 = (float) Math.cos(phi0) * radius;
            float r1 = (float) Math.cos(phi1) * radius;
            float t0 = i / (float) lat;
            float t1 = (i + 1) / (float) lat;
            float shade = 0.78F + 0.22F * hashUnit(i * 131L + 17L);
            for (int j = 0; j < lon; j++) {
                double th0 = Math.PI * 2.0D * j / lon;
                double th1 = Math.PI * 2.0D * (j + 1) / lon;
                float c0 = (float) Math.cos(th0);
                float s0 = (float) Math.sin(th0);
                float c1 = (float) Math.cos(th1);
                float s1 = (float) Math.sin(th1);
                float a = alpha * shade;
                float br = baseR + (topR - baseR) * t0;
                float bg = baseG + (topG - baseG) * t0;
                float bb = baseB + (topB - baseB) * t0;
                float tr = baseR + (topR - baseR) * t1;
                float tg = baseG + (topG - baseG) * t1;
                float tb = baseB + (topB - baseB) * t1;

                vertex(vc, m, c0 * r0, y0, s0 * r0, 0.0F, br, bg, bb, a);
                vertex(vc, m, c1 * r0, y0, s1 * r0, 1.0F, br, bg, bb, a);
                vertex(vc, m, c1 * r1, y1, s1 * r1, 1.0F, tr, tg, tb, a);
                vertex(vc, m, c0 * r1, y1, s0 * r1, 0.0F, tr, tg, tb, a);
            }
        }
    }

    /**
     * 短寿命火球：外层橙、内层白，8 tick 内从 0.18R 膨胀到 0.78R 并淡出。
     * 它不是持续存在的白球，而是爆炸瞬间的火团。
     */
    private static void drawFireball(Matrix4f m, VertexConsumer vc, float radius,
                                     float p, float brightness) {
        if (p <= 0.0F || p >= 0.32F) {
            return;
        }
        float life = Math.min(1.0F, p / 0.32F);
        float f = 1.0F - life;
        float ease = 1.0F - f * f * f;
        float outerRadius = radius * (0.18F + 0.60F * ease);
        float alpha = 0.85F * f * f * brightness;
        if (alpha <= 0.002F) {
            return;
        }
        fireballLayer(m, vc, outerRadius, alpha * 0.55F,
                ShaderEffects.tintR(1.00F),
                ShaderEffects.tintG(0.40F),
                ShaderEffects.tintB(0.10F));
        fireballLayer(m, vc, outerRadius * 0.58F, alpha * 0.85F,
                ShaderEffects.tintR(1.00F),
                ShaderEffects.tintG(0.90F),
                ShaderEffects.tintB(0.66F));
    }

    private static void fireballLayer(Matrix4f m, VertexConsumer vc, float radius, float alpha,
                                      float r, float g, float b) {
        final int lat = 8;
        final int lon = 16;
        for (int i = 0; i < lat; i++) {
            double phi0 = -Math.PI / 2.0 + Math.PI * i / lat;
            double phi1 = -Math.PI / 2.0 + Math.PI * (i + 1) / lat;
            float y0 = (float) Math.sin(phi0) * radius;
            float y1 = (float) Math.sin(phi1) * radius;
            float r0 = (float) Math.cos(phi0) * radius;
            float r1 = (float) Math.cos(phi1) * radius;
            for (int j = 0; j < lon; j++) {
                double t0 = Math.PI * 2.0 * j / lon;
                double t1 = Math.PI * 2.0 * (j + 1) / lon;
                float c0 = (float) Math.cos(t0);
                float s0 = (float) Math.sin(t0);
                float c1 = (float) Math.cos(t1);
                float s1 = (float) Math.sin(t1);

                vertex(vc, m, c0 * r0, y0, s0 * r0, 0.0F, r, g, b, alpha);
                vertex(vc, m, c1 * r0, y0, s1 * r0, 1.0F, r, g, b, alpha);
                vertex(vc, m, c1 * r1, y1, s1 * r1, 1.0F, r, g, b, alpha);
                vertex(vc, m, c0 * r1, y1, s0 * r1, 0.0F, r, g, b, alpha);
            }
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

    private static boolean isPrimaryImpact(OrbitalImpactEntity entity) {
        for (OrbitalImpactEntity other : entity.level().getEntitiesOfClass(
                OrbitalImpactEntity.class, entity.getBoundingBox().inflate(16.0D))) {
            if (other != entity && other.isAlive()
                    && other.getId() < entity.getId()
                    && other.position().distanceToSqr(entity.position()) < 256.0D) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ResourceLocation getTextureLocation(OrbitalImpactEntity entity) {
        return TEXTURE;
    }
}
