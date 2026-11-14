package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.compat.artifact.DamageAdmission;
import a_silly_cat.golems_arsenal.guard.HealthLedger;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 神器伤害准入的 DataItem 写路径：覆盖不经过 setHealth、直接
 * {@code SynchedEntityData.set(DATA_HEALTH_ID, x)} 的 true-damage/外部写。
 */
@Mixin(SynchedEntityData.class)
public abstract class SynchedEntityDataHealthAdmissionMixin {

    @Inject(method = "set", at = @At("HEAD"), cancellable = true)
    private <T> void ga$admitData(EntityDataAccessor<T> key, T value, CallbackInfo ci) {
        if (!(value instanceof Float amount) || !DamageAdmission.isHealthAccessor(key)) {
            return;
        }
        Entity owner = DamageAdmission.entityOf((SynchedEntityData) (Object) this);
        if (owner instanceof LivingEntity living) {
            HealthLedger.noteOffThread(living, "data.health");
            if (!DamageAdmission.allowDataWrite(living, amount)) {
                ci.cancel();
            }
        }
    }
}
