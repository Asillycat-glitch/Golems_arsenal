package a_silly_cat.golems_arsenal.item;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.upgrade.GolemEnergyTechModifier;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.item.equipments.MetalGolemWeaponItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Defensive melee weapon: big-hit damage reduction, flat damage immunity and tech-scaled HP regen. */
public class GolemEnergyHammerItem extends MetalGolemWeaponItem {
    public static final int BASE_ATTACK_DAMAGE = 10;

    public GolemEnergyHammerItem(Properties properties) {
        super(properties, BASE_ATTACK_DAMAGE, 0.0, 1.5f, 2.0f);
    }

    /** Flat damage reduction is a tech-upgrade skill; it unlocks when the tech upgrade is installed. */
    public static boolean hasFlatGuard(AbstractGolemEntity<?, ?> golem) {
        return GolemEnergyTechModifier.hasTechUpgrade(golem);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> list, TooltipFlag flag) {
        list.add(Component.translatable("tooltip.golems_arsenal.energy_hammer.defense",
                Math.round(Config.ENERGY_HAMMER_BIG_HIT_THRESHOLD.get() * 100),
                Math.round(Config.ENERGY_HAMMER_BIG_HIT_REDUCTION.get() * 100)).withStyle(ChatFormatting.GRAY));
        list.add(Component.translatable("tooltip.golems_arsenal.energy_hammer.regen",
                Math.round(Config.ENERGY_HAMMER_HP_REGEN_PERCENT.get())).withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, level, list, flag);
    }
}
