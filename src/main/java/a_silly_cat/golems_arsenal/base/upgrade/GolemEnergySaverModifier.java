package a_silly_cat.golems_arsenal.base.upgrade;

import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/**
 * 节约能源（X5 的 Energy Saver）：所有 FE 消耗按等级打折。每级 -25%（两级 -50%），最低消耗 1 FE。
 * <p>
 * 实现上不改任何物品/属性的定义，只提供一个静态打折函数，由各处消耗 FE 的调用点使用。
 */
public class GolemEnergySaverModifier extends GolemModifier {
    public static final int MAX_LEVEL = 2;
    private static final double DISCOUNT_PER_LEVEL = 0.25;

    public GolemEnergySaverModifier() {
        super(StatFilterType.HEALTH, MAX_LEVEL);
    }

    /** 该傀儡身上的打折系数（1.0 = 不打折，0.5 = 半价，最多 -50%）。 */
    public static double factor(AbstractGolemEntity<?, ?> golem) {
        int level = GolemUpgradeLevels.levelOf(golem, "golems_arsenal:energy_saver");
        if (level <= 0) {
            return 1.0;
        }
        return Math.max(0.0, 1.0 - DISCOUNT_PER_LEVEL * level);
    }

    /** 把一次 FE 消耗打折（至少 1 FE；cost <= 0 时原样返回）。 */
    public static int discount(AbstractGolemEntity<?, ?> golem, int cost) {
        if (cost <= 0) {
            return cost;
        }
        return Math.max(1, (int) Math.round(cost * factor(golem)));
    }

    @Override
    public Component getTooltip(int level) {
        return Component.translatable("upgrade.golems_arsenal.energy_saver")
                .withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        return List.of(Component.translatable("upgrade.golems_arsenal.energy_saver.desc",
                Math.round(DISCOUNT_PER_LEVEL * 100)).withStyle(ChatFormatting.GREEN));
    }
}
