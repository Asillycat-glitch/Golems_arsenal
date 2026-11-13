package a_silly_cat.golems_arsenal.testbench.mixin;

import a_silly_cat.golems_arsenal.testbench.HealthGuard;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P2：拦“直接写真血”的实际路径（PRIVATE TESTBENCH）。
 *
 * <p>1.20.1 的 {@code LivingEntity.health} 存在 {@code SynchedEntityData} 的
 * {@code DATA_HEALTH_ID} 里；{@code VanShAttackEntity} / {@code iss_csw.EntityUtil} 都是绕过
 * {@code setHealth} 直接 {@code entityData.set(DATA_HEALTH_ID, x)}。tick reconcile 只能事后回滚，
 * 若紧接着 hurt(Infinity) 就会先死；所以必须在 {@code SynchedEntityData.set} 这一层拦。</p>
 *
 * <p>owner entity 不用 {@code @Shadow}：这个 Mixin/Forge 组合下字段 refmap 不生效，
 * 统一由 {@link HealthGuard#entityOf} 反射拿 {@code f_135344_}。</p>
 */
@Mixin(SynchedEntityData.class)
public abstract class SynchedEntityDataTraceMixin {

    @Inject(method = "set", at = @At("HEAD"), cancellable = true)
    private <T> void ga$setHealthData(EntityDataAccessor<T> key, T value, CallbackInfo ci) {
        if (!(value instanceof Float amount) || !HealthGuard.isHealthAccessor(key)) {
            return;
        }
        Entity owner = HealthGuard.entityOf((SynchedEntityData) (Object) this);
        if (owner instanceof LivingEntity living
                && !HealthGuard.allowEntityDataHealth(living, amount)) {
            ci.cancel();
        }
    }
}
