package a_silly_cat.golems_arsenal.mixin.tinkers;

import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.capabilities.Capability;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "golemknights.tinkersgolem.events.GolemOverslimeEvents")
public abstract class TinkersGolemOverslimeCompatMixin {

    private static volatile Object overslimeCapability;
    private static volatile boolean capabilityResolved;

    @Inject(method = "getOverslime", at = @At("HEAD"), cancellable = true)
    private static void ga$getOverslime(LivingEntity entity, CallbackInfoReturnable<Float> cir) {
        if (isForeignGolem(entity)) {
            cir.setReturnValue(0.0F);
        }
    }

    @Inject(method = "setOverslime", at = @At("HEAD"), cancellable = true)
    private static void ga$setOverslime(LivingEntity entity, float value, CallbackInfo ci) {
        if (isForeignGolem(entity)) {
            ci.cancel();
        }
    }

    @Inject(method = "addOverslime", at = @At("HEAD"), cancellable = true)
    private static void ga$addOverslime(LivingEntity entity, float value, CallbackInfo ci) {
        if (isForeignGolem(entity)) {
            ci.cancel();
        }
    }

    @Inject(method = "onGolemHurt", at = @At("HEAD"), cancellable = true)
    private static void ga$onGolemHurt(LivingEntity entity, float amount,
                                       CallbackInfoReturnable<Float> cir) {
        if (isForeignGolem(entity)) {
            cir.setReturnValue(amount);
        }
    }

    private static boolean isForeignGolem(LivingEntity entity) {
        return entity instanceof AbstractGolemEntity<?, ?> golem && !hasOverslime(golem);
    }

    private static boolean hasOverslime(LivingEntity entity) {
        Object capability = capability();
        if (capability == null) {
            return entity.getClass().getName().startsWith("golemknights.tinkersgolem.");
        }
        try {
            return entity.getCapability((Capability<?>) capability).isPresent();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object capability() {
        if (!capabilityResolved) {
            synchronized (TinkersGolemOverslimeCompatMixin.class) {
                if (!capabilityResolved) {
                    overslimeCapability = resolveCapability();
                    capabilityResolved = true;
                }
            }
        }
        return overslimeCapability;
    }

    private static Object resolveCapability() {
        try {
            Class<?> cap = Class.forName("golemknights.tinkersgolem.cap.OverslimeCap",
                    false, TinkersGolemOverslimeCompatMixin.class.getClassLoader());
            return cap.getField("CAPABILITY").get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }
}