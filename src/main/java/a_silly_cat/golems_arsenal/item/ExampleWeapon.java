package a_silly_cat.golems_arsenal.item;

import dev.xkmc.modulargolems.content.item.equipments.MetalGolemWeaponItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class ExampleWeapon extends MetalGolemWeaponItem {
    private final double extraDamagePercent;

    public ExampleWeapon(Properties properties, int attackDamage, double extraDamagePercent, float range, float sweep) {
        super(properties, attackDamage, extraDamagePercent, range, sweep);
        this.extraDamagePercent = extraDamagePercent;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> list, TooltipFlag flag) {
        list.add(Component.translatable("tooltip.golems_arsenal.example_weapon",
                Math.round(extraDamagePercent * 100)));
        super.appendHoverText(stack, level, list, flag);
    }
}
