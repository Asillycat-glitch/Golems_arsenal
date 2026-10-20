package a_silly_cat.golems_arsenal.client.refraction;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Locale;

/**
 * {@code /gafx ...} —— 背景折射试验台的客户端触发命令。
 *
 * <p>为什么是<b>客户端命令</b>而不是服务端命令：
 * 这整组效果都是纯视觉、只影响自己，走服务端反而要加包、加权限，
 * 在整合包里测试时还会因为“单机没开作弊”用不了。客户端命令不经过服务端，
 * 单机/联机都能用，也不会改变任何人的伤害判定。
 *
 * <pre>
 * /gafx warp   [半径] [秒]   真·屏幕空间折射（拷贝画面 + 深度 + shader）
 * /gafx shell  [半径] [秒]   纯几何球壳（不需要后处理）
 * /gafx flow   [半径] [秒]   向内收束的粒子流
 * /gafx heat   [半径] [秒]   余热空气翻腾（屏幕空间噪声）
 * /gafx all    [半径] [秒]   折射 + 球壳 + 粒子流
 * /gafx copy   [秒]          管线诊断：画面应原样不变
 * /gafx shift  [秒]          管线诊断：画面应整体偏移 2% 屏宽
 * /gafx off                  关掉
 * /gafx status               看当前状态 / 后处理是否可用
 * /gafx bypass               开/关 Oculus 护栏（强行尝试后处理）
 * </pre>
 *
 * <p>落点取“准星 24 格内命中的第一个方块/实体”；什么都没指到就放在眼前 8 格。
 * 所以对着地面按，球心就贴着地面，看起来就是参考图里那种“扣在地上的罩子”。
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID, value = Dist.CLIENT)
public final class RefractionCommand {

    private static final float DEFAULT_RADIUS = 6.0F;
    private static final int DEFAULT_SECONDS = 4;

    private RefractionCommand() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("gafx")
                .then(modeNode("warp", RefractionFx.Mode.WARP))
                .then(modeNode("shell", RefractionFx.Mode.SHELL))
                .then(modeNode("flow", RefractionFx.Mode.FLOW))
                .then(modeNode("heat", RefractionFx.Mode.HEAT))
                .then(modeNode("all", RefractionFx.Mode.ALL))
                .then(modeNode("copy", RefractionFx.Mode.COPY))
                .then(modeNode("shift", RefractionFx.Mode.SHIFT))
                .then(Commands.literal("off").executes(ctx -> off(ctx.getSource())))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("bypass").executes(ctx -> bypass(ctx.getSource()))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> modeNode(String name, RefractionFx.Mode mode) {
        return Commands.literal(name)
                .executes(ctx -> start(ctx.getSource(), mode, DEFAULT_RADIUS, DEFAULT_SECONDS))
                .then(Commands.argument("radius", FloatArgumentType.floatArg(1.0F, 32.0F))
                        .executes(ctx -> start(ctx.getSource(), mode,
                                FloatArgumentType.getFloat(ctx, "radius"), DEFAULT_SECONDS))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 60))
                                .executes(ctx -> start(ctx.getSource(), mode,
                                        FloatArgumentType.getFloat(ctx, "radius"),
                                        IntegerArgumentType.getInteger(ctx, "seconds")))));
    }

    private static int start(CommandSourceStack src, RefractionFx.Mode mode, float radius, int seconds) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return fail(src, "还没有进入世界。");
        }
        String guard = RefractionFx.shaderGuardReason();
        if (mode.usesPost() && guard != null) {
            return fail(src, guard + "。要硬试就 /gafx bypass；想稳定看边界就用 /gafx shell。");
        }

        Vec3 center = pickCenter(mc.player);
        if (!RefractionFx.start(mode, center, radius, seconds * 20)) {
            return fail(src, "启动失败（世界还没加载好？）。");
        }
        String extra = mode.usesPost() ? "  后处理已请求" : "  纯几何";
        return ok(src, String.format(Locale.ROOT,
                "%s 已启动：半径 %.1f 格，%d 秒，中心 (%.1f, %.1f, %.1f)%s",
                mode.id(), radius, seconds, center.x, center.y, center.z, extra));
    }

    /**
     * 取准星命中的位置；没指到东西就放在眼前 8 格。
     *
     * <p>对着地面按 → 球心贴地，看到的是“扣在地上的半个罩子”，
     * 这正好是之前讨论的「球形伤害范围可视化」的形态。
     */
    private static Vec3 pickCenter(Player player) {
        HitResult hit = player.pick(24.0D, 1.0F, false);
        if (hit.getType() != HitResult.Type.MISS) {
            return hit.getLocation();
        }
        return player.getEyePosition().add(player.getLookAngle().scale(8.0D));
    }

    private static int off(CommandSourceStack src) {
        boolean wasActive = RefractionFx.active();
        RefractionFx.stop();
        return ok(src, wasActive ? "已关闭。" : "本来就没在播放。");
    }

    private static int status(CommandSourceStack src) {
        if (!RefractionFx.active()) {
            ok(src, "当前没有特效。");
        } else {
            RefractionFx.Mode mode = RefractionFx.mode();
            float p = RefractionFx.progress(0.0F);
            Vec3 c = RefractionFx.center();
            ok(src, String.format(Locale.ROOT,
                    "正在播放：%s  进度 %.0f%%  剩余 %.1fs  半径 %.1f  中心 (%.1f, %.1f, %.1f)",
                    mode == null ? "?" : mode.id(), p * 100.0F, RefractionFx.remainingSeconds(),
                    RefractionFx.radius(), c.x, c.y, c.z));
        }
        ok(src, "后处理：" + RefractionPostFx.statusText());
        String guard = RefractionFx.shaderGuardReason();
        ok(src, "光影护栏：" + (guard == null
                ? (RefractionFx.shaderGuardBypassed() ? "已关闭（强行尝试）" : "无遮挡")
                : guard));
        return 1;
    }

    private static int bypass(CommandSourceStack src) {
        boolean bypassed = RefractionFx.toggleShaderGuardBypass();
        return ok(src, bypassed
                ? "已关闭光影护栏：下一次 /gafx warp|all|heat 会强行走后处理。画面花掉/黑屏就 /gafx off。"
                : "已恢复光影护栏：检测到 Oculus/Iris/OptiFine 时不再走后处理。");
    }

    private static int ok(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal("[GA-FX] " + msg), false);
        return 1;
    }

    private static int fail(CommandSourceStack src, String msg) {
        src.sendFailure(Component.literal("[GA-FX] " + msg));
        return 0;
    }
}
