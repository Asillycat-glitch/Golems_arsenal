package a_silly_cat.golems_arsenal.guard.index;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;

/** 具体类型包装：knownUuids。 */
public class GuardedHashSet<E> extends HashSet<E> {

    public GuardedHashSet(Collection<? extends E> source) {
        super(source);
    }

    @Override
    public boolean remove(Object value) {
        if (WorldIndexGuard.isProtected(value)) {
            WorldIndexGuard.deny("HashSet.remove", value);
            return false;
        }
        return super.remove(value);
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
