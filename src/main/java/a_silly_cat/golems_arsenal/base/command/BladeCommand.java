package a_silly_cat.golems_arsenal.base.command;

import a_silly_cat.golems_arsenal.base.entity.PhantomBladeEntity;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 幻梦零剑气的调试命令：{@code /blade [数量] [散角] [伤害]}。
 * <p>
 * <b>为什么单独做一条命令</b>：正常触发要求「傀儡被命名为 Zero + 主手拿 Z 光剑」，
 * 调特效时不该被这两个前置条件卡住。这条命令直接从玩家眼睛位置、朝视线方向生成剑气，
 * 把"看效果"和"凑触发条件"彻底解耦。
 * <p>
 * 参数都可以省略，层层递进：
 * <pre>
 * /blade                    1 道，朝视线，散角无意义
 * /blade 5                  5 道，散角 14°（和游戏内 FLAT 阵型一致）
 * /blade 5 30               5 道，散角 30°
 * /blade 5 30 100           5 道，散角 30°，单道伤害 100
 * </pre>
 * 伤害为 0 时剑气不会造成任何伤害（{@code damageAlong} 会直接返回），
 * 方便只看特效不看战斗。
 * <p>
 * 需要 OP（{@code hasPermission(2)}）。
 */
@Mod.EventBusSubscriber
public final class BladeCommand {

    /** 默认散角，与 {@code PhantomBladeEntity.FLAT_SPREAD_DEGREES} 一致。 */
    private static final double DEFAULT_SPREAD = 14.0;
    /** 默认单道伤害，够在小怪身上看出命中与否。 */
    private static final float DEFAULT_DAMAGE = 20.0F;

    private BladeCommand() {
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("blade")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> fire(ctx.getSource(), 1, DEFAULT_SPREAD, DEFAULT_DAMAGE))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> fire(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "count"),
                                DEFAULT_SPREAD, DEFAULT_DAMAGE))
                        .then(Commands.argument("spread", DoubleArgumentType.doubleArg(0.0, 180.0))
                                .executes(ctx -> fire(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count"),
                                        DoubleArgumentType.getDouble(ctx, "spread"),
                                        DEFAULT_DAMAGE))
                                .then(Commands.argument("damage", FloatArgumentType.floatArg(0.0F, 10000.0F))
                                        .executes(ctx -> fire(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                DoubleArgumentType.getDouble(ctx, "spread"),
                                                FloatArgumentType.getFloat(ctx, "damage"))))));
    }

    private static int fire(CommandSourceStack source, int count, double spread, float damage) {
        Player player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("这条命令必须由玩家执行（需要眼睛位置与视线方向）。"));
            return 0;
        }
        // 1.20.1 的 CommandSourceStack#getLevel 已经直接返回 ServerLevel，无需再判断
        ServerLevel level = source.getLevel();

        Vec3 from = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        int spawned = PhantomBladeEntity.spawnTest(level, from, look, player, damage, count, spread);

        source.sendSuccess(() -> Component.literal(
                "已生成 " + spawned + " 道剑气（散角 " + spread + "°，单道伤害 " + damage + "）"), true);
        return spawned;
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(build());
    }
}
