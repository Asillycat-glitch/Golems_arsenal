package a_silly_cat.golems_arsenal.client.refraction;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import java.util.Locale;

/**
 * 「背景折射」试验台的客户端状态机（测试版，不参与任何正式技能逻辑）。
 *
 * <p>这是给 {@code experiment/background-refraction} 分支单独做的一组实验：
 * 目的不是立刻接到天基激光上，而是先<b>把几条技术路线都跑一遍</b>，
 * 量清楚在当前整合包（有 Oculus/光影）里哪条能用、观感如何。
 *
 * <p>四种模式：
 * <ul>
 *   <li>{@link Mode#WARP} —— 真正的屏幕空间折射：拷贝画面 + 采样深度 + 自定义 shader；</li>
 *   <li>{@link Mode#SHELL} —— 纯几何的"空间凹陷"球壳，不需要后处理，光影下也能用；</li>
 *   <li>{@link Mode#FLOW} —— 向内收束的粒子流，纯几何，最便宜；</li>
 *   <li>{@link Mode#HEAT} —— 余热那一秒的空气翻腾（屏幕空间噪声扰动）；</li>
 *   <li>{@link Mode#ALL} —— 折射 + 球壳 + 粒子流一起，看组合效果。</li>
 * </ul>
 *
 * <p>触发器是客户端命令 {@code /gafx ...}（见 {@link RefractionCommand}），
 * 不经过服务端，所以整合包里可以直接用，也不会影响任何人的伤害/判定。
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID, value = Dist.CLIENT)
public final class RefractionFx {

    public enum Mode {
        WARP("warp", true),
        SHELL("shell", false),
        FLOW("flow", false),
        HEAT("heat", true),
        ALL("all", true),
        /**
         * 管线诊断：只把主画面原样拷贝一遍。画面应该<b>完全不变</b>；
         * 如果这一步就黑了/花了，问题在 FBO/后处理管线，不在 shader 数学。
         */
        COPY("copy", true),
        /** 管线诊断：整体 UV 固定偏移。画面应该整体错位 2% 屏宽，证明采样偏移生效。 */
        SHIFT("shift", true);

        private final String id;
        private final boolean usesPost;

        Mode(String id, boolean usesPost) {
            this.id = id;
            this.usesPost = usesPost;
        }

        public String id() {
            return this.id;
        }

        /** 这一档是否需要屏幕空间后处理（也就是会不会撞上 Oculus/光影）。 */
        public boolean usesPost() {
            return this.usesPost;
        }

        public static Mode byId(String id) {
            for (Mode mode : values()) {
                if (mode.id.equals(id)) {
                    return mode;
                }
            }
            return null;
        }
    }

    private static Mode mode;
    private static Vec3 center = Vec3.ZERO;
    private static float radius = 6.0F;
    private static long startTick;
    private static int durationTicks = 80;

    /**
     * Oculus/光影的护栏默认开着：后处理在光影包里大概率被覆盖或直接黑屏。
     * 想硬试一次就 {@code /gafx bypass}，但要做好画面花掉的准备。
     */
    private static boolean bypassShaderGuard;

    private RefractionFx() {
    }

    // ------------------------------------------------------------------ 生命周期

    public static boolean start(Mode newMode, Vec3 newCenter, float newRadius, int ticks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || newMode == null) {
            return false;
        }
        mode = newMode;
        center = newCenter;
        radius = Mth.clamp(newRadius, 1.0F, 32.0F);
        durationTicks = Math.max(10, ticks);
        startTick = mc.level.getGameTime();
        if (newMode.usesPost()) {
            // 上一次可能因为驱动/资源重载失败过；每次重新启动给管线一次机会。
            RefractionPostFx.resetFailure();
        }
        return true;
    }

    public static void stop() {
        mode = null;
    }

    public static boolean active() {
        return mode != null && Minecraft.getInstance().level != null;
    }

    public static Mode mode() {
        return mode;
    }

    public static Vec3 center() {
        return center;
    }

    public static float radius() {
        return radius;
    }

    public static int durationTicks() {
        return durationTicks;
    }

    // ------------------------------------------------------------------ 时间曲线

    /** 0..1：整段特效的进度（带 partialTick 插值）。 */
    public static float progress(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mode == null || mc.level == null) {
            return 0.0F;
        }
        float t = (mc.level.getGameTime() - startTick + partialTick) / (float) durationTicks;
        return Mth.clamp(t, 0.0F, 1.0F);
    }

    /**
     * 0..1 的亮度包络：快速起、缓慢收，首尾都回到 0。
     *
     * <p>用 {@code sin(pi*p)^0.7} 而不是线性淡入淡出：线性淡出在视觉上会"突然没"，
     * 而这条曲线在中段保持得久，最后一段才真正掉下去。
     */
    public static float envelope(float partialTick) {
        float p = progress(partialTick);
        return (float) Math.pow(Math.sin(Math.PI * p), 0.7D);
    }

    /** 当前半径：开头很快撑到接近目标，之后几乎不再动（"砸下去"而不是"吹气球"）。 */
    public static float radiusNow(float partialTick) {
        float p = progress(partialTick);
        float t = Mth.clamp(p / 0.28F, 0.0F, 1.0F);
        float ease = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
        return radius * (0.58F + 0.42F * ease);
    }

    public static float remainingSeconds() {
        Minecraft mc = Minecraft.getInstance();
        if (mode == null || mc.level == null) {
            return 0.0F;
        }
        long elapsed = mc.level.getGameTime() - startTick;
        return Math.max(0.0F, (durationTicks - elapsed) / 20.0F);
    }

    // ------------------------------------------------------------------ 光影护栏

    /** 返回 null 表示没有遮挡；否则返回给玩家看的原因。 */
    public static String shaderGuardReason() {
        if (bypassShaderGuard) {
            return null;
        }
        if (ModList.get().isLoaded("oculus")) {
            return "检测到 Oculus：屏幕空间后处理会和光影管线打架";
        }
        if (ModList.get().isLoaded("iris")) {
            return "检测到 Iris：屏幕空间后处理会和光影管线打架";
        }
        if (ModList.get().isLoaded("optifine")) {
            return "检测到 OptiFine：自定义后处理不保证可用";
        }
        return null;
    }

    public static boolean shaderGuardBypassed() {
        return bypassShaderGuard;
    }

    public static boolean toggleShaderGuardBypass() {
        bypassShaderGuard = !bypassShaderGuard;
        return bypassShaderGuard;
    }

    /** 后处理在运行期失败时，退回到纯几何版本，至少还能看到边界。 */
    public static void fallbackToShell(String reason) {
        if (mode == Mode.WARP || mode == Mode.HEAT || mode == Mode.ALL
                || mode == Mode.COPY || mode == Mode.SHIFT) {
            mode = Mode.SHELL;
            notifyPlayer("后处理不可用，已退回纯几何球壳：" + reason);
        }
    }

    // ------------------------------------------------------------------ tick

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (mode == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            mode = null;
            return;
        }
        long elapsed = mc.level.getGameTime() - startTick;
        if (elapsed >= durationTicks) {
            mode = null;
            return;
        }
        // 动作栏里留一个倒计时：这是测试版，得能一眼看出还剩多久、当前是哪一档。
        if (elapsed % 10L == 0L && mc.player != null) {
            mc.player.displayClientMessage(Component.literal(
                    String.format(Locale.ROOT, "[GA-FX] %s  %.1fs", mode.id,
                            (durationTicks - elapsed) / 20.0F)), true);
        }
    }

    private static void notifyPlayer(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[GA-FX] " + message), false);
        }
    }
}
