package a_silly_cat.golems_arsenal.tech.upgrade;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.tech.energy.GolemEnergyProvider;
import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

public class GolemEnergyModifier extends GolemModifier {
    public static final int MAX_LEVEL = 5;

    public GolemEnergyModifier() { super(StatFilterType.HEALTH, MAX_LEVEL); }

    // This modifier is intentionally not registered in Modular Golems' Registrate registry.
    // Supplying its own text prevents creative-tab tooltip code from resolving a missing registry id.
    @Override
    public Component getTooltip(int level) {
        return Component.translatable("upgrade.golems_arsenal.golem_energy").withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        return List.of(Component.translatable("upgrade.golems_arsenal.golem_energy.desc")
                .withStyle(ChatFormatting.GREEN));
    }

    @Override
    public void onGolemSpawn(AbstractGolemEntity<?, ?> entity, int level) {
        update(entity, level);
    }

    @Override
    public void onAiStep(AbstractGolemEntity<?, ?> entity, int level) {
        update(entity, level);
    }

    private static void update(AbstractGolemEntity<?, ?> entity, int level) {
        entity.getCapability(GolemEnergyProvider.CAPABILITY).ifPresent(storage ->
                ((a_silly_cat.golems_arsenal.tech.energy.GolemEnergyStorage) storage).setCapacity(capacityForLevel(level)));
    }

    /** Shared capacity formula used by both the deployed golem and the stowed holder item. */
    public static int capacityForLevel(int level) {
        if (level <= 0) return 0;
        return Config.GOLEM_ENERGY_BASE_CAPACITY.get() + Math.max(0, level) * Config.GOLEM_ENERGY_PER_LEVEL.get();
    }
}
