package a_silly_cat.golems_arsenal.tech.orbit;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.base.entity.GuidanceBeamEntity;
import a_silly_cat.golems_arsenal.base.entity.OrbitalBeamEntity;
import a_silly_cat.golems_arsenal.base.entity.OrbitalProjectileEntity;
import dev.xkmc.l2library.init.events.GeneralEventHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * 一次完整的**天基激光炮**打击：瞄准 → 弹体 → 落点冲击。
 *
 * <p>这段时序原本写在 {@code OrbitalCommand#full} 里，是 {@code /ga full} 的调试演出。
 * 现在把它抽成唯一实现，**命令与引导升级共用同一条代码路径**——
 * 否则“命令里是一套、升级里是另一套”会长期漂移，调特效时改了一边另一边不动。
 *
 * <p>时序（单位 tick）：
 * <pre>
 *   t = 0                          {@link GuidanceBeamEntity}（六束瞄准，{@link GuidanceBeamEntity#TOTAL_TICKS}）
 *   t = TOTAL_TICKS - AIM_LEAD     {@link OrbitalProjectileEntity} 发射
 *   弹体落地                        {@code OrbitalImpactEntity} + 不可见伤害光束（同 tick 结算）
 * </pre>
 *
 * <p><b>为什么伤害光束要隐藏</b>：炮的主伤害是落点那一下的冲击波，但结算逻辑仍在
 * {@link OrbitalBeamEntity}（它负责冲击伤 + 余热）。视觉已经由弹体和落点特效承担，
 * 所以这里把光束实体设为 invisible——逻辑照跑，画面不重复。
 *
 * <p>本类只做服务端逻辑；客户端看到的是三类实体自己的渲染。
 */
public final class OrbitalStrike {

    /** 瞄准圈的视觉缩放：比实际落点范围小一点，避免“圈太大、炸太小”。 */
    public static final float AIM_VISUAL_SCALE = 0.75F;
    /** 伤害范围的缩放：实际打击半径比画出来的圈大一点。 */
    public static final float DAMAGE_RADIUS_SCALE = 1.15F;
    /** 弹体相对光柱宽度的倍数（弹体要比瞄准光柱宽，才显眼）。 */
    public static final float PROJECTILE_WIDTH_FACTOR = 3.6F;
    /** 提前多少 tick 发射：让弹体下降与瞄准收束重叠，而不是中间空一段。 */
    public static final int AIM_LEAD_TICKS = 10;

    /**
     * 呼叫一次完整的激光炮。
     *
     * @param level            服务端世界
     * @param impact           落点（通常是目标脚下的地表）
     * @param radius           落点冲击 / 瞄准圈的视觉半径（格）
     * @param damage           落地冲击的主伤害
     * @param residualDamage   光束存续期间的每 tick 余热
     * @param width            主光束的可见宽度（格）
     * @param owner            引导者；可为 null（调试或天基武器自主开火）
     * @param altitude         光束来源高度（格）
     * @param protectFriendlies 是否保护引导者的友军（发射台的「友军保护」开关）
     */
    public static void cast(ServerLevel level, Vec3 impact, float radius, float damage,
                            float residualDamage, float width, LivingEntity owner,
                            float altitude, boolean protectFriendlies) {
        if (level == null || impact == null) {
            return;
        }
        float visualRadius = Math.max(1.0F, radius);
        float beamWidth = Math.max(0.05F, width);
        float topY = Math.max(64.0F, altitude);

        // ① 引导 + 聚合瞄准
        GuidanceBeamEntity.create(level, impact, visualRadius * AIM_VISUAL_SCALE,
                topY, beamWidth * 0.5F);

        // ② 瞄准结束前 AIM_LEAD_TICKS：只发射弹体；伤害光束要等弹体真正落地。
        //    之前两者同 tick 生成，光束在 tickCount == 1 就结算伤害，
        //    而弹体还要下落 ~12 tick，所以伤害会先于落点特效约 11 tick 出现。
        final float projectileStartY = Math.min(topY, 140.0F);
        final int flightTicks = Math.max(1,
                (int) Math.floor((projectileStartY - impact.y) / 12.0D) + 1);
        final long due = level.getGameTime() + GuidanceBeamEntity.TOTAL_TICKS - AIM_LEAD_TICKS;
        final long beamDue = due + flightTicks;

        GeneralEventHandler.schedulePersistent(() -> {
            if (level.getGameTime() < due) {
                return false;
            }
            // 起点压到 140 格：瞄准收束后弹体只下落约 12 tick，
            // 否则从 320 格落下会有 1 秒多的“空窗期”，衔接不上。
            OrbitalProjectileEntity.fire(level, impact, projectileStartY,
                    visualRadius, beamWidth * PROJECTILE_WIDTH_FACTOR);
            return true;
        });

        GeneralEventHandler.schedulePersistent(() -> {
            if (level.getGameTime() < beamDue) {
                return false;
            }
            // 落地后 1 tick 生成伤害光束：让落点特效先出现，
            // 下一 tick 的 tickCount == 1 impactBlast() 再结算伤害。
            OrbitalBeamEntity beam = OrbitalBeamEntity.fire(level, impact, damage,
                    residualDamage, visualRadius * DAMAGE_RADIUS_SCALE,
                    Config.ORBITAL_BEAM_LIFE.get(), beamWidth, owner, topY, protectFriendlies);
            beam.setInvisible(true);
            return true;
        });
    }

    private OrbitalStrike() {
    }
}
