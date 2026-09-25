package a_silly_cat.golems_arsenal.compat.l2damagetracker;

import a_silly_cat.golems_arsenal.tech.item.GolemZeroSwordItem;
import dev.xkmc.l2damagetracker.contents.attack.AttackEventHandler;
import dev.xkmc.l2damagetracker.contents.attack.AttackListener;
import dev.xkmc.l2damagetracker.contents.attack.CreateSourceEvent;
import dev.xkmc.l2damagetracker.contents.damage.DefaultDamageState;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;

/**
 * Z 光剑的"近战攻击穿透护甲"：和傀儡地牢的遗迹锻锤（{@code AncientForge}）走同一条路 ——
 * l2damagetracker 在构造伤害源时会发出 {@link CreateSourceEvent}，在这里给它打开
 * {@link DefaultDamageState#BYPASS_ARMOR} 即可让这次近战无视护甲。
 */
public final class ZeroSwordAttackListener implements AttackListener {

    /** 监听器优先级：傀儡地牢用 3513，我们错开一点，互不覆盖。 */
    private static final int PRIORITY = 3400;

    public static void register() {
        AttackEventHandler.register(PRIORITY, new ZeroSwordAttackListener());
    }

    @Override
    public void onCreateSource(CreateSourceEvent event) {
        if (!(event.getAttacker() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        // 只认近战：弹射物（箭矢/枪械）的直接来源不是傀儡本身。
        if (event.getDirect() != null && event.getDirect() != golem) {
            return;
        }
        if (!(golem.getMainHandItem().getItem() instanceof GolemZeroSwordItem)) {
            return;
        }
        event.enable(DefaultDamageState.BYPASS_ARMOR);
    }
}
