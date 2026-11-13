package a_silly_cat.golems_arsenal.testbench;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * {@code /ga health ...}：P0 观测的命令面（PRIVATE TESTBENCH）。
 *
 * <pre>
 * /ga health start [label]        开始一轮记录（默认落到 config/golems_arsenal/health/p0-*.jsonl）
 * /ga health stop                 停止并写摘要（.json + .txt）
 * /ga health status               当前是否在记录、追踪了谁、待配对 L2 数
 * /ga health track &lt;control|test&gt;
 *                                 看向 32 格内的生物并登记
 * /ga health track &lt;role&gt; self    登记自己（玩家侧测试）
 * /ga health track &lt;role&gt; nearest 登记 48 格内最近的生物（不用瞄准）
 * /ga health track &lt;role&gt; &lt;目标&gt;   实体选择器/玩家名，例如 @e[type=...,limit=1,sort=nearest]
 * /ga health nearby [半径]        列出附近的生物（类型/名字/距离/UUID）
 * /ga health untrack [all]        取消看向的实体 / 全部取消
 * /ga health list                 列出追踪表
 * /ga health all true|false       是否观察所有实体（默认关；开了以后写入量很大）
 * /ga health sample 256           getHealth 每 N 次采样 1 次（默认 256）
 * /ga health gethealth true|false 是否记录 getHealth（默认 true）
 * /ga health report               打印汇总并立刻写摘要
 * /ga health reset                清空追踪表与本地统计（不能在一轮记录进行中执行）
 * </pre>
 *
 * <p>这些命令只在 {@code -Ptestbench} 构建里存在；正式 jar 里没有 {@code /ga health}。</p>
 */
public final class HealthTraceCommand {

