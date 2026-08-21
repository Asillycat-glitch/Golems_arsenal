package a_silly_cat.golems_arsenal.mixin.golemmagicka;

import a_silly_cat.golems_arsenal.upgrade.GolemScrollModifier;
import dev.xkmc.golemmagicka.content.entity.GolemSpellManager;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.mob_weapon_api.registry.WeaponStatus;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Golem Magicka only lets a golem cast while it holds a magic item (spellbook/wand/magic sword/
 * any ISpellContainer) - the wizard goal's canUse and the weapon registry both consult this
 * predicate. A golem with the scroll upgrade should cast its recorded spell regardless, so this
 * mixin lets the predicate pass for such golems with any held item (melee still works while the
 * spell is on cooldown or out of mana).
 */
@Mixin(GolemSpellManager.class)
public abstract class GolemSpellManagerMixin {

    @Inject(method = "predicate", at = @At("HEAD"), cancellable = true)
    private static void golemsArsenal$enableScrollUpgradeCasting(LivingEntity entity, ItemStack stack,
                                                                 InteractionHand hand,
                                                                 CallbackInfoReturnable<Optional<WeaponStatus>> cir) {
        if (entity instanceof AbstractGolemEntity<?, ?> golem && GolemScrollModifier.hasUpgrade(golem)) {
            cir.setReturnValue(WeaponStatus.OFFENSIVE.withPriority(1000).of(true));
        }
    }
}
