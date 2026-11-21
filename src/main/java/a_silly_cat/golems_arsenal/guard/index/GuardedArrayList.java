package a_silly_cat.golems_arsenal.guard.index;

import java.util.ArrayList;
import java.util.Collection;

/** 具体类型包装：ClassInstanceMultiMap 的 List 存储。 */
public class GuardedArrayList<E> extends ArrayList<E> {

    public GuardedArrayList(Collection<? extends E> source) {
        super(source);
    }

    @Override
    public boolean remove(Object value) {
        if (WorldIndexGuard.isProtected(value)) {
            WorldIndexGuard.deny("ArrayList.remove", value);
            return false;
        }
        return super.remove(value);
    }

    @Override
    public E remove(int index) {
        E value = size() > index && index >= 0 ? get(index) : null;
        if (WorldIndexGuard.isProtected(value)) {
            WorldIndexGuard.deny("ArrayList.remove(int)", value);
            return null;
        }
        return super.remove(index);
    }

    @Override
    public boolean removeAll(Collection<?> collection) {
        boolean changed = false;
        for (Object value : new ArrayList<>(this)) {
            if (!WorldIndexGuard.isProtected(value) && collection.contains(value)) {
                changed |= super.remove(value);
            }
        }
        return changed;
    }

    @Override
    public boolean retainAll(Collection<?> collection) {
        boolean changed = false;
        for (Object value : new ArrayList<>(this)) {
            if (!WorldIndexGuard.isProtected(value) && !collection.contains(value)) {
                changed |= super.remove(value);
            }
        }
        return changed;
    }

    @Override
    public void clear() {
        for (Object value : new ArrayList<>(this)) {
            if (!WorldIndexGuard.isProtected(value)) {
                super.remove(value);
            }
        }
    }
}