    /** 看向的搜索距离；傀儡体型大、站得远时 16 格不够。 */
    private static final double LOOK_RANGE = 32.0D;
    /** nearest 的搜索半径；再远请用 nearby 64 或实体选择器。 */
    private static final double NEAREST_RANGE = 48.0D;

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("health")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("help").executes(ctx -> help(ctx.getSource())))
                .then(Commands.literal("start")
                        .executes(ctx -> start(ctx.getSource(), null))
                        .then(Commands.argument("label", StringArgumentType.greedyString())
                                .executes(ctx -> start(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "label")))))
                .then(Commands.literal("stop").executes(ctx -> stop(ctx.getSource())))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("track")
                        .then(Commands.argument("role", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        List.of("control", "test"), builder))
                                .executes(ctx -> trackLook(ctx.getSource(), roleArg(ctx)))
                                .then(Commands.literal("self")
                                        .executes(ctx -> trackSelf(ctx.getSource(), roleArg(ctx))))
                                .then(Commands.literal("nearest")
                                        .executes(ctx -> trackNearest(ctx.getSource(), roleArg(ctx), null))
                                        .then(Commands.argument("type", ResourceLocationArgument.id())
                                                .executes(ctx -> trackNearest(ctx.getSource(), roleArg(ctx),
                                                        ResourceLocationArgument.getId(ctx, "type")))))
                                .then(Commands.argument("target", EntityArgument.entity())
                                        .executes(ctx -> trackTarget(ctx.getSource(), roleArg(ctx),
                                                EntityArgument.getEntity(ctx, "target"))))))
                .then(Commands.literal("nearby")
                        .executes(ctx -> cmdNearby(ctx.getSource(), 16.0D))
                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1.0D, 64.0D))
                                .executes(ctx -> cmdNearby(ctx.getSource(),
                                        DoubleArgumentType.getDouble(ctx, "radius")))))
                .then(Commands.literal("untrack")
                        .executes(ctx -> untrack(ctx.getSource(), false))
                        .then(Commands.literal("all").executes(ctx -> untrack(ctx.getSource(), true))))
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("all")
                        .then(Commands.argument("value", BoolArgumentType.bool())
                                .executes(ctx -> trackAll(ctx.getSource(),
                                        BoolArgumentType.getBool(ctx, "value")))))
                .then(Commands.literal("sample")
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 4096))
                                .executes(ctx -> sample(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "n")))))
                .then(Commands.literal("gethealth")
                        .then(Commands.argument("value", BoolArgumentType.bool())
                                .executes(ctx -> logGetHealth(ctx.getSource(),
                                        BoolArgumentType.getBool(ctx, "value")))))
                .then(Commands.literal("ledger")
                        .then(Commands.argument("value", BoolArgumentType.bool())
                                .executes(ctx -> ledger(ctx.getSource(),
                                        BoolArgumentType.getBool(ctx, "value")))))
                .then(Commands.literal("unexplained")
                        .executes(ctx -> unexplained(ctx.getSource())))
                .then(Commands.literal("truth")
                        .executes(ctx -> truth(ctx.getSource())))
                .then(Commands.literal("poke")
                        .then(Commands.argument("role", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        List.of("control", "test"), builder))
                                .then(Commands.argument("health", FloatArgumentType.floatArg(0.0F, 1024.0F))
                                        .executes(ctx -> poke(ctx.getSource(), roleArg(ctx),
                                                FloatArgumentType.getFloat(ctx, "health"))))))
                .then(Commands.literal("poke_guarded")
                        .then(Commands.argument("role", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        List.of("control", "test"), builder))
                                .then(Commands.argument("health", FloatArgumentType.floatArg(0.0F, 1024.0F))
                                        .executes(ctx -> pokeGuarded(ctx.getSource(), roleArg(ctx),
                                                FloatArgumentType.getFloat(ctx, "health"))))))
                .then(Commands.literal("discard")
                        .then(Commands.argument("role", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        List.of("control", "test"), builder))
                                .executes(ctx -> discard(ctx.getSource(), roleArg(ctx)))))
                .then(Commands.literal("reflect")
                        .then(Commands.argument("role", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        List.of("control", "test"), builder))
                                .then(Commands.argument("health", FloatArgumentType.floatArg(0.0F, 1024.0F))
                                        .executes(ctx -> reflect(ctx.getSource(), roleArg(ctx),
                                                FloatArgumentType.getFloat(ctx, "health"))))))
                .then(Commands.literal("guard")
                        .then(Commands.literal("all")
                                .then(Commands.literal("off")
                                        .executes(ctx -> guardAllOff(ctx.getSource()))))
                        .then(Commands.argument("role", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        List.of("control", "test"), builder))
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                List.of("off", "shadow", "enforce"), builder))
                                        .executes(ctx -> guard(ctx.getSource(), roleArg(ctx),
                                                StringArgumentType.getString(ctx, "mode"))))))
                .then(Commands.literal("report").executes(ctx -> report(ctx.getSource())))
                .then(Commands.literal("reset").executes(ctx -> reset(ctx.getSource())));
    }

    // ------------------------------------------------------------------ 子命令

    private static int help(CommandSourceStack src) {
        ok(src, "P0 观测（只读）：先追踪 control/test，再 start，打同一个伤害源，最后 stop/report。");
        ok(src, "  /ga health nearby 32            —— 先看附近有什么（类型/名字/距离）");
        ok(src, "  /ga health track control nearest modulargolems:metal_golem");
        ok(src, "                                  —— 站在对照组傀儡旁边执行（只选该类型、未追踪的最近者）");
        ok(src, "  /ga health track test nearest modulargolems:metal_golem");
        ok(src, "  /ga health track control        —— 也可以看向生物执行（32 格内）");
        ok(src, "  /ga health track control @e[type=modulargolems:...,limit=1,sort=nearest]");
        ok(src, "  /ga health track test self      —— 玩家侧测试：追踪自己");
        ok(src, "  /ga health start p0-1           —— 开始写 config/golems_arsenal/health/p0-*.jsonl");
        ok(src, "  /ga health stop                  —— 停止并写 summary.json / summary.txt");
        ok(src, "  /ga health status / list         —— 看当前状态、配对检查（pair=OK/WARN/BAD）");
        ok(src, "P1 影子账本（只读，不拦 setHealth）：");
        ok(src, "  /ga health ledger true|false     —— 开关影子账本（默认 true；control/test 都挂）");
        ok(src, "  /ga health unexplained           —— 看两边 unexplained 计数（正常 L2 应为 0）");
        ok(src, "  /ga health truth                 —— P2 诊断：真值 synced/guarded、getHealth、容器类、索引");
        ok(src, "  /ga health poke test 0           —— 测试刺激：外部 setHealth(0)；P1 只记录，不阻止");
        ok(src, "  /ga health poke test 20          —— 恢复测试目标血量（P1 不改血，poke 是故意写的）");
        ok(src, "  /ga health poke_guarded test 150 —— 绕过 GuardedEntity.setHealth 直写 guardedData（模拟外部直写）");
        ok(src, "  /ga health discard test         —— 测试刺激：discard 清除 test（P1 应报 removed_alive/client_remove）");
        ok(src, "  /ga health reflect test 150     —— 测试刺激：反射直接改真血，绕过 setter（P2 tick 兜底）");
        ok(src, "P2 Guard（只对 test；control 永远 OFF）：");
        ok(src, "  /ga health guard test shadow    —— 只记录 would_block，不拦");
        ok(src, "  /ga health guard test enforce   —— 拦截 setHealth/guardedData/setRemoved 并回滚反射真血");
        ok(src, "  /ga health guard all off        —— 应急：全部回 OFF");
        ok(src, "说明：P0 不拦 setHealth、不改伤害；观测代码本身若有副作用，control/test 的 DIFF 会暴露它。");
        return 1;
    }

    private static int start(CommandSourceStack src, String label) {
        if (HealthTrace.active()) {
            return fail(src, "已经在记录：" + HealthTrace.file());
        }
        String msg = HealthTrace.start(src.getServer(), label);
        if (!HealthTrace.active()) {
            return fail(src, msg);
        }
        return ok(src, msg);
    }

    private static int stop(CommandSourceStack src) {
        if (!HealthTrace.active()) {
            return fail(src, "当前没有在记录。");
        }
        return ok(src, HealthTrace.stop());
    }

    private static int status(CommandSourceStack src) {
        for (String line : HealthTrace.statusLines()) {
            ok(src, line);
        }
        return 1;
    }

    private static int trackLook(CommandSourceStack src, String roleId) {
        HealthTraceData.Role role = parseRole(roleId);
        if (role == null) {
            return fail(src, "角色只能是 control 或 test（收到 " + roleId + "）");
        }
        LivingEntity target = resolveLook(src, LOOK_RANGE);
        if (target == null) {
            cmdNearby(src, 16.0D);
            return fail(src, "没看向生物。直接试：/ga health track " + roleId + " nearest");
        }
        return track(src, target, role);
    }

    private static int trackSelf(CommandSourceStack src, String roleId) {
        HealthTraceData.Role role = parseRole(roleId);
        if (role == null) {
            return fail(src, "角色只能是 control 或 test（收到 " + roleId + "）");
        }
        if (!(src.getEntity() instanceof LivingEntity living)) {
            return fail(src, "执行者不是生物。");
        }
        return track(src, living, role);
    }

    private static int trackNearest(CommandSourceStack src, String roleId, ResourceLocation type) {
        HealthTraceData.Role role = parseRole(roleId);
        if (role == null) {
            return fail(src, "角色只能是 control 或 test（收到 " + roleId + "）");
        }
        if (!(src.getEntity() instanceof Player player)) {
            return fail(src, "nearest 需要玩家执行。");
        }
        HealthTraceData data = data(src);
        if (data == null) {
            return fail(src, "拿不到存档级追踪数据。");
        }
        List<LivingEntity> list = nearby(player, NEAREST_RANGE);
        List<LivingEntity> candidates = new ArrayList<>();
        for (LivingEntity e : list) {
            // 不重复选已追踪实体：这样 track control nearest 之后再 track test nearest
            // 会自动选另一个，而不是把 control 改成 test。
            if (data.get(e.getUUID()) != null) {
                continue;
            }
            if (type != null && !type.equals(ForgeRegistries.ENTITY_TYPES.getKey(e.getType()))) {
                continue;
            }
            candidates.add(e);
        }
        if (candidates.isEmpty()) {
            String filter = type == null ? "" : "（type=" + type + "）";
            return fail(src, NEAREST_RANGE + " 格内没有未追踪的生物" + filter
                    + "；用 /ga health nearby 64 看更远，或 /ga health track " + roleId
                    + " @e[type=...,limit=1,sort=nearest]");
        }
        LivingEntity target = candidates.get(0);
        int result = track(src, target, role);
        if (result == 1) {
            ok(src, "nearest 选中：" + HealthTraceData.typeId(target) + " "
                    + target.getName().getString() + " 距离="
                    + fmt(Math.sqrt(target.distanceToSqr(player)))
                    + "；不对就用 /ga health nearby 或实体选择器");
        }
        return result;
    }

    private static int trackTarget(CommandSourceStack src, String roleId, Entity entity) {
        HealthTraceData.Role role = parseRole(roleId);
        if (role == null) {
            return fail(src, "角色只能是 control 或 test（收到 " + roleId + "）");
        }
        if (!(entity instanceof LivingEntity living)) {
            return fail(src, "目标不是生物：" + HealthTraceData.typeId(entity));
        }
        return track(src, living, role);
    }

    private static int track(CommandSourceStack src, LivingEntity target,
                             HealthTraceData.Role role) {
        HealthTraceData data = data(src);
        if (data == null) {
            return fail(src, "拿不到存档级追踪数据。");
        }
        HealthTraceData.Tracked existing = data.get(target.getUUID());
        if (existing != null && existing.role != role) {
            return fail(src, "这个实体已经登记为 " + existing.role.id()
                    + "；P0 不允许直接改角色（会把对照组弄丢）。"
                    + "先 /ga health untrack all，再重新登记 control/test。");
        }
        HealthTraceData.Tracked tracked = data.track(target, role);
        if (tracked == null) {
            return fail(src, "追踪表已满（" + data.size() + "）。先 /ga health untrack all。");
        }
        HealthTrace.onTrack(target);
        return ok(src, (existing == null ? "已登记：" : "已更新：")
                + HealthTraceData.describe(tracked));
    }

    private static int cmdNearby(CommandSourceStack src, double radius) {
        if (!(src.getEntity() instanceof Player player)) {
            return fail(src, "nearby 需要玩家执行。");
        }
        List<LivingEntity> list = nearby(player, radius);
        if (list.isEmpty()) {
            return ok(src, "附近 " + fmt(radius) + " 格内没有生物。");
        }
        ok(src, "附近 " + fmt(radius) + " 格内的生物（按距离，最多显示 8 个）：");
        int n = Math.min(8, list.size());
        for (int i = 0; i < n; i++) {
            LivingEntity e = list.get(i);
            ok(src, String.format(Locale.ROOT, "  #%d %s 名=%s 距离=%.1f uuid=%s",
                    i + 1, HealthTraceData.typeId(e), e.getName().getString(),
                    Math.sqrt(e.distanceToSqr(player)), e.getUUID()));
        }
        if (list.size() > n) {
            ok(src, "  ... 还有 " + (list.size() - n) + " 个");
        }
        ok(src, "追踪：/ga health track <control|test> nearest [type]");
        ok(src, "例：/ga health track control nearest modulargolems:metal_golem");
        ok(src, "精确：/ga health track <control|test> @e[type=...,limit=1,sort=nearest]");
        return 1;
    }

    private static int untrack(CommandSourceStack src, boolean all) {
        HealthTraceData data = data(src);
        if (data == null) {
            return fail(src, "拿不到存档级追踪数据。");
        }
        if (all) {
            int n = data.clearTracked();
            HealthTrace.onClearTracked();
            return ok(src, "已清空追踪表（" + n + " 个实体）。");
        }
        LivingEntity target = resolveLook(src, LOOK_RANGE);
        if (target == null) {
            cmdNearby(src, 16.0D);
            return fail(src, "没看向生物；要清空全部请用 /ga health untrack all。");
        }
        boolean removed = data.untrack(target.getUUID());
        if (removed) {
            HealthTrace.onUntrack(target.getUUID());
        }
        return removed ? ok(src, "已取消追踪："
                + HealthTraceData.typeId(target) + " " + target.getName().getString())
                : fail(src, "这个实体不在追踪表里。");
    }

    private static int list(CommandSourceStack src) {
        HealthTraceData data = data(src);
        if (data == null) {
            return fail(src, "拿不到存档级追踪数据。");
        }
        ok(src, "trackAll=" + data.trackAll + " sampleEvery=" + data.sampleEvery
                + " logGetHealth=" + data.logGetHealth + " ledger=" + data.ledgerEnabled
                + " tracked=" + data.size());
        ok(src, data.pairSummary());
        if (data.size() == 0) {
            ok(src, "（追踪表为空）");
        }
        for (HealthTraceData.Tracked t : data.tracked()) {
            ok(src, "  " + HealthTraceData.describe(t));
        }
        return 1;
    }

    private static int trackAll(CommandSourceStack src, boolean value) {
        HealthTraceData data = data(src);
        if (data == null) {
            return fail(src, "拿不到存档级追踪数据。");
        }
        data.trackAll = value;
        data.setDirty();
        return ok(src, "trackAll = " + value
                + (value ? "（会把所有实体的 setHealth/事件都写进 trace，量很大；建议只在摸底时短开）" : ""));
    }

    private static int sample(CommandSourceStack src, int n) {
        HealthTraceData data = data(src);
        if (data == null) {
            return fail(src, "拿不到存档级追踪数据。");
        }
        data.sampleEvery = n;
        data.setDirty();
        return ok(src, "getHealth 采样：每 " + n + " 次记 1 次调用者（第 1 次也会记）。");
    }

    private static int logGetHealth(CommandSourceStack src, boolean value) {
        HealthTraceData data = data(src);
        if (data == null) {
            return fail(src, "拿不到存档级追踪数据。");
        }
        data.logGetHealth = value;
        data.setDirty();
        return ok(src, "logGetHealth = " + value);
    }

    private static int ledger(CommandSourceStack src, boolean value) {
        int entries = HealthTrace.setLedgerEnabled(src.getServer(), value);
        if (entries < 0) {
            return fail(src, "拿不到存档级追踪数据。");
        }
        return ok(src, "ledger = " + value + "（entries=" + entries
                + "；P1 只读，不会拦截 setHealth）");
    }

    private static int unexplained(CommandSourceStack src) {
        for (String line : HealthTrace.unexplainedLines()) {
            ok(src, line);
        }
        return 1;
    }

    private static int truth(CommandSourceStack src) {
        for (String line : HealthTrace.truthLines(src.getServer())) {
            ok(src, line);
        }
        return 1;
    }

    private static int poke(CommandSourceStack src, String roleId, float health) {
        HealthTraceData.Role role = parseRole(roleId);
        if (role == null) {
            return fail(src, "角色只能是 control 或 test（收到 " + roleId + "）");
        }
        if (!HealthTrace.active()) {
            return fail(src, "先 /ga health start；poke 只在记录中有效（stop 会清空 ledger）。");
        }
        HealthTrace.PokeResult result = HealthTrace.poke(src.getServer(), role, health);
        if (result.total() <= 0) {
            return fail(src, "没有可写的 " + roleId + " 实体（未追踪/未加载）。");
        }
        String detail = "total=" + result.total()
                + " accepted=" + result.accepted()
                + " rejected=" + result.rejected()
                + " dead=" + result.dead()
                + " unchanged=" + result.unchanged()
                + " guardBlocked=" + result.guardBlocked();
        if (result.accepted() <= 0) {
            if (result.guardBlocked() > 0) {
                return fail(src, "setHealth(" + health + ") 被 P2 Guard ENFORCE 拦截：" + detail
                        + "。这是预期；SHADOW/OFF 才会真正写。P1 不会为被拦的写产生 unexplained。");
            }
            return fail(src, "setHealth(" + health + ") 未生效：" + detail
                    + "。GuardedEntity 会拒绝增加血量/低于自身 floor 的写入；0 血实体也不能靠 setHealth 复活。"
                    + "换一个低于当前血量的值，或先把 test 恢复/重新召唤。P1 不会为未生效的写产生 unexplained。");
        }
        String msg = "setHealth(" + health + ") 已生效：" + detail
                + "。这是测试刺激：P1 应在下一 tick 报 UNEXPLAINED_HEALTH；P1 不会阻止它。";
        if (result.rejected() + result.dead() + result.unchanged() > 0) {
            msg += "（未生效的实体不会产生 unexplained）";
        }
        return ok(src, msg);
    }

    private static int pokeGuarded(CommandSourceStack src, String roleId, float health) {
        HealthTraceData.Role role = parseRole(roleId);
        if (role == null) {
            return fail(src, "角色只能是 control 或 test（收到 " + roleId + "）");
        }
        if (!HealthTrace.active()) {
            return fail(src, "先 /ga health start；poke_guarded 只在记录中有效（stop 会清空 ledger）。");
        }
        HealthTrace.PokeResult result = HealthTrace.pokeGuarded(src.getServer(), role, health);
        if (result.total() <= 0) {
            return fail(src, "没有可写的 " + roleId + " 实体（未追踪/未加载）。");
        }
        String detail = "total=" + result.total()
                + " accepted=" + result.accepted()
                + " rejected=" + result.rejected()
                + " dead=" + result.dead()
                + " unchanged=" + result.unchanged()
                + " guardBlocked=" + result.guardBlocked();
        if (result.accepted() <= 0) {
            if (result.guardBlocked() > 0) {
                return fail(src, "直写 guardedData 被 P2 Guard ENFORCE 拦截：" + detail
                        + "。这是预期；SHADOW/OFF 才会真正写。P1 不会为被拦的写产生 unexplained。");
            }
            return fail(src, "直写 guardedData 未生效：" + detail
                    + "。目标不是 GuardedEntity、已经 0 血，或被其自身逻辑拒绝；P1 不会为未生效的写产生 unexplained。");
        }
        return ok(src, "已绕过 GuardedEntity.setHealth 直写 guardedData：" + detail
                + "。这是模拟外部直接写，不是 Guard；P1 应在下一 tick 报 UNEXPLAINED_HEALTH。");
    }

    private static int discard(CommandSourceStack src, String roleId) {
        HealthTraceData.Role role = parseRole(roleId);
        if (role == null) {
            return fail(src, "角色只能是 control 或 test（收到 " + roleId + "）");
        }
        if (!HealthTrace.active()) {
            return fail(src, "先 /ga health start；discard 只在记录中有效。");
        }
        HealthTrace.DiscardResult result = HealthTrace.discard(src.getServer(), role);
        if (result.total() <= 0) {
            return fail(src, "没有可清除的 " + roleId + " 实体（未追踪/未加载）。");
        }
        if (result.removed() <= 0) {
            if (result.guardBlocked() > 0) {
                return fail(src, "discard 被 P2 Guard 拦截：total=" + result.total()
                        + " removed=0 blocked=" + result.blocked()
                        + " guardBlocked=" + result.guardBlocked()
                        + "。ENFORCE 下这是预期行为；SHADOW/OFF 才会真的移除。");
            }
            return fail(src, "discard 未移除：total=" + result.total()
                    + " removed=0 blocked=" + result.blocked()
                    + " guardBlocked=0。这不是 P2 Guard——目标自己重写/拒绝了 discard/remove"
                    + "（已记录 discard_attempt）。");
        }
        return ok(src, "已 discard：" + result.removed() + " 个移除，"
                + result.blocked() + " 个未移除（其中 Guard 拦截 " + result.guardBlocked() + "；total="
                + result.total() + "）。移除了的会记录 remove + removed_alive + client_remove。");
    }

    private static int guard(CommandSourceStack src, String roleId, String modeId) {
        HealthTraceData.Role role = parseRole(roleId);
        if (role == null) {
            return fail(src, "角色只能是 control 或 test（收到 " + roleId + "）");
        }
        HealthTraceData.GuardMode mode = HealthTraceData.GuardMode.byIdOrNull(modeId);
        if (mode == null) {
            return fail(src, "Guard 档位只能是 off / shadow / enforce（收到 " + modeId + "）");
        }
        if (role == HealthTraceData.Role.CONTROL && mode != HealthTraceData.GuardMode.OFF) {
            return fail(src, "control 永远 OFF；Guard 只能挂在 test 上。");
        }
        int n = HealthTrace.setGuardMode(src.getServer(), role, mode);
        if (n < 0) {
            return fail(src, "拿不到存档级追踪数据。");
        }
        if (n == 0) {
            return fail(src, "没有 " + roleId + " 实体可设置 Guard。");
        }
        return ok(src, "guard " + roleId + " = " + mode.id() + "（" + n + " 个实体）。"
                + "OFF=不拦；SHADOW=记 would_block；ENFORCE=拦截/回滚。");
    }

    private static int guardAllOff(CommandSourceStack src) {
        int n = HealthTrace.guardAllOff(src.getServer());
        if (n < 0) {
            return fail(src, "拿不到存档级追踪数据。");
        }
        return ok(src, "全部 Guard 已复位 OFF（改了 " + n + " 个实体）。");
    }

    private static int reflect(CommandSourceStack src, String roleId, float health) {
        HealthTraceData.Role role = parseRole(roleId);
        if (role == null) {
            return fail(src, "角色只能是 control 或 test（收到 " + roleId + "）");
        }
        if (!HealthTrace.active()) {
            return fail(src, "先 /ga health start；reflect 只在记录中有效。");
        }
        HealthTrace.ReflectResult result = HealthTrace.reflect(src.getServer(), role, health);
        String detail = "total=" + result.total() + " changed=" + result.changed()
                + " failed=" + result.failed()
                + (result.error().isEmpty() ? "" : " error=" + result.error());
        if (result.changed() <= 0) {
            return fail(src, "反射真血未生效：" + detail);
        }
        return ok(src, "已反射直写真血：" + detail
                + "。P1 应在下一 tick 报 unexplained；P2 SHADOW=would_restore，ENFORCE=restored。");
    }

    private static int report(CommandSourceStack src) {
        HealthTrace.printReport(src);
        Path summary = HealthTrace.writeSummaryNow();
        return ok(src, summary == null ? "摘要落盘失败（详见日志）。" : "摘要已写入 " + summary);
    }

    private static int reset(CommandSourceStack src) {
        if (HealthTrace.active()) {
            return fail(src, "正在记录中，先 /ga health stop。");
        }
        HealthTraceData data = data(src);
        if (data == null) {
            return fail(src, "拿不到存档级追踪数据。");
        }
        int n = data.clearTracked();
        data.trackAll = false;
        data.sampleEvery = 256;
        data.logGetHealth = true;
        data.ledgerEnabled = true;
        data.setDirty();
        HealthTrace.onClearTracked();
        HealthTrace.clearStats();
        return ok(src, "已复位：清掉 " + n + " 个追踪实体，统计归零。");
    }

    // ------------------------------------------------------------------ 小工具

    private static HealthTraceData data(CommandSourceStack src) {
        return HealthTraceData.get(src.getServer());
    }

    private static HealthTraceData.Role parseRole(String id) {
        HealthTraceData.Role role = HealthTraceData.Role.byId(id);
        if (role != HealthTraceData.Role.CONTROL && role != HealthTraceData.Role.TEST) {
            return null;
        }
        return role;
    }

    private static String roleArg(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "role");
    }

    private static LivingEntity resolveLook(CommandSourceStack src, double range) {
        if (src.getEntity() instanceof Player player) {
            HitResult hit = player.pick(range, 0.0F, false);
            if (hit instanceof EntityHitResult ehr && ehr.getEntity() instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }

    /** 附近生物，按距离排序；不含自己、已移除、旁观者。 */
    private static List<LivingEntity> nearby(Player player, double radius) {
        AABB box = player.getBoundingBox().inflate(radius);
        List<LivingEntity> list = new ArrayList<>(player.level().getEntitiesOfClass(
                LivingEntity.class, box,
                e -> e != player && !e.isRemoved() && e.isAlive()
                        && !(e instanceof Player p && p.isSpectator())));
        list.sort(Comparator.comparingDouble(e -> e.distanceToSqr(player)));
        return list;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static int ok(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal("[GA-Health] " + msg), false);
        return 1;
    }

    private static int fail(CommandSourceStack src, String msg) {
        src.sendFailure(Component.literal("[GA-Health] " + msg));
        return 0;
    }

    private HealthTraceCommand() {
    }
}
