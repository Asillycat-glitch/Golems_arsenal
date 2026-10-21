package a_silly_cat.golems_arsenal.client;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.base.entity.OrbitalProjectileEntity;
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
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 弹体拖尾的**空气折射**：把主画面拷贝一份，沿拖尾的屏幕投影做低频波浪偏移。
 * 不画闪电、不画颜色，只让背景/拖尾在热空气里轻微折弯。
 *
 * <p>这是按“空气扭曲导致弹体拖尾折射”的理解做的第一版：拖尾本身还是几何，
 * 但周围背景会被折射，形成类似参考视频里那种细长的扭动轮廓。
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID, value = Dist.CLIENT)
public final class TrailRefractionFx {

    private static PostPass warpPass;
    private static PostPass blitPass;
    private static TextureTarget swap;
    private static int width = -1;
    private static int height = -1;
    private static boolean failed;

    private TrailRefractionFx() {
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        OrbitalProjectileEntity projectile = null;
        for (OrbitalProjectileEntity candidate : mc.level.getEntitiesOfClass(
                OrbitalProjectileEntity.class, mc.player.getBoundingBox().inflate(160.0D))) {
            if (candidate.isAlive()) {
                projectile = candidate;
                break;
            }
        }
        if (projectile == null) {
            return;
        }

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

        Vec3 head = projectile.position();
        Vec3 top = head.add(0.0D, 80.0D, 0.0D);
        float[] screenHead = project(head, cameraPos, forward, right, up, tanHalfFov, aspect);
        float[] screenTop = project(top, cameraPos, forward, right, up, tanHalfFov, aspect);
        if (screenHead == null || screenTop == null) {
            return;
        }

        float zAverage = Math.max(0.5F, (screenHead[2] + screenTop[2]) * 0.5F);
        float worldWidth = Math.max(0.8F, projectile.beamWidth() * 1.35F);
        float screenWidth = worldWidth / (2.0F * zAverage * tanHalfFov);
        screenWidth = Math.max(0.006F, Math.min(0.14F, screenWidth));

        float intensity = Math.min(0.95F, 12.0F / zAverage);
        if (intensity <= 0.002F) {
            return;
        }

        EffectInstance effect = warpPass.getEffect();
        effect.safeGetUniform("TrailUV0").set(screenHead[0], screenHead[1]);
        effect.safeGetUniform("TrailUV1").set(screenTop[0], screenTop[1]);
        effect.safeGetUniform("Width").set(screenWidth);
        effect.safeGetUniform("Intensity").set(intensity);
        effect.safeGetUniform("Aspect").set(aspect);

        float time = (mc.level.getGameTime() + event.getPartialTick()) / 20.0F;
        try {
            warpPass.process(time);
            blitPass.process(time);
            main.bindWrite(true);
            RenderSystem.viewport(0, 0, main.width, main.height);
        } catch (Throwable throwable) {
            failed = true;
            Golems_arsenal.LOGGER.error("[GA-Trail] 拖尾空气折射绘制失败", throwable);
        }
    }

    private static float[] project(Vec3 point, Vec3 cameraPos,
                                   Vector3f forward, Vector3f right, Vector3f up,
                                   float tanHalfFov, float aspect) {
        Vec3 rel = point.subtract(cameraPos);
        float z = (float) (rel.x * forward.x + rel.y * forward.y + rel.z * forward.z);
        if (z <= 0.08F) {
            return null;
        }
        float x = (float) (rel.x * right.x + rel.y * right.y + rel.z * right.z);
        float y = (float) (rel.x * up.x + rel.y * up.y + rel.z * up.z);
        float u = 0.5F + 0.5F * x / (z * tanHalfFov * aspect);
        float v = 0.5F + 0.5F * y / (z * tanHalfFov);
        return new float[]{u, v, z};
    }

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
                    "golems_arsenal:trail_refraction", main, swap);
            warpPass.setOrthoMatrix(ortho);

            blitPass = new PostPass(mc.getResourceManager(), "blit", swap, main);
            blitPass.setOrthoMatrix(ortho);

            width = w;
            height = h;
            Golems_arsenal.LOGGER.info("[GA-Trail] 拖尾空气折射管线就绪: {}x{}", w, h);
            return true;
        } catch (Throwable throwable) {
            closePasses();
            failed = true;
            Golems_arsenal.LOGGER.error("[GA-Trail] 无法创建拖尾空气折射管线", throwable);
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
