package a_silly_cat.golems_arsenal.guard.combat;

import java.util.Set;

/**
 * 适配器是正向声明：类/接口由它支持时，它声明自己**已经处理**哪些能力。
 * 一个适配器必须一次性声明全部能力，不能只处理 DIRECT_HEALTH_WRITE 而漏 FORCED_REMOVAL。
 */
public interface CapabilityAdapter {

    boolean supports(Class<?> clazz);

    Set<CombatCapability> declaredCapabilities(Class<?> clazz);

    /** 真正执行适配；没有适配器时不会调用。 */
    void handle(CombatAction action);
}
