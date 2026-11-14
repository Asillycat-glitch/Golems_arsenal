package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.compat.artifact.DamageAdmission;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * D3 写入口：{@code SynchedEntityData.DataItem#setValue}。
 *
 * <p>为什么必须单开一层：{@code SynchedEntityData#set} 是我们已有的门，但匠战妖域里有四份代码
 * 绕过它直接写 DataItem 的值——{@code ForceHurtUtil.forceHurt}、
 * {@code PenetratingDamage.tryModifyHealth}、{@code LivingEntityUtil.reflectionSeverance}、
 * {@code ticex 的 IEntityDataAccessor.setValue}。它们都不发 Forge 事件。</p>
 *
 * <p><b>实现方式</b>：在 {@code setValue} 的 HEAD 上 cancel。{@code setValue} 是 {@code void}，
 * cancel 就等于"这次赋值没有发生"，比 {@code @Redirect} 一个字段更稳——字段名在 SRG 里是
 * {@code f_135391_}，写进 {@code target} 字符串要额外维护一条 refmap 字段映射，漏了就是
 * "编译过、启动崩"。</p>
 */
@Mixin(SynchedEntityData.DataItem.class)
public abstract class HealthDataItemSealMixin {

    @Inject(method = "setValue", at = @At("HEAD"), cancellable = true)
    private void ga$gateValue(Object value, CallbackInfo ci) {
        if (value instanceof Float incoming
                && DamageAdmission.isSealedHealthItem(this)
                && !DamageAdmission.allowDataItemHealth(this, incoming)) {
            ci.cancel();
        }
    }
}
