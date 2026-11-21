package a_silly_cat.golems_arsenal.guard.index;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** 具体类型包装：byUuid / ClassInstanceMultiMap.byClass 等。 */
public class GuardedHashMap<K, V> extends HashMap<K, V> {

    public GuardedHashMap(Map<? extends K, ? extends V> source) {
        super(source);
    }

    @Override
    public V remove(Object key) {
        if (WorldIndexGuard.isProtected(key)) {
            WorldIndexGuard.deny("HashMap.remove", key);
            return null;
        }
        return super.remove(key);
    }

    @Override
    public boolean remove(Object key, Object value) {
        if (WorldIndexGuard.isProtected(key) || WorldIndexGuard.isProtected(value)) {
            WorldIndexGuard.deny("HashMap.remove(key,value)", key);
            return false;
        }
        return super.remove(key, value);
    }

    @Override
    public V put(K key, V value) {
        return super.put(key, (V) guardValue(value));
    }

    @Override
    public V computeIfAbsent(K key, java.util.function.Function<? super K, ? extends V> mappingFunction) {
        V value = super.computeIfAbsent(key, mappingFunction);
        V guarded = (V) guardValue(value);
        if (guarded != value) {
            super.put(key, guarded);
        }
        return guarded;
    }

    private Object guardValue(Object value) {
        if (value instanceof java.util.Set<?> && !WorldIndexGuard.isGuarded(value)) {
            return new GuardedHashSet<>((java.util.Set<?>) value);
        }
        if (value instanceof java.util.List<?> && !WorldIndexGuard.isGuarded(value)) {
            return new GuardedArrayList<>((java.util.List<?>) value);
        }
        if (value instanceof java.util.Collection<?> && !WorldIndexGuard.isGuarded(value)) {
            return new GuardedArrayList<>((java.util.Collection<?>) value);
        }
        return value;
    }

    @Override
    public void clear() {
        for (Object key : new ArrayList<>(super.keySet())) {
            if (!WorldIndexGuard.isProtected(key)) {
                super.remove(key);
            }
        }
    }
}
