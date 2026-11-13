package a_silly_cat.golems_arsenal.testbench;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Path;

/**
 * {@code /ga debug ...}：测试台开关（PRIVATE TESTBENCH）。
 *
 * <pre>
 * /ga debug enable|disable              总开关（默认关；服务器启动自动复位）
 * /ga debug list                        列出全部开关与当前值
 * /ga debug reset                       全部复位
 * /ga debug export                      导出当前条件（可粘贴留档）
 * /ga debug report                      打印汇总并把 JSON 落盘
 * /ga debug clear                       清空统计
 * /ga debug tier &lt;vanilla|plain|armor|armor_ench|pierce4|true_edge|true_edge_cd|magic|fire&gt;
 * /ga debug lever &lt;名字&gt; &lt;true|false&gt;
 * /ga debug extra &lt;0-8&gt;                 额外段数
 * /ga debug restitution &lt;0-1&gt;           END 层返还比例
 * /ga debug strip &lt;0-8&gt; [秒]            摘附魔件数与时长
 * /ga debug writepct &lt;0-1&gt;              写血：扣最大生命的比例
 * /ga debug writebelow &lt;0-1&gt;            写血触发阈值（目标血量比例）
 * /ga debug trim &lt;0-1&gt;                 砍最大生命的比例
 * /ga debug player &lt;true|false&gt;        允许玩家手持也触发
 * /ga debug every &lt;n&gt;                  每 n 条命中打印一次汇总（0 = 不自动打印）
 * </pre>
 */
