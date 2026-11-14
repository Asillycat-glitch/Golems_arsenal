package a_silly_cat.golems_arsenal.mixin.golemmagicka;

import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.mob_weapon_api.registry.WeaponStatus;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Golem Magicka only lets a golem cast while it holds a magic item (spellbook/wand/magic sword/
 * any ISpellContainer) - the wizard goal's canUse and the weapon registry both consult this
 * predicate. A golem with the scroll upgrade should cast its recorded spell regardless, so this
 * mixin lets the predicate pass for such golems with any held item (melee still works while the
 * spell is on cooldown or out of mana).
 *
 * <p>All references to Golems Arsenal classes are reflective: this mixin is applied to a
 * golemmagicka target, whose module classloader does not necessarily expose our classes to the
 * mixin metadata resolver. Direct references cause ClassMetadataNotFoundException at runtime.</p>
 */
@Mixin(targets = "dev.xkmc.golemmagicka.content.entity.GolemSpellManager")
public abstract class GolemSpellManagerMixin {
    @Inject(method = "predicate", at = @At("HEAD"), cancellable = true)
    private static void golemsArsenal$enableScrollUpgradeCasting(LivingEntity entity, ItemStack stack,
                                                                 InteractionHand hand,
                                                                 CallbackInfoReturnable<Optional<WeaponStatus>> cir) {
        if (entity instanceof AbstractGolemEntity<?, ?> golem && GolemMagickaReflection.hasScroll(golem)) {
            cir.setReturnValue(WeaponStatus.OFFENSIVE.withPriority(1000).of(true));
        }
    }

    private static final class GolemMagickaReflection {
        private static Method hasUpgrade;
        private static Object scrollModifier;

        private static boolean hasScroll(AbstractGolemEntity<?, ?> golem) {
            try {
                if (scrollModifier == null) {
                    Class<?> compat = Class.forName("a_silly_cat.golems_arsenal.compat.golemmagicka.GolemMagickaCompat");
                    Object entry = compat.getField("SCROLL").get(null);
                    scrollModifier = entry.getClass().getMethod("get").invoke(entry);
                }
                if (hasUpgrade == null) {
                    Class<?> flag = Class.forName("a_silly_cat.golems_arsenal.base.upgrade.GolemFlagModifier");
                    Class<?> modifier = Class.forName("dev.xkmc.modulargolems.content.modifier.base.GolemModifier");
                    hasUpgrade = flag.getMethod("hasUpgrade", AbstractGolemEntity.class, modifier);
                }
                return (boolean) hasUpgrade.invoke(null, golem, scrollModifier);
            } catch (Throwable ignored) {
                return false;
            }
        }
    }
}
