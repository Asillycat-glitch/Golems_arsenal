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
    private static final Map<UUID, Float> LAST_GOOD_CLIENT = new ConcurrentHashMap<>();

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
            // 客户端也做投影读：AV 可以直接把客户端的 DATA_HEALTH 写成 0/0.1；
            // 记住最后一次正常值，别让客户端模型/死亡动画按 0 血跑。
            if (golem.level().isClientSide
                    && a_silly_cat.golems_arsenal.guard.ProtectedEntityPolicy.protectsClient(golem)) {
                if (Float.isFinite(reported) && reported > 1.0F) {
                    LAST_GOOD_CLIENT.put(golem.getUUID(), reported);
                    return;
                }
                Float last = LAST_GOOD_CLIENT.get(golem.getUUID());
                float fallback = last != null && Float.isFinite(last) && last > 1.0F
                        ? last : Math.max(1.0F, golem.getMaxHealth());
                cir.setReturnValue(fallback);
                return;
            }
            float raw = DamageAdmission.rawHealth(golem);
            float expected = a_silly_cat.golems_arsenal.guard.HealthLedger.expectedOf(golem);
            // 2026-11-18：服务端权威读。受保护傀儡的 getHealth 不再 max(raw, ledger)，
            // 而是 Soul/账本；Entity/DataItem 只是投影。native 写内存写高/写低都不影响
            // 这里的权威值（客户端仍读同步值，HUD 由服务端包纠正）。
            if (!golem.level().isClientSide
                    && a_silly_cat.golems_arsenal.guard.ProtectedEntityPolicy.protects(golem)) {
                float soulHealth = a_silly_cat.golems_arsenal.guard.SoulRegistry
                        .healthForProjection(golem.getUUID());
                float authority = Float.isFinite(soulHealth) && soulHealth > 0.0F
                        ? soulHealth : expected;
                if (Float.isFinite(authority) && authority > 1.0F) {
                    cir.setReturnValue(authority);
                    return;
                }
            }
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
