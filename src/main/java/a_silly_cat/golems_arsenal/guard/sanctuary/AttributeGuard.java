package a_silly_cat.golems_arsenal.guard.sanctuary;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 属性级守卫：拦住"把 MAX_HEALTH 压到腰斩"的修饰符 / base 改动。
 *
 * <p>2026-11-07 实测：ArcaneVortex 的 {@code HealthModify.attributeModifier} 走
 * {@code AttributeInstance#addPermanentModifier}，挂一个 {@code -(max-health)+0.001}
 * 的 ADDITION 修饰符，把上限压成 0。上限一 0，{@code getHealth()} 读 0、客户端播死亡动画，
 * 事后再修也慢半拍。</p>
 *
 * <p>这里在<b>修饰符进场的那一刻</b>就拦：只有"会让当前上限掉到一半以下"的负向修饰符
 * 才会被拒，正常升级/卸下/正面 buff 不受影响。不需要认识任何 mod id。</p>
 *
 * <p>{@code AttributeInstance} 不持有实体引用，所以实体在受击时通过 {@link #bind} 登记。</p>
 */
public final class AttributeGuard {

    private static final Map<AttributeInstance, java.lang.ref.WeakReference<AbstractGolemEntity<?, ?>>> OWNERS =
            new IdentityHashMap<>();
    private static final Map<UUID, Integer> LAST_LOG = new ConcurrentHashMap<>();

    private static long blocked;

    private AttributeGuard() {
    }

    /** 把属性实例和它的主人绑定（受击、审计时调用）。 */
    public static void bind(AttributeInstance instance, AbstractGolemEntity<?, ?> golem) {
        if (instance != null && golem != null) {
            OWNERS.put(instance, new java.lang.ref.WeakReference<>(golem));
        }
    }

    private static AbstractGolemEntity<?, ?> owner(AttributeInstance instance) {
        java.lang.ref.WeakReference<AbstractGolemEntity<?, ?>> ref = OWNERS.get(instance);
        return ref == null ? null : ref.get();
    }

    /** {@code AttributeInstance#addModifier/addPermanentModifier/addTransientModifier} HEAD。 */
    public static boolean allowModifier(AttributeInstance instance, AttributeModifier modifier) {
        try {
            if (modifier == null || instance == null) {
                return true;
            }
            if (!isMaxHealth(instance)) {
                return true;
            }
            AbstractGolemEntity<?, ?> golem = owner(instance);
            if (golem == null) {
                // 攻击方可能抢在第一次绑定之前挂修饰符：做一次反向查找兜底。
                golem = findOwner(instance);
            }
            if (golem == null || !Sanctuary.protectedGolem(golem)
                    || Sanctuary.mode() != SanctuaryMode.ENFORCE) {
                return true;
            }
            double current = instance.getValue();
            double after = apply(current, modifier);
            if (!(after < current * 0.5D)) {
                return true;
            }
            blocked++;
            log(golem, "ATTRIBUTE_MODIFIER_BLOCKED",
                    "max=" + current + " -> " + after
                            + " amount=" + modifier.getAmount()
                            + " op=" + modifier.getOperation());
            return false;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** {@code AttributeInstance#setBaseValue} HEAD。 */
    public static boolean allowBaseValue(AttributeInstance instance, double value) {
        try {
            if (instance == null || !isMaxHealth(instance)) {
                return true;
            }
            AbstractGolemEntity<?, ?> golem = owner(instance);
            if (golem == null) {
                golem = findOwner(instance);
            }
            if (golem == null || !Sanctuary.protectedGolem(golem)
                    || Sanctuary.mode() != SanctuaryMode.ENFORCE) {
                return true;
            }
            double current = instance.getBaseValue();
            if (!(value < current * 0.5D)) {
                return true;
            }
            blocked++;
            log(golem, "ATTRIBUTE_BASE_BLOCKED", "base=" + current + " -> " + value);
            return false;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean isMaxHealth(AttributeInstance instance) {
        try {
            return instance.getAttribute() == Attributes.MAX_HEALTH;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 反向查找：这个 {@code AttributeInstance} 属于哪只受保护傀儡。 */
    private static AbstractGolemEntity<?, ?> findOwner(AttributeInstance instance) {
        try {
            var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return null;
            }
            for (var level : server.getAllLevels()) {
                for (var entity : level.getEntities().getAll()) {
                    if (!(entity instanceof AbstractGolemEntity<?, ?> golem)) {
                        continue;
                    }
                    if (golem.getAttribute(Attributes.MAX_HEALTH) == instance) {
                        bind(instance, golem);
                        return golem;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static double apply(double current, AttributeModifier modifier) {
        double amount = modifier.getAmount();
        return switch (modifier.getOperation()) {
            case ADDITION -> current + amount;
            case MULTIPLY_BASE, MULTIPLY_TOTAL -> current * (1.0D + amount);
        };
    }

    private static void log(AbstractGolemEntity<?, ?> golem, String action, String detail) {
        int now = golem.tickCount;
        Integer last = LAST_LOG.put(golem.getUUID(), now);
        if (last != null && now - last < 20) {
            return;
        }
        Golems_arsenal.LOGGER.info("[Sanctuary] {} target={} uuid={} {}",
                action, golem.getName().getString(), golem.getUUID(), detail);
    }

    public static String statusLine() {
        return "attributeBlocked=" + blocked + " bound=" + OWNERS.size();
    }

    public static void rotateIdentity(UUID old, UUID fresh) {
        Integer at = LAST_LOG.remove(old);
        if (at != null) {
            LAST_LOG.put(fresh, at);
        }
    }

    public static void clear() {
        OWNERS.clear();
        LAST_LOG.clear();
        blocked = 0;
    }

    /** 属性读侧地板用的"上次正常值"（只对已绑定的受保护傀儡）。 */
    private static final Map<AttributeInstance, Double> LAST_GOOD = new java.util.WeakHashMap<>();

    /**
     * {@code AttributeInstance#getValue} 的地板：只拦"腰斩式"下降。
     *
     * <p>调用频率很高，所以只查 {@link #OWNERS}（进世界/受击时已绑定），
     * 不做反向全服扫描。</p>
     */
    public static double floorMaxHealth(AttributeInstance instance, double reported) {
        try {
            if (instance == null || !isMaxHealth(instance)) {
                return reported;
            }
            AbstractGolemEntity<?, ?> golem = owner(instance);
            if (golem == null || !Sanctuary.protectedGolem(golem)) {
                return reported;
            }
            Double last = LAST_GOOD.get(instance);
            if (last == null || reported > last) {
                LAST_GOOD.put(instance, reported);
                return reported;
            }
            if (last > 0.0D && reported < last * 0.5D) {
                blocked++;
                log(golem, "ATTRIBUTE_VALUE_FLOORED", "reported=" + reported + " lastGood=" + last);
                return last;
            }
            return reported;
        } catch (Throwable ignored) {
            return reported;
        }
    }
}