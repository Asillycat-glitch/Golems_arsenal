package a_silly_cat.golems_arsenal.testbench.mixin;

import a_silly_cat.golems_arsenal.testbench.HealthGuard;
import a_silly_cat.golems_arsenal.testbench.HealthTrace;
import dev.xkmc.modulargolems.content.entity.common.GuardedEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * P0 观测：本家傀儡的 setHealth/getHealth/setGuardedDataImpl（PRIVATE TESTBENCH）。
 *
 * <p>为什么单独挂：{@code GuardedEntity} 把血量存在自己的 {@code guardedData} 里，并覆写了
 * {@code setHealth}/{@code getHealth}。只在 {@code LivingEntity} 上埋点会漏掉：
 * <ul>
 *   <li>{@code GuardedEntity.setHealth} 在 {@code tickCount > 5} 后会<b>拒绝</b>"变大"的写入，
 *       这个拒绝发生在调用 super 之前；</li>
 *   <li>L2/本家的伤害落地走 {@code setGuardedDataImpl}，不是 {@code setHealth}；</li>
 *   <li>{@code setGuardedDataImpl} 内部再调 {@code super.setHealth} 同步 vanilla 字段。</li>
 * </ul>
 * 三层都会记，但用 {@code nested} 标记嵌套，统计"未配对写入"时只数最外层。</p>
 *
 * <p><b>为什么不直接读 {@code getGuardedDataImpl()}：</b>它在 {@code guardedData == null} 时会
 * 调 {@code validateGuardedData()} 做懒初始化。P0 的要求是"观测不能改行为"，所以先 shadow
 * {@code guardedData} 字段判断是否已初始化；没初始化时不读，等方法自己初始化后再读。
 * 这样第一次写入可能拿不到 before（记 {@code beforeKnown=false}），但不会让观测代码提前创建
 * guardedData。</p>
 *
 * <p><b>生产环境的名字：</b>本家 jar 是 reobf 过的，{@code setHealth}/{@code getHealth} 作为
 * vanilla 覆写在生产里是 SRG 名（{@code m_21153_}/{@code m_21223_}）。所以这两个注入<b>必须</b>
 * 走 refmap；不能像 mod 自有方法 {@code setGuardedDataImpl} 那样写 {@code remap = false}。
 * 第一版就是这里写错了，启动时报 "could not find any targets matching 'setHealth'"。</p>
 *
 * <p>目标是 mod 类：类名本身不重映射，但覆写的 vanilla 方法名要重映射；mod 自有方法
 * {@code setGuardedDataImpl} 与字段 {@code guardedData} 保持原名，用 {@code remap = false}。</p>
 */
@Mixin(GuardedEntity.class)
public abstract class GuardedEntityTraceMixin {

    @Shadow(remap = false)
    private GuardedEntity.GuardedData guardedData;

    @Inject(method = "setHealth", at = @At("HEAD"), cancellable = true)
    private void ga$setHealthHead(float amount, CallbackInfo ci) {
        if (!HealthGuard.allowSetHealth((GuardedEntity) (Object) this, amount)) {
            ci.cancel();
            return;
        }
        HealthTrace.onGuardedSetHealthHead((GuardedEntity) (Object) this, amount, guardedData != null);
    }

    @Inject(method = "setHealth", at = @At("RETURN"))
    private void ga$setHealthReturn(float amount, CallbackInfo ci) {
        HealthTrace.onGuardedSetHealthReturn((GuardedEntity) (Object) this, amount, guardedData != null);
    }

    @Inject(method = "getHealth", at = @At("RETURN"))
    private void ga$getHealthReturn(CallbackInfoReturnable<Float> cir) {
        HealthTrace.onGuardedGetHealth((GuardedEntity) (Object) this, cir.getReturnValueF());
    }

    @Inject(method = "setGuardedDataImpl(FZZ)V", at = @At("HEAD"), remap = false, cancellable = true)
    private void ga$setGuardedDataHead(float amount, boolean sync, boolean force, CallbackInfo ci) {
        if (!HealthGuard.allowGuardedData((GuardedEntity) (Object) this, amount)) {
            ci.cancel();
            return;
        }
        HealthTrace.onGuardedDataHead((GuardedEntity) (Object) this, amount, sync, force,
                guardedData != null);
    }

    @Inject(method = "setGuardedDataImpl(FZZ)V", at = @At("RETURN"), remap = false)
    private void ga$setGuardedDataReturn(float amount, boolean sync, boolean force, CallbackInfo ci) {
        HealthTrace.onGuardedDataReturn((GuardedEntity) (Object) this, amount, sync, force,
                guardedData != null);
    }
}
