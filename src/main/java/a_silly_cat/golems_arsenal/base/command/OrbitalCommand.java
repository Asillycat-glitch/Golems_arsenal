package a_silly_cat.golems_arsenal.base.command;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.base.entity.GuidanceBeamEntity;
import a_silly_cat.golems_arsenal.base.entity.OrbitalBeamEntity;
import a_silly_cat.golems_arsenal.base.entity.OrbitalProjectileEntity;
import dev.xkmc.l2library.init.events.GeneralEventHandler;
import a_silly_cat.golems_arsenal.tech.mekanism.OrbitalImpact;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Collection;
import java.util.List;

/**
 * 天基激光的调试/管理命令：{@code /golems_arsenal orbital <目标> [伤害]}。
 *
 * <p><b>为什么第 1 刀先做命令。</b>光束渲染、MEK 激光伤害、"击杀不归属"这三件事是本设计里最容易
 * 做错的部分，而它们全都不依赖"在轨状态"。先用命令把这三件事在游戏里验证掉，再叠引导升级与发射台，
 * 出问题时能立刻分清是渲染错了还是玩法逻辑错了。
 *
 * <p>落点取目标脚下的地表（{@code MOTION_BLOCKING} 高度图），而不是目标当前 Y ——
 * 这样飞行中的目标也会看到光束落在地面，符合"天基打击"的观感。
 *
 * <p>命令需要 OP（{@code requires(s -> s.hasPermission(2))}）。
 */
@Mod.EventBusSubscriber
public final class OrbitalCommand {

    /**
     * **统一尺寸**：瞄准画出来的圈有多大，落点冲击就有多大。
     *
     * <p>用户 2026-10-19 的设定："瞄准过程中画出来的大小就是攻击范围，特效允许宏大到这种地步"。
     * 所以 guide / fire / shot 三个命令的默认半径都取这一个值——调一处，三处一致。
     * 想更宏大就把它改大（24~32 都试得起，几何开销几乎不变）。
     */
    private static final float DEFAULT_STRIKE_RADIUS = 14.0F;

    /** 瞄准圈的缩放：视觉圈比伤害范围**小一点**，避免"圈太大、炸太小"的观感。 */
    private static final float AIM_VISUAL_SCALE = 0.75F;
    /** 伤害范围的缩放：实际打击半径比画出来的圈**大一点**。 */
    private static final float DAMAGE_RADIUS_SCALE = 1.15F;

    /** 弹体相对光柱宽度的倍数（用户要求弹体"比瞄准光柱宽，更显眼"）。 */
    private static final float PROJECTILE_WIDTH_FACTOR = 3.6F;

    public static final List<String> ALIASES = List.of("golems_arsenal", "ga");

    /**
     * 三个特效调试子命令，**和 orbital 平级**挂在 /ga 下：
     * <pre>
     *   /ga guide [半径]   引导 + 聚合瞄准（六条光柱）
     *   /ga fire  [半径]   发射段落点特效（冲击环 + 白热球 + 碎屑）
     *   /ga sigil [半径]   法阵（渲染示例）
     * </pre>
     *
     * <p>2026-10-19 从 {@code orbital} 下面提出来的：之前它们是
     * `/ga orbital fire`，用户照着 `/ga fire` 打自然找不到。
     */
    private static LiteralArgumentBuilder<CommandSourceStack> cmdGuide() {
        return Commands.literal("guide")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> guide(ctx.getSource(), DEFAULT_STRIKE_RADIUS))
                .then(Commands.argument("radius", FloatArgumentType.floatArg(1.0F, 24.0F))
                        .executes(ctx -> guide(ctx.getSource(),
                                FloatArgumentType.getFloat(ctx, "radius"))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> cmdFire() {
        return Commands.literal("fire")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> fireFx(ctx.getSource(), DEFAULT_STRIKE_RADIUS))
                .then(Commands.argument("radius", FloatArgumentType.floatArg(1.0F, 24.0F))
                        .executes(ctx -> fireFx(ctx.getSource(),
                                FloatArgumentType.getFloat(ctx, "radius"))));
    }

    /**
     * `/ga full [半径]` —— **全流程**：引导瞄准 → 聚合 → 发射弹体 → 落地冲击。
     *
     * <p>这是收尾测试用的"一条命令走完整个技能"。时序：
     * <pre>
     *   t=0              GuidanceBeamEntity（六条光柱，50 tick ≈ 2.5 秒）
     *   t=TOTAL_TICKS    弹体从高空射下 + 伤害光束（不可见）
     *   弹体落地           OrbitalImpactEntity（球 / 环 / 火星 / 热痕）
     * </pre>
     *
     * <p><b>为什么伤害光束要隐藏</b>：炮的主伤害是落地那一下的冲击波，但结算逻辑仍在
     * {@link OrbitalBeamEntity}（它负责冲击伤 + 余热）。视觉已经由弹体和落点特效承担，
     * 所以这里把光束实体设为 invisible——逻辑照跑，画面不重复。
     */
    private static LiteralArgumentBuilder<CommandSourceStack> cmdFull() {
        return Commands.literal("full")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> full(ctx.getSource(), DEFAULT_STRIKE_RADIUS))
                .then(Commands.argument("radius", FloatArgumentType.floatArg(1.0F, 32.0F))
                        .executes(ctx -> full(ctx.getSource(),
                                FloatArgumentType.getFloat(ctx, "radius"))));
    }

