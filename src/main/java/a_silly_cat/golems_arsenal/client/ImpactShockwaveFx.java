package a_silly_cat.golems_arsenal.client;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.base.entity.OrbitalImpactEntity;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Set;

/**
 * 天基炮落点的**无色冲击波**：不画可见光罩，只把已经渲染好的画面拷一份，
 * 在屏幕空间里沿一个不断扩大的圆环做折射。效果是"背景被推开一圈"，没有额外颜色。
 *
 * <p>参考秘法涡流的做法：拷贝画面 → 在冲击波轮廓附近取一圈 → 沿屏幕径向偏移采样。
 * 这里用 {@link PostPass} 做全屏版本，避免自定义网格在相机进入球体内部时整圈消失。
 *
 * <p>速度/距离：环在约 55% 时长内扩到 {@link #MAX_RADIUS_MULT} × 冲击半径
 * （半径 14 时约 42 格），然后消失；屏幕震动只在前 40% 时长内有一次短促衰减。
 *
 * <p><b>同时只允许一个活动波</b>：实机里同一次打击会生成/保留多个冲击实体，
 * 之前每个实体各起一圈，就会出现两次屏幕空间空气波。现在新波只会在没有活动波时创建，
 * 一次渲染也只处理这一个。
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID, value = Dist.CLIENT)
public final class ImpactShockwaveFx {

    private static final float MAX_RADIUS_MULT = 3.0F;
    private static final int DURATION = 24;
    private static final float EXPAND_END = 0.55F;

    /**
     * 这两张实机图里的"很漂亮的震荡"就是这层屏幕空间空气波，所以保留。
     * 它和地面亮环带是同一个落点的两层：亮环带负责"看见"，空气波负责"空间被推开"。
     */
    private static final boolean SCREEN_WARP_ENABLED = true;

    private static Wave activeWave;
    private static int activeImpactId = -1;
    private static final Set<Integer> CONSUMED_IMPACTS = new HashSet<>();
    private static long lastRenderNanos = 0L;

    private static PostPass warpPass;
    private static PostPass blitPass;
    private static TextureTarget swap;
    private static int width = -1;
    private static int height = -1;
    private static boolean failed;

    private ImpactShockwaveFx() {
    }

    private record Wave(Vec3 center, long start, float maxRadius) {
    }

    // ------------------------------------------------------------------ 状态

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            activeWave = null;
            activeImpactId = -1;
            CONSUMED_IMPACTS.clear();
            return;
        }
        // 实体生命周期 26 tick，波只有 24 tick；不记录已消费实体的话，
        // 波结束后会从同一个旧实体再起一圈，正好就是“两次空气波”。
        CONSUMED_IMPACTS.removeIf(id -> mc.level.getEntity(id) == null);
        long now = mc.level.getGameTime();
        if (activeWave != null && now - activeWave.start >= DURATION) {
            activeWave = null;
            activeImpactId = -1;
        }
        // 同一时间只允许一个屏幕空间空气波：已有波时不再接收新的冲击实体。
        if (activeWave != null || mc.player == null) {
            return;
        }
        for (OrbitalImpactEntity impact : mc.level.getEntitiesOfClass(
                OrbitalImpactEntity.class, mc.player.getBoundingBox().inflate(160.0D))) {
            if (!impact.isAlive() || CONSUMED_IMPACTS.contains(impact.getId())) {
                continue;
            }
            activeWave = new Wave(impact.position(), now,
                    Math.max(8.0F, impact.radius() * MAX_RADIUS_MULT));
            activeImpactId = impact.getId();
            CONSUMED_IMPACTS.add(activeImpactId);
            Golems_arsenal.LOGGER.info("[GA-Wave] start id={} active=1", activeImpactId);
            break;
        }
    }

    // ------------------------------------------------------------------ 渲染

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!SCREEN_WARP_ENABLED) {
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || activeWave == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        // 某些光影/多 pass 环境会让 AFTER_LEVEL 在一个画面里触发多次；这里再做一层
        // 时间防抖，保证一帧只合成一次，不会把同一个空气波叠两遍。
        long nanos = System.nanoTime();
        if (nanos - lastRenderNanos < 2_000_000L) {
            return;
        }
        lastRenderNanos = nanos;

        RenderTarget main = mc.getMainRenderTarget();
        if (!ensurePasses(mc, main)) {
            return;
        }

        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        Quaternionf rotation = new Quaternionf(camera.rotation());
        Vector3f forward = new Vector3f(0.0F, 0.0F, 1.0F).rotate(rotation);
        Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F).rotate(rotation);
        Vector3f left = new Vector3f(1.0F, 0.0F, 0.0F).rotate(rotation);
        Vector3f right = new Vector3f(left).negate();

        Matrix4f projection = event.getProjectionMatrix();
        float m00 = projection.m00();
        float m11 = projection.m11();
        if (m00 == 0.0F || m11 == 0.0F) {
            return;
        }
        float tanHalfFov = 1.0F / m11;
        float aspect = m11 / m00;

        long now = mc.level.getGameTime();
        float partial = event.getPartialTick();
        Wave wave = activeWave;
        float progress = (now - wave.start + partial) / (float) DURATION;
        if (progress <= 0.0F || progress >= 1.0F) {
            return;
        }
        float t = Math.min(1.0F, progress / EXPAND_END);
        float ease = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
        float radius = wave.maxRadius * (0.05F + 0.95F * ease);
        float intensity = (float) Math.pow(Math.sin(Math.PI * progress), 0.55D);
        if (intensity <= 0.002F) {
            return;
        }

        Vec3 relative = wave.center.subtract(cameraPos);
        float z = dot(relative, forward);
        if (z <= 0.10F) {
            return;
        }
        float x = dot(relative, right);
        float y = dot(relative, up);
        float centerU = 0.5F + 0.5F * x / (z * tanHalfFov * aspect);
        float centerV = 0.5F + 0.5F * y / (z * tanHalfFov);
        float screenRadius = radius / (2.0F * z * tanHalfFov);
        if (screenRadius <= 0.0005F) {
            return;
        }

        EffectInstance effect = warpPass.getEffect();
        effect.safeGetUniform("CenterUV").set(centerU, centerV);
        effect.safeGetUniform("ScreenRadius").set(screenRadius);
        effect.safeGetUniform("Intensity").set(intensity);
        effect.safeGetUniform("Aspect").set(aspect);

        float time = (now + partial) / 20.0F;
        try {
            warpPass.process(time);
            blitPass.process(time);
            main.bindWrite(true);
            RenderSystem.viewport(0, 0, main.width, main.height);
        } catch (Throwable throwable) {
            failed = true;
            Golems_arsenal.LOGGER.error("[GA-Shockwave] 无色冲击波绘制失败", throwable);
        }
    }

    private static float dot(Vec3 v, Vector3f f) {
        return (float) (v.x * f.x + v.y * f.y + v.z * f.z);
    }

    // ------------------------------------------------------------------ 震屏

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.isPaused()) {
            return;
        }
        float best = 0.0F;
        for (OrbitalImpactEntity impact : mc.level.getEntitiesOfClass(
                OrbitalImpactEntity.class, mc.player.getBoundingBox().inflate(64.0D))) {
            float p = impact.tickCount / (float) OrbitalImpactEntity.LIFE;
            if (p >= 0.40F) {
                continue;
            }
            double distance = mc.player.distanceTo(impact);
            float falloff = distance <= 16.0D ? 1.0F
                    : (float) Math.max(0.0D, (64.0D - distance) / 48.0D);
            float strength = (1.0F - p / 0.40F) * (1.0F - p / 0.40F) * falloff;
            best = Math.max(best, strength);
        }
        if (best <= 0.001F) {
            return;
        }
        double time = (System.currentTimeMillis() % 100000L) / 1000.0D;
        float roll = (float) (Math.sin(time * 47.0D) * 0.7D + Math.sin(time * 71.0D) * 0.3D);
        float yaw = (float) (Math.sin(time * 53.0D) * 0.6D + Math.sin(time * 83.0D) * 0.4D);
        event.setRoll(event.getRoll() + roll * 2.4F * best);
        event.setYaw(event.getYaw() + yaw * 0.8F * best);
    }

    // ------------------------------------------------------------------ 管线

    private static boolean ensurePasses(Minecraft mc, RenderTarget main) {
        if (failed) {
            return false;
        }
        if (warpPass != null && width == main.width && height == main.height) {
            return true;
        }
        closePasses();
        int w = main.width;
        int h = main.height;
        if (w <= 0 || h <= 0) {
            return false;
        }
        try {
            swap = new TextureTarget(w, h, false, Minecraft.ON_OSX);
            swap.setClearColor(0.0F, 0.0F, 0.0F, 1.0F);
            swap.setFilterMode(9728);

            Matrix4f ortho = new Matrix4f().setOrtho(0.0F, (float) w, 0.0F, (float) h, 0.1F, 1000.0F);
            warpPass = new PostPass(mc.getResourceManager(),
                    "golems_arsenal:impact_shockwave", main, swap);
            warpPass.setOrthoMatrix(ortho);

            blitPass = new PostPass(mc.getResourceManager(), "blit", swap, main);
            blitPass.setOrthoMatrix(ortho);

            width = w;
            height = h;
            Golems_arsenal.LOGGER.info("[GA-Shockwave] impact shockwave pipeline ready: {}x{}", w, h);
            return true;
        } catch (Throwable throwable) {
            closePasses();
            failed = true;
            Golems_arsenal.LOGGER.error("[GA-Shockwave] 无法创建无色冲击波管线", throwable);
            return false;
        }
    }

    private static void closePasses() {
        if (warpPass != null) {
            try {
                warpPass.close();
            } catch (Throwable ignored) {
            }
            warpPass = null;
        }
        if (blitPass != null) {
            try {
                blitPass.close();
            } catch (Throwable ignored) {
            }
            blitPass = null;
        }
        if (swap != null) {
            swap.destroyBuffers();
            swap = null;
        }
        width = -1;
        height = -1;
    }
}
