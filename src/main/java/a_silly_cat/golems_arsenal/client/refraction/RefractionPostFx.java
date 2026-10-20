package a_silly_cat.golems_arsenal.client.refraction;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 真·屏幕空间折射：把主画面与深度各拷一份，然后用一个全屏 shader 采样并偏移 UV。
 *
 * <p><b>为什么用 {@link PostPass} 而不是自己搭 FBO：</b>
 * 它把“全屏四边形、正交矩阵、DiffuseSampler、InSize/OutSize/Time 这些样板”都处理好了，
 * 而且 {@code addAuxAsset("DepthSampler", ...)} 能直接把深度纹理挂进 shader。
 * 自己搭 FBO 既要重设 viewport，又容易把主目标的反馈环踩出来。
 *
 * <p><b>为什么深度要在 AFTER_PARTICLES 先拷一份：</b>
 * fabulous 画质下，{@code GameRenderer} 在 AFTER_LEVEL 之前会用透明链把画面合回主目标，
 * 那一趟全屏 pass 会把主目标的深度覆盖掉。等 AFTER_LEVEL 再采样，深度已经是坏的。
 * 所以在 AFTER_PARTICLES（深度仍然有效）用 {@link RenderTarget#copyDepthFrom} 先存一份，
 * AFTER_LEVEL 只拷贝颜色、采样我们自己的深度副本。
 *
 * <p><b>Oculus/光影：</b>后处理管线可能被整体替换。默认直接拒绝，
 * 玩家可以用 {@code /gafx bypass} 强行试一次。
 */
public final class RefractionPostFx {

    /** 屏幕空间折射的强度上限（UV 单位，1.0 = 整个屏幕高度）。 */
    private static final float STRENGTH = 0.115F;

    private static PostPass warpPass;
    private static PostPass blitPass;
    private static TextureTarget swap;
    private static TextureTarget depthCopy;
    private static int width = -1;
    private static int height = -1;
    private static int depthWidth = -1;
    private static int depthHeight = -1;

    private static boolean failed;
    private static String failure = "";

    /** 资源重载后置位；真正的 GL 释放/重建在渲染线程的 ensure() 里做。 */
    private static volatile boolean needsRebuild;

    /**
     * 深度是否可采样。某些整合包/模组会把主目标的深度改成 stencil 格式，
     * 这时 {@code glBlitFramebuffer} 会报 GL_INVALID_OPERATION；shader 里就退化成不做遮挡。
     */
    private static boolean depthOk;

    private RefractionPostFx() {
    }

    public static boolean isPostMode() {
        RefractionFx.Mode mode = RefractionFx.mode();
        return mode != null && mode.usesPost();
    }

    /** 光影护栏之外，再确认一次“这一档真的需要后处理”。 */
    private static boolean shouldRun() {
        return RefractionFx.active() && isPostMode() && RefractionFx.shaderGuardReason() == null;
    }

    /** AFTER_PARTICLES：深度还代表不透明场景，先拷走。 */
    public static void captureDepth(RenderLevelStageEvent event) {
        if (!shouldRun()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        if (!ensureDepthCopy(main)) {
            return;
        }
        try {
            // 先清掉之前遗留的 GL 错误，免得把别人的错误算到深度拷贝头上。
            for (int i = 0; i < 8 && GlStateManager._getError() != 0; i++) {
                // drain
            }
            depthCopy.copyDepthFrom(main);
            int error = GlStateManager._getError();
            boolean wasOk = depthOk;
            depthOk = error == 0;
            if (!depthOk && wasOk) {
                Golems_arsenal.LOGGER.warn("[GA-FX] 主目标深度无法拷贝（GL error {}），折射将不做遮挡。", error);
            }
            // copyDepthFrom 结束时把 FBO 绑回了 0；恢复主目标，别把后面的云/雨画到屏幕上。
            main.bindWrite(false);
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** AFTER_LEVEL：颜色已经是最终画面，跑 warp + blit 两个 pass。 */
    public static void render(RenderLevelStageEvent event) {
        if (!shouldRun() || failed) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        if (!ensure(mc, main)) {
            return;
        }

        float partial = event.getPartialTick();
        float env = RefractionFx.envelope(partial);
        if (env <= 0.001F) {
            return;
        }
        RefractionFx.Mode mode = RefractionFx.mode();
        boolean heat = mode == RefractionFx.Mode.HEAT;
        Vec3 center = RefractionFx.center();
        float radius = RefractionFx.radiusNow(partial);

        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();
        Quaternionf rot = new Quaternionf(camera.rotation());
        Vector3f forward = new Vector3f(0.0F, 0.0F, 1.0F).rotate(rot);
        Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F).rotate(rot);
        Vector3f left = new Vector3f(1.0F, 0.0F, 0.0F).rotate(rot);
        Vector3f right = new Vector3f(left).negate();

        float centerU = 0.5F;
        float centerV = 0.5F;
        float screenRadius = 0.25F;
        float strength = STRENGTH;

        Matrix4f projection = event.getProjectionMatrix();
        float m00 = projection.m00();
        float m11 = projection.m11();
        if (m11 == 0.0F || m00 == 0.0F) {
            return;
        }
        // 标准 setPerspective：m11 = 1/tan(fovY/2)，m00 = m11/aspect
        float tanHalfFov = 1.0F / m11;
        float aspect = m11 / m00;

        if (!heat) {
            Vec3 rel = center.subtract(camPos);
            float z = dot(rel, forward);
            if (z <= 0.10F) {
                return; // 球心跑到相机后面了，这一帧不画
            }
            float x = dot(rel, right);
            float y = dot(rel, up);
            centerU = 0.5F + 0.5F * x / (z * tanHalfFov * aspect);
            centerV = 0.5F + 0.5F * y / (z * tanHalfFov);
            // 球在屏幕上的 UV 半径（以屏幕高度为单位）
            screenRadius = radius / (2.0F * z * tanHalfFov);
            if (screenRadius <= 0.0005F) {
                return;
            }
            // 屏幕上的球越大，绝对位移也要跟着放大，否则大球看起来"只扭了一点点"。
            strength *= Math.max(0.65F, Math.min(1.45F, screenRadius / 0.20F));
        }

        EffectInstance fx = warpPass.getEffect();
        fx.safeGetUniform("Mode").set(heat ? 1.0F : 0.0F);
        fx.safeGetUniform("UseDepth").set(depthOk ? 1.0F : 0.0F);
        fx.safeGetUniform("Center").set((float) center.x, (float) center.y, (float) center.z);
        fx.safeGetUniform("Radius").set(radius);
        fx.safeGetUniform("Strength").set(strength);
        fx.safeGetUniform("Progress").set(env);
        fx.safeGetUniform("CenterUV").set(centerU, centerV);
        fx.safeGetUniform("ScreenRadius").set(screenRadius);
        fx.safeGetUniform("CamPos").set((float) camPos.x, (float) camPos.y, (float) camPos.z);
        fx.safeGetUniform("CamForward").set(forward.x, forward.y, forward.z);
        fx.safeGetUniform("CamRight").set(right.x, right.y, right.z);
        fx.safeGetUniform("CamUp").set(up.x, up.y, up.z);
        fx.safeGetUniform("TanHalfFov").set(tanHalfFov);
        fx.safeGetUniform("Aspect").set(aspect);
        fx.safeGetUniform("Near").set(0.05F);
        fx.safeGetUniform("Far").set(mc.gameRenderer.getDepthFar());

        float time = (mc.level == null ? 0L : mc.level.getGameTime() + partial) / 20.0F;
        try {
            warpPass.process(time);
            blitPass.process(time);
        } catch (Throwable t) {
            fail(t);
            return;
        } finally {
            // PostPass 会把 FBO 解绑到 0，并且留下自己的混合/深度状态；还原到“主目标 + 常规状态”，
            // 否则 AFTER_LEVEL 之后的手部渲染会继承这里的状态。
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(true);
            main.bindWrite(true);
            RenderSystem.viewport(0, 0, main.width, main.height);
        }
    }

    // ------------------------------------------------------------------ 资源生命周期

    private static boolean ensure(Minecraft mc, RenderTarget main) {
        if (needsRebuild) {
            // 只重建 program / swap；深度附件的尺寸通常没变，留着还能省一次拷贝。
            closePasses();
            needsRebuild = false;
            failed = false;
            failure = "";
        }
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
        if (!ensureDepthCopy(main)) {
            return false;
        }
        try {
            swap = new TextureTarget(w, h, false, Minecraft.ON_OSX);
            swap.setClearColor(0.0F, 0.0F, 0.0F, 1.0F);
            swap.setFilterMode(9728); // GL_NEAREST

            Matrix4f ortho = new Matrix4f().setOrtho(0.0F, (float) w, 0.0F, (float) h, 0.1F, 1000.0F);

            warpPass = new PostPass(mc.getResourceManager(), "golems_arsenal:warp", main, swap);
            warpPass.setOrthoMatrix(ortho);
            warpPass.addAuxAsset("DepthSampler", depthCopy::getDepthTextureId, w, h);

            blitPass = new PostPass(mc.getResourceManager(), "blit", swap, main);
            blitPass.setOrthoMatrix(ortho);

            width = w;
            height = h;
            Golems_arsenal.LOGGER.info("[GA-FX] 屏幕空间折射管线就绪：{}x{}", w, h);
            return true;
        } catch (Throwable t) {
            closePasses();
            fail(t);
            return false;
        }
    }

    /**
     * 只保证深度副本存在、尺寸正确。它可以独立于 shader 管线创建，
     * 这样 AFTER_PARTICLES 阶段只做 FBO 操作，不会在半个世界渲染帧里编译 shader / 改 program 状态。
     */
    private static boolean ensureDepthCopy(RenderTarget main) {
        if (depthCopy != null && depthWidth == main.width && depthHeight == main.height) {
            return true;
        }
        if (depthCopy != null) {
            depthCopy.destroyBuffers();
            depthCopy = null;
        }
        depthWidth = main.width;
        depthHeight = main.height;
        if (depthWidth <= 0 || depthHeight <= 0) {
            return false;
        }
        try {
            depthCopy = new TextureTarget(depthWidth, depthHeight, true, Minecraft.ON_OSX);
            depthCopy.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            depthCopy.setFilterMode(9728);
            depthOk = false; // 新附件是空的，等下一次 AFTER_PARTICLES 拷贝
            return true;
        } catch (Throwable t) {
            depthWidth = -1;
            depthHeight = -1;
            fail(t);
            return false;
        }
    }

    private static void fail(Throwable t) {
        if (!failed) {
            failed = true;
            failure = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            Golems_arsenal.LOGGER.error("[GA-FX] 屏幕空间折射管线创建/执行失败，退回纯几何球壳", t);
            RefractionFx.fallbackToShell(failure);
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

    /** 新一次启动时清掉失败状态：可能是资源重载/驱动问题，允许再试一次。 */
    public static void resetFailure() {
        failed = false;
        failure = "";
    }

    /**
     * 资源重载回调（可能在非渲染线程）：只置标志，下一次渲染时再释放/重建 GL 对象。
     * 这样改了 shader 按 F3+T 就能生效，不用重启游戏。
     */
    public static void onResourceReload() {
        needsRebuild = true;
    }

    public static String statusText() {
        if (failed) {
            return "失败（已退回纯几何）：" + failure;
        }
        if (warpPass == null) {
            return "尚未创建（第一次 /gafx warp 时编译 shader）";
        }
        return "就绪 " + width + "x" + height;
    }

    /**
     * Vec3 与 JOML 向量点乘（避免在渲染路径里为一次投影构造 Vec3）。
     */
    private static float dot(Vec3 v, Vector3f f) {
        return (float) (v.x * f.x + v.y * f.y + v.z * f.z);
    }
}
