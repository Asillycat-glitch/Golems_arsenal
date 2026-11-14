package a_silly_cat.golems_arsenal.guard.combat;

import net.minecraft.world.item.Item;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 正向声明：只有显式注册的类/接口适配器才能“处理”额外能力。
 * 它不是敌人名单；没有适配器的 CUSTOM_HOOK 默认不支持。
 */
public final class CombatAdapters {

    private static final List<CapabilityAdapter> ADAPTERS = new CopyOnWriteArrayList<>();

    private CombatAdapters() {
    }

    public static void register(CapabilityAdapter adapter) {
        ADAPTERS.add(adapter);
    }

    public static Set<CombatCapability> handledBy(Class<?> clazz) {
        return ADAPTERS.stream()
                .filter(adapter -> adapter.supports(clazz))
                .flatMap(adapter -> adapter.declaredCapabilities(clazz).stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    public static CapabilityAdapter adapterFor(Item item) {
        return ADAPTERS.stream()
                .filter(adapter -> adapter.supports(item.getClass()))
                .findFirst()
                .orElse(null);
    }
}
