package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.guard.sanctuary.Projection;
import a_silly_cat.golems_arsenal.guard.sanctuary.SanctuaryScopes;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 坐标门：{@code Entity#setPosRaw} 是 1.20.1 所有位置写入的终点
 * （{@code setPos} / {@code moveTo} / {@code move} 全都汇到这里）。
 *
 * <p>两档：</p>
 * <ul>
 *   <li><b>SEALED</b>：受保护傀儡一旦被冻结，坐标<b>只认我们自己写的</b>
 *       （{@code inLoad} / {@code inInternal} / {@code inDeath} 三个作用域）。
 *       其余一律丢弃 —— 包括 {@code setPos(0,0,0)}、随机远方点、以及任何"看起来正常"的搬运。</li>
 *   <li><b>NORMAL</b>：只拦非有限值和单步 &gt; 128 格。</li>
 * </ul>
 *
 * <p>为什么这条比"事后再拉回来"重要：实体一旦落在 NaN / 世界外，
 * {@code level.isLoaded(pos)} 就是 false，任何"按位置判断"的守望都会主动放弃它。</p>
 */
@Mixin(Entity.class)
public abstract class EntitySetPosGuardMixin {

    /** NORMAL 档的单步位移上限。 */
    private static final double MAX_STEP = 128.0D;

    /** 封死档的日志节流：同一只傀儡 20 tick 一条。 */
    private static final Map<UUID, Long> LAST_SEALED_LOG = new ConcurrentHashMap<>();

    @Inject(method = "setPosRaw", at = @At("HEAD"), cancellable = true)
    private void ga$guardPosRaw(double x, double y, double z, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        // 我们自己写的（读档 / 内部修复 / 死亡栈）永远放行。
        if (SanctuaryScopes.inLoad() || SanctuaryScopes.inInternal() || SanctuaryScopes.inDeath()) {
            return;
        }
        // SEALED：受保护傀儡的位置只认作用域内的写。
        if (Projection.sealed(self)) {
            ci.cancel();
            long now = self.level().getGameTime();
            Long last = LAST_SEALED_LOG.get(self.getUUID());
            if (last == null || now - last >= 20) {
                LAST_SEALED_LOG.put(self.getUUID(), now);
                Golems_arsenal.LOGGER.warn("[Sanctuary] blocked site=Entity.setPosRaw sealed uuid={} from=({},{},{}) to=({},{},{})",
                        self.getUUID(), self.getX(), self.getY(), self.getZ(), x, y, z);
            }
            return;
        }
        if (!a_silly_cat.golems_arsenal.guard.sanctuary.RemovalGuard.ledgerAlive(self)) {
            return;
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            ci.cancel();
            Golems_arsenal.LOGGER.warn("[Sanctuary] blocked site=Entity.setPosRaw non-finite uuid={} to=({},{},{})",
                    self.getUUID(), x, y, z);
            return;
        }
        double dx = x - self.getX();
        double dy = y - self.getY();
        double dz = z - self.getZ();
        if (Math.abs(dx) > MAX_STEP || Math.abs(dy) > MAX_STEP || Math.abs(dz) > MAX_STEP) {
            ci.cancel();
            Golems_arsenal.LOGGER.warn("[Sanctuary] blocked site=Entity.setPosRaw far uuid={} from=({},{},{}) to=({},{},{})",
                    self.getUUID(), self.getX(), self.getY(), self.getZ(), x, y, z);
        }
    }
}
