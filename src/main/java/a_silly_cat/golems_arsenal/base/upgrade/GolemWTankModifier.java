package a_silly_cat.golems_arsenal.base.upgrade;

import a_silly_cat.golems_arsenal.tech.energy.GolemEnergyProvider;
import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.energy.IEnergyStorage;

import java.util.List;

/**
 * W 罐（E 罐的附属）：把 E 罐里的修复材料转成傀儡的 FE。
 * <p>
 * 傀儡 FE 低于上限的一半时，每 {@link #CONVERT_INTERVAL} tick 从 E 罐取 1 份材料换成
 * {@link #FE_PER_MATERIAL} FE。"每 60 秒给 E 罐补一份材料"是四件套联动的效果（见
 * {@code GolemsArsenalSetHandler}），不在这个升级里。
 */
public class GolemWTankModifier extends GolemModifier {
    public static final int MAX_LEVEL = 1;
    /** 1 份材料能换多少 FE。 */
    private static final int FE_PER_MATERIAL = 50_000;
    /** FE 低于上限的这个比例时才换电。 */
    private static final double FE_THRESHOLD = 0.5;

    private static final String LAST_CONVERT_KEY = "GolemsArsenalWTankConvert";
    /** 换电的最小间隔（tick），避免一秒钟把罐子倒空。 */
    private static final int CONVERT_INTERVAL = 20;

    public GolemWTankModifier() {
        super(StatFilterType.HEALTH, MAX_LEVEL);
    }

    @Override
    public Component getTooltip(int level) {
        return Component.translatable("upgrade.golems_arsenal.w_tank")
                .withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        return List.of(Component.translatable("upgrade.golems_arsenal.w_tank.desc")
                .withStyle(ChatFormatting.GREEN),
                Component.translatable("upgrade.golems_arsenal.w_tank.convert",
                        FE_PER_MATERIAL).withStyle(ChatFormatting.GRAY),
                Component.translatable("upgrade.golems_arsenal.w_tank.set")
                        .withStyle(ChatFormatting.DARK_AQUA));
    }

    @Override
    public void onAiStep(AbstractGolemEntity<?, ?> golem, int level) {
        if (golem.level().isClientSide || golem.tickCount % 20 != 0) {
            return;
        }
        long now = golem.level().getGameTime();
        CompoundTag tag = golem.getPersistentData();
        int tankLevel = GolemETankModifier.levelOf(golem);
        if (tankLevel <= 0) {
            return;   // 没装 E 罐就没有罐子可补
        }
        // 材料换电
        if (now < tag.getLong(LAST_CONVERT_KEY) + CONVERT_INTERVAL) {
            return;
        }
        if (GolemETankModifier.stored(golem) <= 0) {
            return;
        }
        IEnergyStorage storage = golem.getCapability(GolemEnergyProvider.CAPABILITY)
                .resolve().orElse(null);
        if (storage == null || storage.getMaxEnergyStored() <= 0) {
            return;
        }
        if (storage.getEnergyStored() > storage.getMaxEnergyStored() * FE_THRESHOLD) {
            return;
        }
        if (GolemETankModifier.consumeOne(golem)) {
            tag.putLong(LAST_CONVERT_KEY, now);
            storage.receiveEnergy(FE_PER_MATERIAL, false);
        }
    }
}
