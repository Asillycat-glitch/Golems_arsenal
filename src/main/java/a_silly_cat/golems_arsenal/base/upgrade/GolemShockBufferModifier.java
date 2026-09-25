package a_silly_cat.golems_arsenal.base.upgrade;

import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 减震（X5 的 Shock Buffer）：只做一件事 —— 抗击退。每级 +0.5 击退抗性（两级 = 完全免疫击退）。
 * 用 transient modifier 每 tick 校正，不写进 NBT、不碰原版属性定义。
 */
public class GolemShockBufferModifier extends GolemModifier {
    public static final int MAX_LEVEL = 2;
    private static final double RESISTANCE_PER_LEVEL = 0.5;
    private static final UUID UUID_KEY =
            java.util.UUID.nameUUIDFromBytes("golems_arsenal:shock_buffer".getBytes(StandardCharsets.UTF_8));

    public GolemShockBufferModifier() {
        super(StatFilterType.HEALTH, MAX_LEVEL);
    }

    @Override
    public Component getTooltip(int level) {
        return Component.translatable("upgrade.golems_arsenal.shock_buffer")
                .withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        return List.of(Component.translatable("upgrade.golems_arsenal.shock_buffer.desc",
                Math.round(RESISTANCE_PER_LEVEL * 100)).withStyle(ChatFormatting.GREEN));
    }

    @Override
    public void onAiStep(AbstractGolemEntity<?, ?> golem, int level) {
        if (golem.level().isClientSide) {
            return;
        }
        AttributeInstance instance = golem.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (instance == null) {
            return;
        }
        double value = Math.min(1.0, RESISTANCE_PER_LEVEL * Math.max(0, level));
        AttributeModifier current = instance.getModifier(UUID_KEY);
        if (current != null && Math.abs(current.getAmount() - value) < 1.0E-4) {
            return;   // 值没变，别每 tick 重建
        }
        instance.removeModifier(UUID_KEY);
        instance.addTransientModifier(new AttributeModifier(UUID_KEY,
                "golems_arsenal_shock_buffer", value, AttributeModifier.Operation.ADDITION));
    }
}
