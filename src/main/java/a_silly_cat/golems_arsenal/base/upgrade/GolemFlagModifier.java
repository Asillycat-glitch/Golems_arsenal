package a_silly_cat.golems_arsenal.base.upgrade;

import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * Generic level-1 flag modifier shared by all marker upgrades (main/alt/ranged/shield/onslaught,
 * death explosion, scroll). Only the stat filter, language key and optional extra detail keys
 * differ between them; {@link #hasUpgrade} checks the exact registered instance.
 */
public class GolemFlagModifier extends GolemModifier {
    private final String langKey;
    private final String[] extraDetailKeys;

    public GolemFlagModifier(StatFilterType type, String langKey, String... extraDetailKeys) {
        super(type, 1);
        this.langKey = langKey;
        this.extraDetailKeys = extraDetailKeys;
    }

    @Override
    public Component getTooltip(int level) {
        return Component.translatable(langKey).withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        List<MutableComponent> list = new ArrayList<>();
        list.add(Component.translatable(langKey + ".desc").withStyle(ChatFormatting.GREEN));
        for (String key : extraDetailKeys) {
            list.add(Component.translatable(key).withStyle(ChatFormatting.GREEN));
        }
        return list;
    }

    /** True when the given modifier instance is installed on the golem. */
    public static boolean hasUpgrade(AbstractGolemEntity<?, ?> entity, GolemModifier modifier) {
        return entity.getModifiers().containsKey(modifier);
    }

    /**
     * 这只傀儡是不是正骑在另一只傀儡身上（本家的 {@code RideUpgrade} / 骑乘手杖那条路）。
     * <p>
     * 用来闸住<b>所有会挪动傀儡本体的特技</b>（冲撞 / 狮子斩 / 凤凰天驱 / 剑雨）：它们全都按
     * <b>绝对坐标</b> {@code setPos}，而原版乘客每 tick 会被强制贴回载具 —— 两边对着拽，表现为
     * 每秒 20 次的来回拉扯，同时 {@code setNoGravity(true)} 还可能把乘骑状态卡住。
     * <p>
     * 注意与"骑兵加成"方向相反：{@link a_silly_cat.golems_arsenal.base.event.WeaponEventHandler}
     * 里的枪骑加成<b>需要</b>这道条件成立才生效，所以那个不能改用这个判定去拦。
     */
    public static boolean hasRiddenMount(AbstractGolemEntity<?, ?> entity) {
        return entity.getVehicle() instanceof AbstractGolemEntity<?, ?>;
    }
}
