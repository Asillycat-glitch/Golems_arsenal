package a_silly_cat.golems_arsenal.guard.index;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.ArrayList;

/** 具体类型包装：ChunkMap 的 entityMap 是 Int2ObjectOpenHashMap。 */
public class GuardedInt2ObjectOpenHashMap<V> extends Int2ObjectOpenHashMap<V> {

    public GuardedInt2ObjectOpenHashMap(Int2ObjectMap<V> source) {
        super(source);
    }

    @Override
    public V remove(int key) {
        if (WorldIndexGuard.isProtected(key)) {
            WorldIndexGuard.deny("Int2ObjectOpenHashMap.remove(int)", key);
            return null;
        }
        return super.remove(key);
    }

    @Override
    public V remove(Object key) {
        if (key instanceof Integer id && WorldIndexGuard.isProtected(id)) {
            WorldIndexGuard.deny("Int2ObjectOpenHashMap.remove(Object)", id);
            return null;
        }
        return super.remove(key);
    }

    @Override
    public boolean remove(int key, Object value) {
        if (WorldIndexGuard.isProtected(key)) {
            WorldIndexGuard.deny("Int2ObjectOpenHashMap.remove(int,value)", key);
            return false;
        }
        return super.remove(key, value);
    }

    @Override
    public void clear() {
        for (Integer key : new ArrayList<>(super.keySet())) {
            if (!WorldIndexGuard.isProtected(key)) {
                super.remove((int) key);
            }
        }
    }
}