    private static int full(CommandSourceStack source, float radius) {
        ServerLevel level = source.getLevel();
        Vec3 pos = source.getPosition();
        float alt = Config.ORBITAL_BEAM_ALTITUDE.get().floatValue();
        float width = Config.ORBITAL_BEAM_WIDTH.get().floatValue();

        // ① 引导 + 聚合瞄准
        GuidanceBeamEntity.create(level, pos, radius * AIM_VISUAL_SCALE, alt, width * 0.5F);

        // ② 瞄准结束的下一 tick：发射
        // 提前 10 tick（0.5 秒）发射：让弹体的下降和瞄准的收束**重叠**，
        // 而不是"瞄准结束→空白 0.6 秒→弹体出现"。弹体更亮更宽，重叠时能凸显出来。
        final long due = level.getGameTime() + GuidanceBeamEntity.TOTAL_TICKS - 10;
        GeneralEventHandler.schedulePersistent(() -> {
            if (level.getGameTime() < due) {
                return false;
            }
            // 起点压到 140 格：瞄准收束后弹体只下落约 12 tick，
            // 否则从 300 格落下会有 1 秒多的"空窗期"，衔接不上。
            OrbitalProjectileEntity.fire(level, pos, Math.min(alt, 140.0F), radius,
                    width * PROJECTILE_WIDTH_FACTOR);
            // 基础伤害 = 20 + 攻击者攻击力；灼烧固定 1 秒（20 tick）
            float attack = 0.0F;
            if (source.getEntity() instanceof net.minecraft.world.entity.LivingEntity le) {
                attack = (float) le.getAttributeValue(
                        net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
            }
            OrbitalBeamEntity beam = OrbitalBeamEntity.fire(level, pos,
                    20.0F + attack,
                    Config.ORBITAL_BEAM_RESIDUAL_DAMAGE.get().floatValue(),
                    radius * DAMAGE_RADIUS_SCALE, 20, width, null, alt);
            beam.setInvisible(true);
            return true;
        });

        source.sendSuccess(() -> Component.literal(
                "全流程开始：瞄准 " + (GuidanceBeamEntity.TOTAL_TICKS / 20.0F)
                        + " 秒 → 发射 → 落地冲击（半径 " + radius + " 格）"), true);
        return 1;
    }

    /** `/ga shot [半径]` —— 只发射弹（白热弹体 + 红热拖尾 + 落地冲击）。 */
    private static LiteralArgumentBuilder<CommandSourceStack> cmdShot() {
        return Commands.literal("shot")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> shot(ctx.getSource(), DEFAULT_STRIKE_RADIUS))
                .then(Commands.argument("radius", FloatArgumentType.floatArg(1.0F, 32.0F))
                        .executes(ctx -> shot(ctx.getSource(),
                                FloatArgumentType.getFloat(ctx, "radius"))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> cmdSigil() {
        return Commands.literal("sigil")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> sigil(ctx.getSource(), 4.0F))
                .then(Commands.argument("radius", FloatArgumentType.floatArg(1.0F, 24.0F))
                        .executes(ctx -> sigil(ctx.getSource(),
                                FloatArgumentType.getFloat(ctx, "radius"))));
    }


    /**
     * 在命令执行者脚下开始一次引导（测试用，纯视觉）。
     *
     * <p>单独一个子命令而不是塞进 {@code orbital}：引导光是纯视觉，
     * 不需要目标实体，调试时想随手看一次就够。
     */
    private static int guide(CommandSourceStack source, float radius) {
        ServerLevel level = source.getLevel();
        Vec3 pos = source.getPosition();
        float alt = Config.ORBITAL_BEAM_ALTITUDE.get().floatValue();
        float width = Config.ORBITAL_BEAM_WIDTH.get().floatValue() * 0.5F;
        a_silly_cat.golems_arsenal.base.entity.GuidanceBeamEntity.create(level, pos, radius, alt, width);
        source.sendSuccess(() -> Component.literal(
                "引导光束已生成：半径 " + radius + " 格，时长 "
                        + a_silly_cat.golems_arsenal.base.entity.GuidanceBeamEntity.TOTAL_TICKS
                        + " tick"), true);
        return 1;
    }

    /**
     * 在命令执行者脚下画一个法阵（测试用）。
     *
     * <p>法阵和引导光共用同一条轨迹（{@code OrbitalGuidancePath}），
     * 区别是它把轨迹画出来。保留它是当渲染示例用的。
     */
    private static int sigil(CommandSourceStack source, float radius) {
        ServerLevel level = source.getLevel();
        Vec3 pos = source.getPosition();
        a_silly_cat.golems_arsenal.base.entity.OrbitalSigilEntity.create(level, pos, radius);
        source.sendSuccess(() -> Component.literal(
                "法阵已生成：半径 " + radius + " 格，绘制 "
                        + a_silly_cat.golems_arsenal.base.entity.OrbitalSigilEntity.DRAW_TICKS
                        + " tick"), true);
        return 1;
    }

    /**
     * 只放发射段的落点特效（冲击环 + 爆闪），不造成伤害、不破坏方块。
     *
     * <p>用来单独调特效手感；真正的"发射"以后会把这段接在引导之后。
     */
    private static int fireFx(CommandSourceStack source, float radius) {
        ServerLevel level = source.getLevel();
        Vec3 pos = source.getPosition();
        a_silly_cat.golems_arsenal.base.entity.OrbitalImpactEntity.create(level, pos, radius);
        source.sendSuccess(() -> Component.literal(
                "冲击特效已生成：半径 " + radius + " 格，时长 "
                        + a_silly_cat.golems_arsenal.base.entity.OrbitalImpactEntity.LIFE + " tick"), true);
        return 1;
    }

    /**
     * 射出一发白热弹体（调试用）。
     *
     * <p>弹体宽度取配置里的光柱宽度 × {@link #PROJECTILE_WIDTH_FACTOR}——
     * 用户要求"弹体可以大于瞄准激光的宽度，这样射下来更明显"。
     */
    private static int shot(CommandSourceStack source, float radius) {
        ServerLevel level = source.getLevel();
        Vec3 pos = source.getPosition();
        float alt = Config.ORBITAL_BEAM_ALTITUDE.get().floatValue();
        float width = Config.ORBITAL_BEAM_WIDTH.get().floatValue() * PROJECTILE_WIDTH_FACTOR;
        a_silly_cat.golems_arsenal.base.entity.OrbitalProjectileEntity.fire(
                level, pos, alt, radius, width);
        source.sendSuccess(() -> Component.literal(
                "白热弹体已发射：宽度 " + width + " 格，落地冲击半径 " + radius + " 格"), true);
        return 1;
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        for (String alias : ALIASES) {
            event.getDispatcher().register(
                    Commands.literal(alias)
                            .then(cmdGuide())     // guide [半径]
                            .then(cmdFire())      // fire  [半径]
                            .then(cmdSigil())     // sigil [半径]
                            .then(cmdShot())      // shot  [半径]
                            .then(cmdFull()));    // full  [半径] → 全流程
        }
    }

    private OrbitalCommand() {
    }
}
