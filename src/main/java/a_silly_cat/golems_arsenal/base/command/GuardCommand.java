package a_silly_cat.golems_arsenal.base.command;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.compat.artifact.DamageAdmission;
import a_silly_cat.golems_arsenal.guard.GuardRuntime;
import a_silly_cat.golems_arsenal.guard.HealthLedger;
import a_silly_cat.golems_arsenal.guard.Selftest;
import a_silly_cat.golems_arsenal.guard.WindowGuard;
import a_silly_cat.golems_arsenal.guard.combat.CombatAdmission;
import a_silly_cat.golems_arsenal.guard.combat.CombatMode;
import a_silly_cat.golems_arsenal.guard.sanctuary.RemovalGuard;
import a_silly_cat.golems_arsenal.guard.sanctuary.Projection;
import a_silly_cat.golems_arsenal.guard.sanctuary.ProjectionMode;
import a_silly_cat.golems_arsenal.guard.sanctuary.Sanctuary;
import a_silly_cat.golems_arsenal.guard.sanctuary.SanctuaryMode;
import com.mojang.brigadier.arguments.FloatArgumentType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * {@code /ga guard status}：只读的 Guard 运行时状态。
 *
 * <p>状态由 {@link GuardRuntime} 在 {@code ModConfigEvent.Loading/Reloading} 时重算，
 * 不在命令里直接读配置，避免命令看到过期缓存。</p>
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID)
public final class GuardCommand {

