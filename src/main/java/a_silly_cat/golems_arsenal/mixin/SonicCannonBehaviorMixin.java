package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.upgrade.GolemWeaponRangedModifier;
import dev.xkmc.mob_weapon_api.api.projectile.ProjectileWeaponUser;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.item.ranged.SonicCannonBehavior;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The golem Sonic Cannon's cooldown is the return value of
 * {@code SonicCannonBehavior.trigger} (default 20 ticks, 10 with a sculk body part).
 * With the ranged weapon upgrade installed, shrink that cooldown by the configured
 * multiplier so the Echo Cannon also benefits from the upgrade.
 */
@Mixin(SonicCannonBehavior.class)
public abstract class SonicCannonBehaviorMixin {

    @Inject(method = "trigger", at = @At("RETURN"), cancellable = true)
    private void golemsArsenal$reduceCannonCooldown(ProjectileWeaponUser user, ItemStack stack,
                                                    LivingEntity target, int time,
                                                    CallbackInfoReturnable<Integer> cir) {
        if (user.user() instanceof AbstractGolemEntity<?, ?> golem
                && GolemWeaponRangedModifier.hasUpgrade(golem)) {
            int cd = cir.getReturnValueI();
            cir.setReturnValue(Math.max(1, (int) Math.ceil(cd * Config.RANGED_CANNON_CD_MULTIPLIER.get())));
        }
    }
}