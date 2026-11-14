package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.compat.artifact.DamageAdmission;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.entity.common.GuardedEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 读侧纠正：把"被外部覆盖表改小的血量"读回真实 {@code DATA_HEALTH}。
 *
 * <p>2026-11-07 定位到最后一层：ArcaneVortex 的 {@code HealthLockMixin} 注入
 * {@code LivingEntity#getHealth}，配合 {@code HealthMethodHelper.HEALTH_MAP} 返回一个
 * <b>覆盖值</b>。它的 {@code forceSetHealth(entity, 0, 0)} 只往表里写 (0,0)，
 * 真实 {@code DATA_HEALTH} 可能还是 198——于是：</p>
 *
 * <pre>
 *   raw(DATA_HEALTH)=198, getHealth()=0  ->  HUD 0/0、isDeadOrDying=true、die/移除循环
 * </pre>
 *
 * <p>GuardedEntity 覆写了 {@code getHealth}（super 取 max(raw, guardedData)），
 * 所以覆盖值会先污染 super，再被 max 带上来。这里在 {@code GuardedEntity#getHealth}
 * 的 RETURN 上做最后纠正：<b>只向上修</b>——报告值明显低于真实值、且真实值 &gt; 1 时才改。</p>
 *
 * <p>客户端也生效（HUD 读的也是 getHealth），所以不加 server-only 限制。</p>
 */
@Mixin(value = GuardedEntity.class, priority = 2000)
public abstract class GuardedEntityHealthReadMixin {

    private static final Map<UUID, Integer> LAST_LOG = new ConcurrentHashMap<>();

    @Inject(method = "getHealth", at = @At("RETURN"), cancellable = true)
    private void ga$correctReportedHealth(CallbackInfoReturnable<Float> cir) {
        try {
            if (!((Object) this instanceof AbstractGolemEntity<?, ?> golem)) {
                return;
            }
            if (golem.getOwnerUUID() == null) {
                return;
            }
            // mode=OFF 时整套读侧纠正也要停：否则"关掉防御"的实验会被这层污染。
            if (a_silly_cat.golems_arsenal.guard.sanctuary.Sanctuary.mode()
                    != a_silly_cat.golems_arsenal.guard.sanctuary.SanctuaryMode.ENFORCE) {
                return;
            }
            float reported = cir.getReturnValue();
            float raw = DamageAdmission.rawHealth(golem);
            // 2026-11-08：上界取 max(raw, 账本)。只看 raw 的话，"raw 被写 0、账本还满"这一帧
            // 仍然会报 0 血（HUD 空血、客户端播死亡动画），而这正是攻击方要的那一帧。
            float expected = a_silly_cat.golems_arsenal.guard.HealthLedger.expectedOf(golem);
            // 2026-11-09：读侧掩护。服务端、非可信读者（原版 / 别的模组 / 攻击者）一律只看得到
            // 掩护值——"血量低于我最大生命就秒杀"这类阈值判定因此永远不成立。
            // 客户端不挡（HUD 要显示真实血条），我们自己的作用域也不挡。
            if (false) {
                a_silly_cat.golems_arsenal.guard.sanctuary.ReadShield.noteHidden();
                cir.setReturnValue(a_silly_cat.golems_arsenal.guard.sanctuary.ReadShield.DECOY);
                return;
            }
            a_silly_cat.golems_arsenal.guard.sanctuary.ReadShield.noteTrusted();
            float truth = Math.max(Float.isFinite(raw) ? raw : 0.0F,
                    Float.isFinite(expected) ? expected : 0.0F);
            if (!Float.isFinite(truth) || !Float.isFinite(reported)) {
                return;
            }
            // 只向上纠正：报告值被读小了才管；账本 <= 1 时不管（那是真的快死了）。
            if (truth <= 1.0F || reported >= truth - 1.0F) {
                return;
            }
            cir.setReturnValue(truth);
            int now = golem.tickCount;
            Integer last = LAST_LOG.put(golem.getUUID(), now);
            if (last == null || now - last >= 20) {
                Golems_arsenal.LOGGER.info(
                        "[Sanctuary] health_read_corrected uuid={} reported={} raw={} ledger={}",
                        golem.getUUID(), reported, raw, expected);
            }
        } catch (Throwable ignored) {
        }
    }
}