    private GuardCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ga")
                .then(Commands.literal("guard")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("status")
                                .executes(ctx -> status(ctx.getSource())))
                        .then(Commands.literal("refresh")
                                .executes(ctx -> refresh(ctx.getSource())))
                        .then(Commands.literal("strict")
                                .executes(ctx -> strict(ctx.getSource()))
                                .then(Commands.literal("on")
                                        .executes(ctx -> strictValue(ctx.getSource(), Boolean.TRUE)))
                                .then(Commands.literal("off")
                                        .executes(ctx -> strictValue(ctx.getSource(), Boolean.FALSE)))
                                .then(Commands.literal("config")
                                        .executes(ctx -> strictValue(ctx.getSource(), null))))
                        .then(Commands.literal("combat")
                                .executes(ctx -> combat(ctx.getSource()))
                                .then(Commands.literal("off")
                                        .executes(ctx -> combatValue(ctx.getSource(), CombatMode.OFF)))
                                .then(Commands.literal("shadow")
                                        .executes(ctx -> combatValue(ctx.getSource(), CombatMode.SHADOW)))
                                .then(Commands.literal("enforce")
                                        .executes(ctx -> combatValue(ctx.getSource(), CombatMode.ENFORCE)))
                                .then(Commands.literal("config")
                                        .executes(ctx -> combatValue(ctx.getSource(), null))))
                        .then(Commands.literal("sanctuary")
                                .executes(ctx -> sanctuary(ctx.getSource()))
                                .then(Commands.literal("off")
                                        .executes(ctx -> sanctuaryValue(ctx.getSource(), SanctuaryMode.OFF)))
                                .then(Commands.literal("shadow")
                                        .executes(ctx -> sanctuaryValue(ctx.getSource(), SanctuaryMode.SHADOW)))
                                .then(Commands.literal("enforce")
                                        .executes(ctx -> sanctuaryValue(ctx.getSource(), SanctuaryMode.ENFORCE)))
                                .then(Commands.literal("config")
                                        .executes(ctx -> sanctuaryValue(ctx.getSource(), null)))
                                .then(Commands.literal("clear")
                                        .executes(ctx -> sanctuaryClear(ctx.getSource()))))
                        .then(Commands.literal("removal")
                                .executes(ctx -> removal(ctx.getSource()))
                                .then(Commands.literal("off")
                                        .executes(ctx -> removalValue(ctx.getSource(), SanctuaryMode.OFF)))
                                .then(Commands.literal("shadow")
                                        .executes(ctx -> removalValue(ctx.getSource(), SanctuaryMode.SHADOW)))
                                .then(Commands.literal("enforce")
                                        .executes(ctx -> removalValue(ctx.getSource(), SanctuaryMode.ENFORCE))))
                        .then(Commands.literal("identity")
                                .executes(ctx -> identity(ctx.getSource())))
                        .then(Commands.literal("projection")
                                .executes(ctx -> projection(ctx.getSource()))
                                .then(Commands.literal("sealed")
                                        .executes(ctx -> projectionValue(ctx.getSource(), ProjectionMode.SEALED)))
                                .then(Commands.literal("normal")
                                        .executes(ctx -> projectionValue(ctx.getSource(), ProjectionMode.NORMAL)))
                                .then(Commands.literal("config")
                                        .executes(ctx -> projectionValue(ctx.getSource(), null))))
                        .then(Commands.literal("probe")
                                .then(Commands.argument("type", ResourceLocationArgument.id())
                                        .executes(ctx -> probe(ctx.getSource(),
                                                ResourceLocationArgument.getId(ctx, "type"), 10.0F))
                                        .then(Commands.argument("amount", FloatArgumentType.floatArg(0.0F, 1.0E9F))
                                                .executes(ctx -> probe(ctx.getSource(),
                                                        ResourceLocationArgument.getId(ctx, "type"),
                                                        FloatArgumentType.getFloat(ctx, "amount"))))))
                        .then(Commands.literal("toaru")
                                .executes(ctx -> toaru(ctx.getSource())))
                        .then(Commands.literal("window")
                                .executes(ctx -> window(ctx.getSource()))
                                .then(Commands.literal("on")
                                        .executes(ctx -> windowValue(ctx.getSource(), Boolean.TRUE)))
                                .then(Commands.literal("off")
                                        .executes(ctx -> windowValue(ctx.getSource(), Boolean.FALSE)))
                                .then(Commands.literal("config")
                                        .executes(ctx -> windowValue(ctx.getSource(), null))))
                        .then(Commands.literal("audit")
                                .then(Commands.literal("status")
                                        .executes(ctx -> auditStatus(ctx.getSource())))
                                .then(Commands.literal("start")
                                        .executes(ctx -> auditEnabled(ctx.getSource(), true)))
                                .then(Commands.literal("stop")
                                        .executes(ctx -> auditEnabled(ctx.getSource(), false)))
                                .then(Commands.literal("clear")
                                        .executes(ctx -> auditClear(ctx.getSource()))))
                        .then(Commands.literal("selftest")
                                .then(Commands.literal("start")
                                        .executes(ctx -> selftestStart(ctx.getSource())))
                                .then(Commands.literal("stop")
                                        .executes(ctx -> selftestStop(ctx.getSource())))
                                .then(Commands.literal("status")
                                        .executes(ctx -> selftestStatus(ctx.getSource()))))
                        .then(Commands.literal("dump")
                                .executes(ctx -> dump(ctx.getSource(), 32.0D))
                                .then(Commands.argument("radius", FloatArgumentType.floatArg(1.0F, 512.0F))
                                        .executes(ctx -> dump(ctx.getSource(),
                                                FloatArgumentType.getFloat(ctx, "radius")))))
                        .then(Commands.literal("watch")
                                .executes(ctx -> watch(ctx.getSource()))
                                .then(Commands.literal("on")
                                        .executes(ctx -> watchSet(ctx.getSource(), true)))
                                .then(Commands.literal("off")
                                        .executes(ctx -> watchSet(ctx.getSource(), false)))
                                .then(Commands.literal("reset")
                                        .executes(ctx -> watchReset(ctx.getSource()))))
                        .then(Commands.literal("exempt")
                                .then(Commands.literal("add")
                                        .then(Commands.argument("uuid", com.mojang.brigadier.arguments.StringArgumentType.string())
                                                .executes(ctx -> exemptSet(ctx.getSource(),
                                                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "uuid"), true))))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("uuid", com.mojang.brigadier.arguments.StringArgumentType.string())
                                                .executes(ctx -> exemptSet(ctx.getSource(),
                                                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "uuid"), false)))))
                        .then(Commands.literal("disk")
                                .executes(ctx -> disk(ctx.getSource()))
                                .then(Commands.literal("verify")
                                        .executes(ctx -> diskVerify(ctx.getSource()))))));
    }

    private static int exemptSet(CommandSourceStack source, String raw, boolean exempt) {
        try {
            java.util.UUID uuid = java.util.UUID.fromString(raw);
            a_silly_cat.golems_arsenal.guard.sanctuary.AgentBootstrap.setExempt(uuid, exempt);
            source.sendSuccess(() -> Component.literal("[sanctuary] exempt " + uuid + " = " + exempt), false);
        } catch (Throwable t) {
            source.sendFailure(Component.literal("bad uuid: " + raw));
        }
        return 1;
    }

    private static int disk(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                a_silly_cat.golems_arsenal.guard.sanctuary.DiskShield.statusLine()), false);
        return 1;
    }

    private static int diskVerify(CommandSourceStack source) {
        String line = a_silly_cat.golems_arsenal.guard.sanctuary.DiskShield.verifyNow();
        Golems_arsenal.LOGGER.info("{}", line);
        source.sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static int watch(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                a_silly_cat.golems_arsenal.guard.sanctuary.DataGuard.statusLine()
                        + " " + a_silly_cat.golems_arsenal.guard.sanctuary.ReadShield.statusLine()
                        + " " + a_silly_cat.golems_arsenal.guard.sanctuary.Watchdog.statusLine()
                        + " " + a_silly_cat.golems_arsenal.guard.sanctuary.IndexGuard.statusLine()
                        + " " + a_silly_cat.golems_arsenal.guard.sanctuary.IdentityGuard.statusLine()), false);
        return 1;
    }

    private static int watchSet(CommandSourceStack source, boolean on) {
        a_silly_cat.golems_arsenal.guard.sanctuary.DataGuard.setEnabled(on);
        return watch(source);
    }

    private static int watchReset(CommandSourceStack source) {
        a_silly_cat.golems_arsenal.guard.sanctuary.DataGuard.reset();
        return watch(source);
    }

    /**
     * {@code /ga guard dump [radius]}：把半径内受保护傀儡的**三个数据面**打成一行一条。
     *
     * <p>打法：dump → 用武器 → dump → diff。血量/全部 DataItem/坐标都能机械比对，
     * 不靠肉眼判断"变没变"。</p>
     */
    private static int dump(CommandSourceStack source, double radius) {
        var player = source.getPlayer();
        var level = source.getLevel();
        var center = player == null
                ? new net.minecraft.world.phys.Vec3(source.getPosition().x, source.getPosition().y, source.getPosition().z)
                : player.position();
        List<String> lines = a_silly_cat.golems_arsenal.guard.Dump.snapshot(level, center, radius);
        Golems_arsenal.LOGGER.info("[Sanctuary] dump begin radius={} count={}", radius, lines.size());
        for (String line : lines) {
            Golems_arsenal.LOGGER.info("[Sanctuary] {}", line);
            source.sendSuccess(() -> Component.literal(line), false);
        }
        Golems_arsenal.LOGGER.info("[Sanctuary] dump end");
        source.sendSuccess(() -> Component.literal("dump: " + lines.size() + " golem(s), radius=" + radius), false);
        return lines.size();
    }

    private static int status(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(GuardRuntime.statusLine()), false);
        return 1;
    }

    private static int refresh(CommandSourceStack source) {
        GuardRuntime.refresh();
        source.sendSuccess(() -> Component.literal("guard refreshed: " + GuardRuntime.statusLine()), false);
        return 1;
    }

    private static int auditStatus(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(HealthLedger.statusLine()), false);
        return 1;
    }

    private static int strict(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("[guard] " + DamageAdmission.strictStatus()), false);
        return 1;
    }

    private static int strictValue(CommandSourceStack source, Boolean value) {
        DamageAdmission.setStrictOverride(value);
        source.sendSuccess(() -> Component.literal("[guard] " + DamageAdmission.strictStatus()), false);
        return 1;
    }

    private static int window(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("[guard] " + WindowGuard.status()), false);
        return 1;
    }

    private static int windowValue(CommandSourceStack source, Boolean value) {
        WindowGuard.setOverride(value);
        source.sendSuccess(() -> Component.literal("[guard] " + WindowGuard.status()), false);
        return 1;
    }

    private static int combat(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("[guard] " + CombatAdmission.status()), false);
        return 1;
    }

    private static int combatValue(CommandSourceStack source, CombatMode value) {
        CombatAdmission.setOverride(value);
        source.sendSuccess(() -> Component.literal("[guard] " + CombatAdmission.status()), false);
        return 1;
    }

    private static int sanctuary(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(Sanctuary.statusLine()), false);
        return 1;
    }

    private static int sanctuaryValue(CommandSourceStack source, SanctuaryMode value) {
        Sanctuary.setOverride(value);
        source.sendSuccess(() -> Component.literal(Sanctuary.statusLine()), false);
        return 1;
    }

    private static int sanctuaryClear(CommandSourceStack source) {
        Sanctuary.clearCounters();
        source.sendSuccess(() -> Component.literal(Sanctuary.statusLine()), false);
        return 1;
    }

    private static int removal(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(RemovalGuard.statusLine()), false);
        return 1;
    }

    private static int removalValue(CommandSourceStack source, SanctuaryMode value) {
        RemovalGuard.setMode(value);
        source.sendSuccess(() -> Component.literal(RemovalGuard.statusLine()), false);
        return 1;
    }

    private static int identity(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                a_silly_cat.golems_arsenal.guard.sanctuary.IdentityGuard.statusLine()
                        + " " + a_silly_cat.golems_arsenal.guard.sanctuary.IndexGuard.statusLine()
                        + " " + a_silly_cat.golems_arsenal.guard.sanctuary.Watchdog.statusLine()
                        + " " + a_silly_cat.golems_arsenal.guard.sanctuary.AgentBridge.agentStatusLine()
                        + " " + RemovalGuard.statusLine()), false);
        return 1;
    }

    private static int projection(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "[sanctuary] " + Projection.statusLine()
                        + " sanctuary=" + Sanctuary.mode()
                        + " removal=" + RemovalGuard.mode()), false);
        return 1;
    }

    private static int projectionValue(CommandSourceStack source, ProjectionMode value) {
        Projection.setOverride(value);
        source.sendSuccess(() -> Component.literal(
                "[sanctuary] " + Projection.statusLine()
                        + " (damage/interact/drops and AI freeze " + (value == ProjectionMode.NORMAL ? "released" : "active") + ")"),
                false);
        return 1;
    }

    /**
     * 测试刺激：对最近的玩家傀儡打一发指定伤害类型。
     *
     * <p>用它是为了在没有“另一个玩家/能打友军的模组”时也能验证判定：伤害源不带攻击者，
     * 所以 {@code toaru:gungnir} 这种会落到 {@code OPERATION}，普通类型会落到
     * {@code UNATTRIBUTED}。看 {@code verdict=} 和血量变化就知道 ENFORCE 有没有生效。</p>
     */
    private static int probe(CommandSourceStack source, ResourceLocation type, float amount) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("probe 需要玩家执行"));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        List<AbstractGolemEntity> found = level.getEntitiesOfClass(
                AbstractGolemEntity.class,
                player.getBoundingBox().inflate(16.0D),
                golem -> golem.getOwnerUUID() != null);
        if (found.isEmpty()) {
            source.sendFailure(Component.literal("probe: 16 格内没有玩家傀儡"));
            return 0;
        }
        AbstractGolemEntity<?, ?> golem = found.get(0);
        ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE, type);
        var registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        if (!registry.containsKey(key)) {
            source.sendFailure(Component.literal("probe: 未知伤害类型 " + type));
            return 0;
        }
        DamageSource damage = new DamageSource(registry.getHolderOrThrow(key));
        float before = DamageAdmission.rawHealth(golem);
        boolean hurt = golem.hurt(damage, amount);
        float after = DamageAdmission.rawHealth(golem);
        String verdict = Sanctuary.explain(golem, damage);
        source.sendSuccess(() -> Component.literal("[probe] type=" + type
                + " amount=" + amount
                + " hurt=" + hurt
                + " raw " + before + " -> " + after
                + " " + verdict), false);
        return 1;
    }

    /**
     * toaru 测试刺激：用 FakePlayer（非主人）当施法者，对最近的玩家傀儡走一遍
     * toaru 自己的 {@code DamageService.strike}。
     *
     * <p>这样既能绕开它的"不打友军"过滤，又不需要第二个真实玩家。</p>
     */
    private static int toaru(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("toaru 测试需要玩家执行"));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        List<AbstractGolemEntity> found = level.getEntitiesOfClass(
                AbstractGolemEntity.class,
                player.getBoundingBox().inflate(16.0D),
                golem -> golem.getOwnerUUID() != null);
        if (found.isEmpty()) {
            source.sendFailure(Component.literal("toaru: 16 格内没有玩家傀儡"));
            return 0;
        }
        AbstractGolemEntity<?, ?> golem = found.get(0);
        String result = a_silly_cat.golems_arsenal.guard.sanctuary.ToaruProbe.strike(level, golem);
        source.sendSuccess(() -> Component.literal("[toaru] " + result), false);
        return 1;
    }

    private static int auditEnabled(CommandSourceStack source, boolean enabled) {        HealthLedger.setEnabled(enabled);
        source.sendSuccess(() -> Component.literal("guard audit " + (enabled ? "started" : "stopped")
                + ": " + HealthLedger.statusLine()), false);
        return 1;
    }

    private static int auditClear(CommandSourceStack source) {
        HealthLedger.clear();
        source.sendSuccess(() -> Component.literal("guard audit cleared: " + HealthLedger.statusLine()), false);
        return 1;
    }

    private static int selftestStart(CommandSourceStack source) {
        if (!(source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            source.sendFailure(Component.literal("selftest 需要玩家执行"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[selftest] mode=" + GuardRuntime.mode()
                + " (只对临时傀儡；不 ENFORCE)"), false);
        for (Selftest.Row row : Selftest.run(player)) {
            source.sendSuccess(() -> Component.literal("[selftest] " + row.format()), false);
        }
        source.sendSuccess(() -> Component.literal("[selftest] done"), false);
        return 1;
    }

    private static int selftestStop(CommandSourceStack source) {
        Selftest.stop();
        source.sendSuccess(() -> Component.literal("selftest stopped; temp golem released"), false);
        return 1;
    }

    private static int selftestStatus(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("selftest running=" + Selftest.isRunning()), false);
        for (Selftest.Row row : Selftest.lastReport()) {
            source.sendSuccess(() -> Component.literal("[selftest] " + row.format()), false);
        }
        return 1;
    }
}
