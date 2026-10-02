package a_silly_cat.golems_arsenal.base.upgrade;

import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/**
 * 光柱护体的"旧独立升级"载体。<b>效果已经移交给 Omega 四件套联动</b>
 * （见 {@code GolemsArsenalSetHandler}：减震 II + 节约能源 II + E 罐 + W 罐齐套后，
 * 受击 → 4 道光柱 2 秒、冷却 5 秒、期间无敌）。
 * <p>
 * 这个 modifier 与对应的物品 id（{@code golems_arsenal:light_pillar_upgrade}）仍然保留注册，
 * 纯粹是为了避免老存档里已有的物品变成 AirItem 而崩；它<b>不再有任何主动效果</b>，
 * 也不再出现在创造栏里、没有任何配方。本类不会注册任何 forge 事件监听。
 * <p>
 * ⚠️ 下面那两个数值常量只用于拼 tooltip 文案（老存档玩家悬浮查看时还能看到），
 * <b>不代表当前实际效果</b>：真正生效的是 Omega 联动的 2 秒 / <b>5 秒</b>冷却
 * （见 {@code GolemsArsenalSetHandler} 的 {@code PILLAR_CD_TICKS = 100}）。
 * 这个 30 秒是独立升级时期的遗留值，保留原样以免改动老存档的展示。
 */
public class GolemLightPillarModifier extends GolemModifier {
    public static final int MAX_LEVEL = 1;

    /** 仅用于 tooltip：旧独立升级时期的持续时长（tick）。 */
    private static final int ACTIVE_TICKS = 40;
    /** 仅用于 tooltip：旧独立升级时期的冷却（tick，30 秒）；Omega 联动实际是 5 秒。 */
    private static final int COOLDOWN_TICKS = 600;

    public GolemLightPillarModifier() {
        super(StatFilterType.HEALTH, MAX_LEVEL);
    }

    @Override
    public Component getTooltip(int level) {
        return Component.translatable("upgrade.golems_arsenal.light_pillar")
                .withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        return List.of(
                Component.translatable("upgrade.golems_arsenal.light_pillar.desc")
                        .withStyle(ChatFormatting.GREEN),
                Component.translatable("upgrade.golems_arsenal.light_pillar.detail",
                        ACTIVE_TICKS / 20, COOLDOWN_TICKS / 20).withStyle(ChatFormatting.GRAY));
    }

}
