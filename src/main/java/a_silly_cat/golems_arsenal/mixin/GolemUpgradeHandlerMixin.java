package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.init.ModTags;
import dev.xkmc.modulargolems.content.item.upgrade.IUpgradeItem;
import dev.xkmc.modulargolems.content.item.upgrade.UpgradeItem;
import dev.xkmc.modulargolems.content.menu.table.GolemUpgradeItemHandler;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;

/**
 * Special moves are mutually exclusive: while any item tagged
 * {@code golems_arsenal:special_move_upgrades} is already installed on the holder, the golem
 * workbench rejects adding another one. Follows the existing mixin-into-Modular-Golems pattern
 * (see {@code AbstractGolemEnergyMixin}); MG 2.7.x runs with readable (official) method names.
 */
@Mixin(GolemUpgradeItemHandler.class)
public abstract class GolemUpgradeHandlerMixin {

    @Shadow
    private ArrayList<IUpgradeItem> upgrades;

    @Inject(method = "appendUpgrade", at = @At("HEAD"), cancellable = true)
    private void golemsArsenal$rejectSecondSpecialMove(UpgradeItem incoming,
                                                       CallbackInfoReturnable<ItemStack> cir) {
        if (!isSpecialMove(incoming)) {
            return;
        }
        for (IUpgradeItem existing : upgrades) {
            if (existing != incoming && existing instanceof Item item && isSpecialMove(item)) {
                cir.setReturnValue(ItemStack.EMPTY);
                return;
            }
        }
    }

    private static boolean isSpecialMove(Item item) {
        return new ItemStack(item).is(ModTags.SPECIAL_MOVE_UPGRADES);
    }
}
