package a_silly_cat.golems_arsenal.guard.index;

import java.lang.reflect.Field;

/**
 * Small final-field helper.  World-index fields are final, so normal reflection
 * cannot swap the map/set instances on Java 17.  We only use this for our own
 * process-local structures.
 */
public final class UnsafeAccess {

    private static final Object UNSAFE;
    private static final java.lang.reflect.Method OBJECT_FIELD_OFFSET;
    private static final java.lang.reflect.Method PUT_OBJECT;
    private static final java.lang.reflect.Method PUT_BOOLEAN;
    private static final java.lang.reflect.Method GET_OBJECT;

    static {
        Object unsafe = null;
        java.lang.reflect.Method offset = null;
        java.lang.reflect.Method putObject = null;
        java.lang.reflect.Method putBoolean = null;
        java.lang.reflect.Method getObject = null;
        try {
            Class<?> c = Class.forName("sun.misc.Unsafe");
            Field f = c.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = f.get(null);
            offset = c.getMethod("objectFieldOffset", Field.class);
            putObject = c.getMethod("putObject", Object.class, long.class, Object.class);
            putBoolean = c.getMethod("putBoolean", Object.class, long.class, boolean.class);
            getObject = c.getMethod("getObject", Object.class, long.class);
        } catch (Throwable ignored) {
        }
        UNSAFE = unsafe;
        OBJECT_FIELD_OFFSET = offset;
        PUT_OBJECT = putObject;
        PUT_BOOLEAN = putBoolean;
        GET_OBJECT = getObject;
    }

    private UnsafeAccess() {
    }

    public static boolean available() {
        return UNSAFE != null && OBJECT_FIELD_OFFSET != null && PUT_OBJECT != null;
    }

    public static Object get(Object owner, Field field) {
        if (owner == null || field == null) {
            return null;
        }
        try {
            if (UNSAFE != null && GET_OBJECT != null) {
                long off = (Long) OBJECT_FIELD_OFFSET.invoke(UNSAFE, field);
                return GET_OBJECT.invoke(UNSAFE, owner, off);
            }
        } catch (Throwable ignored) {
        }
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void set(Object owner, Field field, Object value) {
        if (owner == null || field == null) {
            return;
        }
        try {
            if (UNSAFE != null && PUT_OBJECT != null) {
                long off = (Long) OBJECT_FIELD_OFFSET.invoke(UNSAFE, field);
                PUT_OBJECT.invoke(UNSAFE, owner, off, value);
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            field.setAccessible(true);
            field.set(owner, value);
        } catch (Throwable ignored) {
        }
    }

    public static void setBoolean(Object owner, Field field, boolean value) {
        if (owner == null || field == null) {
            return;
        }
        try {
            if (UNSAFE != null && PUT_BOOLEAN != null) {
                long off = (Long) OBJECT_FIELD_OFFSET.invoke(UNSAFE, field);
                PUT_BOOLEAN.invoke(UNSAFE, owner, off, value);
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            field.setAccessible(true);
            field.setBoolean(owner, value);
        } catch (Throwable ignored) {
        }
    }
}
