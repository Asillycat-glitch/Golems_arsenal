package a_silly_cat.golems_arsenal.base.upgrade;

import a_silly_cat.golems_arsenal.base.event.GolemComboHandler;
import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;

import java.util.List;

/**
 * 三连击（特技升级）。
 * <p>
 * 效果 = <b>降低攻击速度（拉长攻击间隔）</b> + <b>一次近战攻击接出整段三连击</b>。
 * 代价与收益配套：攻击变慢，换来每一击都带出一套（横扫 / 上挑 / 下劈）范围挥砍。
 * <p>
 * 这个类只负责"起手"：真正的连击推进、每段判定、攻速惩罚的挂载都在
 * {@link GolemComboHandler} 里（每 tick 幂等维护，读档/换装自愈）。
 */
public class GolemComboModifier extends GolemModifier {

    public GolemComboModifier() {
        super(StatFilterType.ATTACK, 1);
    }

    /** 近战攻击发起时起手整段连击（是"发起"，不是"命中"，所以挡下/打空也算）。 */
    @Override
    public void onAttackTarget(AbstractGolemEntity<?, ?> golem, LivingAttackEvent event, int level) {
        GolemComboHandler.onGolemAttack(golem);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        return List.of(
                Component.translatable("upgrade.golems_arsenal.combo.detail",
                        GolemComboHandler.attackIntervalTicks(), GolemComboHandler.LINK_TICKS),
                Component.translatable("upgrade.golems_arsenal.special_move.note"));
    }
}