@Mod.EventBusSubscriber(modid = "golems_arsenal")
public final class TestbenchCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ga")
                .then(Commands.literal("debug")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("enable").executes(ctx -> setEnabled(ctx.getSource(), true)))
                        .then(Commands.literal("disable").executes(ctx -> setEnabled(ctx.getSource(), false)))
                        .then(Commands.literal("help").executes(ctx -> help(ctx.getSource())))
                        .then(Commands.literal("inspect").executes(ctx -> inspect(ctx.getSource())))
                        .then(Commands.literal("sweep")
                                .then(Commands.literal("start")
                                        .executes(ctx -> sweepStart(ctx.getSource(), 20))
                                        .then(Commands.argument("hits", IntegerArgumentType.integer(5, 500))
                                                .executes(ctx -> sweepStart(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "hits")))))
                                .then(Commands.literal("stop").executes(ctx -> sweepStop(ctx.getSource())))
                                .then(Commands.literal("status").executes(ctx -> sweepStatus(ctx.getSource()))))
                        .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                        .then(Commands.literal("reset").executes(ctx -> reset(ctx.getSource())))
                        .then(Commands.literal("export").executes(ctx -> export(ctx.getSource())))
                        .then(Commands.literal("clear").executes(ctx -> {
                            TestbenchCore.clearTallies();
                            return ok(ctx.getSource(), "统计已清空。");
                        }))
                        .then(Commands.literal("report").executes(ctx -> report(ctx.getSource())))
                        .then(Commands.literal("tier")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(ctx -> tier(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id")))))
                        .then(Commands.literal("lever")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("value", BoolArgumentType.bool())
                                                .executes(ctx -> lever(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id"),
                                                        BoolArgumentType.getBool(ctx, "value"))))))
                        .then(Commands.literal("extra")
                                .then(Commands.argument("n", IntegerArgumentType.integer(0, 8))
                                        .executes(ctx -> number(ctx.getSource(), "extraHits",
                                                IntegerArgumentType.getInteger(ctx, "n")))))
                        .then(Commands.literal("restitution")
                                .then(Commands.argument("p", DoubleArgumentType.doubleArg(0.0D, 1.0D))
                                        .executes(ctx -> decimal(ctx.getSource(), "restitution",
                                                DoubleArgumentType.getDouble(ctx, "p")))))
                        .then(Commands.literal("strip")
                                .then(Commands.argument("pieces", IntegerArgumentType.integer(0, 8))
                                        .executes(ctx -> strip(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "pieces"), null))
                                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 600))
                                                .executes(ctx -> strip(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "pieces"),
                                                        IntegerArgumentType.getInteger(ctx, "seconds"))))))
                        .then(Commands.literal("writepct")
                                .then(Commands.argument("p", DoubleArgumentType.doubleArg(0.0D, 1.0D))
                                        .executes(ctx -> decimal(ctx.getSource(), "writePct",
                                                DoubleArgumentType.getDouble(ctx, "p")))))
                        .then(Commands.literal("writebelow")
                                .then(Commands.argument("p", DoubleArgumentType.doubleArg(0.0D, 1.0D))
                                        .executes(ctx -> decimal(ctx.getSource(), "writeBelowPct",
                                                DoubleArgumentType.getDouble(ctx, "p")))))
                        .then(Commands.literal("trim")
                                .then(Commands.argument("p", DoubleArgumentType.doubleArg(0.0D, 1.0D))
                                        .executes(ctx -> decimal(ctx.getSource(), "trimPct",
                                                DoubleArgumentType.getDouble(ctx, "p")))))
                        .then(Commands.literal("player")
                                .then(Commands.argument("value", BoolArgumentType.bool())
                                        .executes(ctx -> bool(ctx.getSource(), "allowPlayer",
                                                BoolArgumentType.getBool(ctx, "value")))))
                        .then(Commands.literal("every")
                                .then(Commands.argument("n", IntegerArgumentType.integer(0, 10000))
                                        .executes(ctx -> number(ctx.getSource(), "reportEvery",
                                                IntegerArgumentType.getInteger(ctx, "n"))))))
                .then(HealthTraceCommand.node()));
    }

    // ------------------------------------------------------------------ 各子命令

    private static int help(CommandSourceStack src) {
        ok(src, "最快的用法（三步）：");
        ok(src, "  1) /ga debug enable");
        ok(src, "  2) /ga debug sweep start 20      —— 自动按档位阶梯跑：plain→armor→armor_ench→pierce4→true_edge→true_edge_cd");
        ok(src, "  3) 用测试武器（example_weapon）打**同一个真正的怪**，每档打满 20 下；打完自动出对比表并落盘");
        ok(src, "注意：别用试验假人（dummmmmmy:target_dummy）—— 它有自己的 ×1.5 与「伤害算出来了但不掉血」行为，会污染结论；");
        ok(src, "     换目标会自动把本档重来，所以中途可以放心停手。");
        ok(src, "单点测试：/ga debug tier <档位> + /ga debug lever <名字> <true|false>，再 /ga debug report 看表。");
        ok(src, "打不动/完全没伤害时：看向那个生物执行 /ga debug inspect —— 会打出它的登记表（immunity / magic_immune / damage_cap…）");
        ok(src, "多段落地：clear_iframe（命中后把目标 invulnerableTime 清零，mgdp 取消升级 / JVG 莫厄武器的手法）对比 true_edge_cd（tag 路线）");
        ok(src, "别忘写血要先开杠杆：/ga debug lever write_health true + /ga debug writepct 0.08（只看 writePct 不会生效，报告里 writeApplied 会是 0）");
        ok(src, "P0 血量观测：/ga health help —— 只读记录事件 / setHealth / getHealth / 移除 / L2 终值，不拦不写。");
        ok(src, "全部开关：/ga debug list；导出条件：/ga debug export；复位：/ga debug reset。");
        return 1;
    }

    private static int inspect(CommandSourceStack src) {
        net.minecraft.world.entity.LivingEntity target = TestbenchCore.resolveInspectTarget(src);
        if (target == null) {
            return fail(src, "没找到目标：看向一个生物（12 格内），或先用测试武器打它一下。");
        }
        for (String line : TestbenchCore.inspectLines(target)) {
            ok(src, line);
        }
        return 1;
    }

    private static int sweepStart(CommandSourceStack src, int hits) {
        DebugFlags flags = flags(src);
        if (flags == null) {
            return fail(src, "拿不到测试台状态");
        }
        TestbenchCore.stopSweep(flags, false);
        TestbenchCore.startSweep(flags, hits);
        return 1;
    }

    private static int sweepStop(CommandSourceStack src) {
        DebugFlags flags = flags(src);
        TestbenchCore.stopSweep(flags, true);
        return ok(src, "扫档已停止（档位已还原）。");
    }

    private static int sweepStatus(CommandSourceStack src) {
        TestbenchCore.Sweep sweep = TestbenchCore.sweep();
        if (sweep == null || !sweep.active) {
            return ok(src, "当前没有在扫档。");
        }
        return ok(src, "扫档进行中：档位 " + sweep.currentTier().id()
                + "（第 " + (sweep.index + 1) + "/" + TestbenchCore.SWEEP_LADDER.size() + " 档）"
                + "  本档进度 " + sweep.attemptsText());
    }

    private static int setEnabled(CommandSourceStack src, boolean value) {
        DebugFlags flags = flags(src);
        if (flags == null) {
            return fail(src, "拿不到测试台状态（只能在服务器上切换）");
        }
        flags.enabled = value;
        flags.setDirty();
        return ok(src, "测试台总开关 = " + value
                + (value ? "（注意：默认全关，测完记得 /ga debug disable）" : ""));
    }

    private static int list(CommandSourceStack src) {
        DebugFlags flags = flags(src);
        if (flags == null) {
            return fail(src, "拿不到测试台状态");
        }
        ok(src, "enabled=" + flags.enabled + "  tier=" + flags.tier.id()
                + "  allowPlayer=" + flags.allowPlayer
                + "  extraHits=" + flags.extraHits
                + "  restitution=" + fmt(flags.restitution)
                + "  strip=" + flags.stripPieces + "/" + flags.stripSeconds + "s"
                + "  writePct=" + fmt(flags.writePct) + " (<" + fmt(flags.writeBelowPct) + ")"
                + "  trimPct=" + fmt(flags.trimPct)
                + "  every=" + flags.reportEvery);
        for (String lever : DebugFlags.LEVERS) {
            ok(src, "  " + lever + " = " + flags.lever(lever));
        }
        ok(src, "当前生效组合：" + flags.activeLeverTag());
        return 1;
    }

    private static int reset(CommandSourceStack src) {
        DebugFlags flags = flags(src);
        if (flags == null) {
            return fail(src, "拿不到测试台状态");
        }
        flags.resetAll();
        TestbenchCore.clearTallies();
        return ok(src, "已全部复位（含统计）。");
    }

    private static int export(CommandSourceStack src) {
        DebugFlags flags = flags(src);
        if (flags == null) {
            return fail(src, "拿不到测试台状态");
        }
        return ok(src, flags.exportLine());
    }

    private static int report(CommandSourceStack src) {
        TestbenchCore.print(src);
        Path file = TestbenchEvents.writeReport(src.getServer());
        return ok(src, file == null ? "报告落盘失败，见日志。" : "报告已写入 " + file);
    }

    private static int tier(CommandSourceStack src, String id) {
        DebugFlags flags = flags(src);
        TestbenchTiers tier = TestbenchTiers.byId(id);
        if (flags == null) {
            return fail(src, "拿不到测试台状态");
        }
        if (tier == null) {
            return fail(src, "没有这个档位：" + id);
        }
        flags.tier = tier;
        flags.setDirty();
        return ok(src, "档位 = " + tier.id());
    }

    private static int lever(CommandSourceStack src, String id, boolean value) {
        DebugFlags flags = flags(src);
        if (flags == null) {
            return fail(src, "拿不到测试台状态");
        }
        if (!DebugFlags.LEVERS.contains(id)) {
            return fail(src, "没有这个杠杆：" + id + "（可用：" + String.join(", ", DebugFlags.LEVERS) + "）");
        }
        flags.setLever(id, value);
        return ok(src, id + " = " + value + "   当前组合：" + flags.activeLeverTag());
    }

    private static int strip(CommandSourceStack src, int pieces, Integer seconds) {
        DebugFlags flags = flags(src);
        if (flags == null) {
            return fail(src, "拿不到测试台状态");
        }
        flags.stripPieces = pieces;
        if (seconds != null) {
            flags.stripSeconds = seconds;
        }
        flags.setDirty();
        return ok(src, "摘附魔：每次命中 " + pieces + " 件，" + flags.stripSeconds + " 秒后还原"
                + "（还要 /ga debug lever strip_enchants true 才生效）");
    }

    private static int number(CommandSourceStack src, String field, int value) {
        DebugFlags flags = flags(src);
        if (flags == null) {
            return fail(src, "拿不到测试台状态");
        }
        switch (field) {
            case "extraHits" -> flags.extraHits = value;
            case "reportEvery" -> flags.reportEvery = value;
            default -> {
                return fail(src, "内部错误：" + field);
            }
        }
        flags.setDirty();
        return ok(src, field + " = " + value);
    }

    private static int decimal(CommandSourceStack src, String field, double value) {
        DebugFlags flags = flags(src);
        if (flags == null) {
            return fail(src, "拿不到测试台状态");
        }
        switch (field) {
            case "restitution" -> flags.restitution = value;
            case "writePct" -> flags.writePct = value;
            case "writeBelowPct" -> flags.writeBelowPct = value;
            case "trimPct" -> flags.trimPct = value;
            default -> {
                return fail(src, "内部错误：" + field);
            }
        }
        flags.setDirty();
        return ok(src, field + " = " + fmt(value));
    }

    private static int bool(CommandSourceStack src, String field, boolean value) {
        DebugFlags flags = flags(src);
        if (flags == null) {
            return fail(src, "拿不到测试台状态");
        }
        if ("allowPlayer".equals(field)) {
            flags.allowPlayer = value;
        } else {
            return fail(src, "内部错误：" + field);
        }
        flags.setDirty();
        return ok(src, field + " = " + value);
    }

    // ------------------------------------------------------------------ 小工具

    private static DebugFlags flags(CommandSourceStack src) {
        return DebugFlags.get(src.getServer());
    }

    private static int ok(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal("[GA-Testbench] " + msg), false);
        return 1;
    }

    private static int fail(CommandSourceStack src, String msg) {
        src.sendFailure(Component.literal("[GA-Testbench] " + msg));
        return 0;
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private TestbenchCommand() {
    }
}
