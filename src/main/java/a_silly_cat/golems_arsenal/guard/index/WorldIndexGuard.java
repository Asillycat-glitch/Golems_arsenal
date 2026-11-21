package a_silly_cat.golems_arsenal.guard.index;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.guard.ProtectedEntityPolicy;
import a_silly_cat.golems_arsenal.guard.SoulRegistry;
import a_silly_cat.golems_arsenal.guard.sanctuary.Anchor;
import a_silly_cat.golems_arsenal.guard.sanctuary.RemovalGuard;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.TransientEntitySectionManager;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.client.multiplayer.ClientLevel;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * 世界索引的“数据层门”。
 *
 * <p>AV 0.7.10 的 {@code PurgeBatch.containers} 不调用 {@code EntityLookup.remove}，
 * 而是反射拿到 {@code byId/byUuid}、{@code knownUuids}、section、tickList 后直接
 * {@code Map.remove}/{@code Collection.remove}。任何方法 mixin 都看不见这条路径。</p>
 *
 * <p>这里把容器本身替换成“受保护 key 不可移除”的代理。它不按对手类名判断，
 * 只按 key/元素是否是受保护实体判断；重建作用域 {@link ProtectedEntityPolicy#inRebuild()}
 * 内放行。</p>
 */
public final class WorldIndexGuard {

    private static final Map<ServerLevel, Integer> INSTALLED = new WeakHashMap<>();
    private static final Map<String, Integer> DENIED = new HashMap<>();
    private static final String[] MANAGER_FIELDS = {"f_143244_", "entityManager"};
    private static final String[] LOOKUP_FIELDS = {"f_157494_", "visibleEntityStorage"};
    private static final String[] KNOWN_FIELDS = {"f_157491_", "knownUuids"};
    private static final String[] TICK_FIELDS = {"f_143243_", "entityTickList"};
    private static final String[] SECTION_STORAGE_FIELDS = {"f_157495_", "sectionStorage"};
    private static final String[] CHUNK_MAP_FIELDS = {"f_8325_", "chunkMap"};

    private WorldIndexGuard() {
    }

    public static void ensureAll(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            ensure(level);
        }
    }

    public static void ensure(ServerLevel level) {
        if (level == null || level.isClientSide) {
            return;
        }
        try {
            Object manager = read(level, MANAGER_FIELDS);
            if (manager == null) {
                return;
            }
            Object lookup = read(manager, LOOKUP_FIELDS);
            if (lookup != null) {
                wrapField(lookup, findField(lookup.getClass(), "f_156807_", "byId"),
                        Int2ObjectMap.class, Kind.INT_MAP, "lookup.byId");
                wrapField(lookup, findField(lookup.getClass(), "f_156808_", "byUuid"),
                        Map.class, Kind.MAP, "lookup.byUuid");
            }
            wrapField(manager, findField(manager.getClass(), KNOWN_FIELDS),
                    Set.class, Kind.SET, "knownUuids");

            Object tick = read(level, TICK_FIELDS);
            if (tick != null) {
                for (Field f : tick.getClass().getDeclaredFields()) {
                    if (Int2ObjectMap.class.isAssignableFrom(f.getType())) {
                        wrapField(tick, f, Int2ObjectMap.class, Kind.INT_MAP, "tickList." + f.getName());
                    }
                }
            }

            installChunkMap(level);
            installSections(manager);

            Integer old = INSTALLED.get(level);
            INSTALLED.put(level, old == null ? 1 : old + 1);
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("[IndexGuard] install failed: {}", t.toString());
        }
    }

    /** 客户端同层：ClientLevel 的 EntityLookup / section / tickList 也换成具体容器门。 */
    public static void installClient(ClientLevel level) {
        if (level == null) {
            return;
        }
        try {
            Object manager = findFieldByType(level, TransientEntitySectionManager.class);
            if (manager == null) {
                return;
            }
            Object lookup = findFieldByType(manager, EntityLookup.class);
            guardContainerFields(lookup, "client.lookup");
            guardContainerFields(manager, "client.manager");

            Object sectionStorage = findFieldByType(manager, EntitySectionStorage.class);
            Object sections = sectionStorage == null ? null : findFieldByType(sectionStorage, Long2ObjectMap.class);
            if (sections instanceof Map<?, ?> map) {
                for (Object section : map.values()) {
                    Object storage = findFieldByType(section, ClassInstanceMultiMap.class);
                    guardContainerFields(storage, "client.section");
                }
            }

            Object tick = findFieldByType(level, EntityTickList.class);
            guardContainerFields(tick, "client.tick");
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("[IndexGuard] client install failed: {}", t.toString());
        }
    }

    /**
     * 客户端“强制投影”入口：绕过 ClientLevel.addEntity 上其他模组的 cancel。
     * 只对受保护 entityId 使用；直接调 TransientEntitySectionManager.addEntity。
     */
    public static boolean addClientEntity(ClientLevel level, Entity entity) {
        if (level == null || entity == null) {
            return false;
        }
        try {
            if (level.getEntity(entity.getId()) == entity) {
                return true;
            }
            Object manager = findFieldByType(level, TransientEntitySectionManager.class);
            if (manager == null) {
                return false;
            }
            for (java.lang.reflect.Method method : manager.getClass().getMethods()) {
                if (!"m_157653_".equals(method.getName()) || method.getParameterCount() != 1) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    method.invoke(manager, entity);
                } catch (Throwable ignored) {
                }
                boolean added = level.getEntity(entity.getId()) == entity;
                if (added) {
                    Golems_arsenal.LOGGER.warn("[IndexGuard] client direct add id={} uuid={}",
                            entity.getId(), entity.getUUID());
                    return true;
                }
            }
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("[IndexGuard] client direct add failed: {}", t.toString());
        }
        return false;
    }

    private static void guardContainerFields(Object owner, String prefix) {
        if (owner == null) {
            return;
        }
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Class<?> type = field.getType();
                // 只包“通用接口”字段。LongSet/Long2ObjectMap/ObjectSet 这类
                // fastutil 专用接口必须跳过：换成 HashSet/HashMap 会丢接口，
                // 调用方 checkcast 时直接 IncompatibleClassChangeError。
                if (type == Int2ObjectMap.class) {
                    wrapField(owner, field, Int2ObjectMap.class, Kind.INT_MAP, prefix + "." + field.getName());
                } else if (type == Map.class) {
                    wrapField(owner, field, Map.class, Kind.MAP, prefix + "." + field.getName());
                } else if (type == Set.class) {
                    wrapField(owner, field, Set.class, Kind.SET, prefix + "." + field.getName());
                } else if (type == List.class || type == Collection.class) {
                    wrapField(owner, field, Collection.class, Kind.LIST, prefix + "." + field.getName());
                }
            }
        }
    }

    private static Object findFieldByType(Object owner, Class<?> type) {
        if (owner == null) {
            return null;
        }
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (type.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        return field.get(owner);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return null;
    }

    private static void installChunkMap(ServerLevel level) {
        try {
            ServerChunkCache cache = level.getChunkSource();
            Object chunkMap = read(cache, CHUNK_MAP_FIELDS);
            if (chunkMap == null) {
                return;
            }
            for (Field f : chunkMap.getClass().getDeclaredFields()) {
                if (Int2ObjectMap.class.isAssignableFrom(f.getType())) {
                    wrapField(chunkMap, f, Int2ObjectMap.class, Kind.INT_MAP, "chunkMap." + f.getName());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void installSections(Object manager) {
        try {
            Object storage = read(manager, SECTION_STORAGE_FIELDS);
            if (!(storage instanceof EntitySectionStorage<?> rawStorage)) {
                return;
            }
            Object map = read(storage, "f_156852_", "sections");
            if (!(map instanceof Map<?, ?> sections)) {
                return;
            }
            for (Object value : new ArrayList<>(sections.values())) {
                if (!(value instanceof EntitySection<?> section)) {
                    continue;
                }
                Object multi = read(section, "f_156827_", "storage");
                if (multi == null) {
                    continue;
                }
                wrapField(multi, findField(multi.getClass(), "f_13527_", "byClass"),
                        Map.class, Kind.MAP, "section.byClass");
                wrapField(multi, findField(multi.getClass(), "f_13529_", "allInstances"),
                        List.class, Kind.LIST, "section.all");
            }
        } catch (Throwable ignored) {
        }
    }

    private static void wrapField(Object owner, Field field, Class<?> iface, Kind kind, String name) {
        if (owner == null || field == null) {
            return;
        }
        try {
            Object current = UnsafeAccess.get(owner, field);
            if (current == null || isGuarded(current)) {
                return;
            }
            Object guarded;
            if (kind == Kind.INT_MAP) {
                if (current.getClass().getName().contains("LinkedOpenHashMap")) {
                    guarded = new GuardedInt2ObjectLinkedOpenHashMap<>((Int2ObjectMap<?>) current);
                } else {
                    guarded = new GuardedInt2ObjectOpenHashMap<>((Int2ObjectMap<?>) current);
                }
            } else if (kind == Kind.MAP) {
                guarded = new GuardedHashMap<>(castMap(current));
            } else if (kind == Kind.SET) {
                guarded = new GuardedHashSet<>(castSet(current));
            } else {
                guarded = new GuardedArrayList<>(castCollection(current));
            }
            UnsafeAccess.set(owner, field, guarded);
            if (guarded instanceof Map<?, ?> map) {
                guardMapValues(map, name);
            }
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("[IndexGuard] wrap {} failed: {}", name, t.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> castMap(Object value) {
        return (Map<Object, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Set<Object> castSet(Object value) {
        return (Set<Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> castCollection(Object value) {
        return (Collection<Object>) value;
    }

    public static boolean isGuarded(Object value) {
        return value instanceof GuardedInt2ObjectLinkedOpenHashMap<?>
                || value instanceof GuardedInt2ObjectOpenHashMap<?>
                || value instanceof GuardedHashMap<?, ?>
                || value instanceof GuardedHashSet<?>
                || value instanceof GuardedArrayList<?>;
    }

    /** Section 的 byClass map 的值是 List；只包 map 不包 value，iterator.remove 仍然能清。 */
    private static void guardMapValues(Map<?, ?> map, String name) {
        try {
            for (Map.Entry<?, ?> entry : new ArrayList<>(map.entrySet())) {
                Object value = entry.getValue();
                if (!(value instanceof Collection<?>) || isGuarded(value)) {
                    continue;
                }
                Object guard = value instanceof Set<?>
                        ? new GuardedHashSet<>(castSet(value))
                        : new GuardedArrayList<>(castCollection(value));
                @SuppressWarnings({"rawtypes", "unchecked"})
                Map.Entry raw = entry;
                raw.setValue(guard);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Object plainCopy(Object value) {
        String cls = value.getClass().getName();
        boolean jdk = cls.startsWith("java.util.") || cls.startsWith("it.unimi.dsi.fastutil.");
        if (value instanceof Int2ObjectMap<?> map) {
            return jdk ? map : new Int2ObjectOpenHashMap<>(map);
        }
        if (value instanceof Set<?> set) {
            return jdk ? set : new HashSet<>(set);
        }
        if (value instanceof List<?> list) {
            return jdk ? list : new ArrayList<>(list);
        }
        if (value instanceof Map<?, ?> map) {
            return jdk ? map : new HashMap<>(map);
        }
        if (value instanceof Collection<?> collection) {
            return jdk ? collection : new ArrayList<>(collection);
        }
        return value;
    }

    /**
     * Register a rebuilt projection without {@code addFreshEntity}/{@code addNewEntity}.
     * AV's {@code EntityGuardTransformer} gates both of those methods by stack, so a
     * rebuild that goes through them can never succeed while the remover is held.
     */
    public static boolean register(ServerLevel level, Entity entity) {
        if (level == null || entity == null || level.isClientSide) {
            return false;
        }
        try {
            ensure(level);
            Object manager = read(level, MANAGER_FIELDS);
            Object lookup = manager == null ? null : read(manager, LOOKUP_FIELDS);
            if (lookup == null) {
                return false;
            }
            Object byId = read(lookup, "f_156807_", "byId");
            Object byUuid = read(lookup, "f_156808_", "byUuid");
            Object known = read(manager, KNOWN_FIELDS);
            if (byId instanceof Int2ObjectMap<?> intMap) {
                @SuppressWarnings({"rawtypes", "unchecked"})
                Int2ObjectMap raw = intMap;
                raw.put(entity.getId(), entity);
            }
            if (byUuid instanceof Map<?, ?> uuidMap) {
                @SuppressWarnings({"rawtypes", "unchecked"})
                Map raw = uuidMap;
                raw.put(entity.getUUID(), entity);
            }
            if (known instanceof Set<?> set) {
                @SuppressWarnings({"rawtypes", "unchecked"})
                Set raw = set;
                raw.add(entity.getUUID());
            }

            Object sectionStorage = read(manager, SECTION_STORAGE_FIELDS);
            Object section = null;
            if (sectionStorage instanceof EntitySectionStorage<?> storage) {
                long pos = SectionPos.asLong(entity.blockPosition());
                section = getOrCreateSection(storage, pos);
                if (section != null) {
                    Object multi = read(section, "f_156827_", "storage");
                    if (multi != null) {
                        wrapField(multi, findField(multi.getClass(), "f_13527_", "byClass"),
                                Map.class, Kind.MAP, "section.byClass.register");
                        wrapField(multi, findField(multi.getClass(), "f_13529_", "allInstances"),
                                List.class, Kind.LIST, "section.all.register");
                    }
                    if (multi instanceof Collection<?> collection) {
                        @SuppressWarnings({"rawtypes", "unchecked"})
                        Collection raw = collection;
                        raw.add(entity);
                    }
                }
            }

            Object tick = read(level, TICK_FIELDS);
            if (tick != null) {
                for (Field f : tick.getClass().getDeclaredFields()) {
                    if (Int2ObjectMap.class.isAssignableFrom(f.getType())) {
                        Object map = UnsafeAccess.get(tick, f);
                        if (map instanceof Int2ObjectMap<?> intMap) {
                            @SuppressWarnings({"rawtypes", "unchecked"})
                            Int2ObjectMap raw = intMap;
                            raw.put(entity.getId(), entity);
                        }
                    }
                }
            }

            if (section != null) {
                try {
                    Class<?> cb = Class.forName("net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback");
                    Constructor<?> ctor;
                    Object callback;
                    try {
                        // inner (non-static) class: outer instance is the first constructor arg
                        ctor = cb.getDeclaredConstructor(
                                PersistentEntitySectionManager.class, EntityAccess.class,
                                long.class, EntitySection.class);
                        ctor.setAccessible(true);
                        callback = ctor.newInstance(manager, entity,
                                SectionPos.asLong(entity.blockPosition()), section);
                    } catch (Throwable inner) {
                        ctor = cb.getDeclaredConstructor(EntityAccess.class, long.class, EntitySection.class);
                        ctor.setAccessible(true);
                        callback = ctor.newInstance(entity, SectionPos.asLong(entity.blockPosition()), section);
                    }
                    entity.setLevelCallback((EntityInLevelCallback) callback);
                } catch (Throwable ignored) {
                }
            }
            try {
                Field added = Entity.class.getDeclaredField("isAddedToWorld");
                UnsafeAccess.setBoolean(entity, added, true);
            } catch (Throwable ignored) {
            }
            try {
                RemovalGuard.clearField(entity);
            } catch (Throwable ignored) {
            }
            // 不调用 LevelCallback.m_141985_：它会触发 Forge StartTracking 事件，
            // 第三方 mod（GolemOverslimeEvents）在“直接 reindex”这种没有 player 的
            // 上下文里会 NoSuchElementException，而且 ChunkMap.addEntity 只加一半。
            // 客户端投影统一走 ClientProjectionSync 的 remove+add 显式同步。
            Entity resolved = level.getEntity(entity.getId());
            if (resolved == entity) {
                Anchor.protect(entity.getUUID(), entity.getId());
                try {
                    a_silly_cat.golems_arsenal.guard.sanctuary.AgentBridge.onProtectedDataItem(entity);
                } catch (Throwable ignored) {
                }
                return true;
            }
            Golems_arsenal.LOGGER.warn("[IndexGuard] direct register not visible id={} uuid={} resolved={}",
                    entity.getId(), entity.getUUID(), resolved);
            return false;
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("[IndexGuard] direct register failed: {}", t.toString());
            return false;
        }
    }

    private static Object getOrCreateSection(EntitySectionStorage<?> storage, long pos) {
        try {
            Method m = storage.getClass().getMethod("m_156893_", long.class);
            m.setAccessible(true);
            return m.invoke(storage, pos);
        } catch (Throwable ignored) {
        }
        try {
            Object map = read(storage, "f_156852_", "sections");
            if (map instanceof Map<?, ?> sections) {
                return sections.get(pos);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static boolean isProtected(Object value) {
        if (value == null || ProtectedEntityPolicy.inRebuild()) {
            return false;
        }
        return isProtected0(value);
    }

    private static boolean isProtected0(Object value) {
        if (value instanceof Entity entity) {
            return ProtectedEntityPolicy.protects(entity);
        }
        if (value instanceof UUID uuid) {
            if (RemovalGuard.isLifecycleAllowed(uuid)) {
                return false;
            }
            AbstractGolemEntity<?, ?> golem = Anchor.liveByUuid(uuid);
            return golem != null ? ProtectedEntityPolicy.protects(golem) : Anchor.isProtectedUuid(uuid);
        }
        if (value instanceof Integer id) {
            if (RemovalGuard.isLifecycleAllowed(id)) {
                return false;
            }
            AbstractGolemEntity<?, ?> golem = Anchor.liveById(id);
            if (golem != null && RemovalGuard.isLifecycleAllowed(golem.getUUID())) {
                return false;
            }
            return golem != null ? ProtectedEntityPolicy.protects(golem) : Anchor.isProtectedEntityId(id);
        }
        if (value instanceof Map.Entry<?, ?> entry) {
            return isProtected0(entry.getKey()) || isProtected0(entry.getValue());
        }
        return false;
    }

    static void deny(String site, Object key) {
        String k = site + ":" + (key instanceof Entity e ? e.getUUID() : String.valueOf(key));
        int count;
        synchronized (DENIED) {
            count = DENIED.merge(k, 1, Integer::sum);
        }
        if (count <= 20) {
            Golems_arsenal.LOGGER.warn("[IndexGuard] DENIED {} key={}", site, key);
        }
    }

    private static Object read(Object owner, String... names) {
        if (owner == null) {
            return null;
        }
        Field f = findField(owner.getClass(), names);
        return f == null ? null : UnsafeAccess.get(owner, f);
    }

    private static Field findField(Class<?> type, String... names) {
        for (String name : names) {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    public static String status() {
        return "index.wrapped=" + INSTALLED.size();
    }

    public static void clear() {
        INSTALLED.clear();
    }

    private enum Kind {
        MAP, SET, LIST, INT_MAP
    }

    private static final class GuardHandler implements InvocationHandler {
        private final Object delegate;
        private final Kind kind;
        private final String name;

        private GuardHandler(Object delegate, Kind kind, String name) {
            this.delegate = delegate;
            this.kind = kind;
            this.name = name;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String m = method.getName();
            if ("toString".equals(m)) {
                return "guarded:" + name + ":" + delegate;
            }
            if ("hashCode".equals(m)) {
                return delegate.hashCode();
            }
            if ("equals".equals(m)) {
                return proxy == (args == null ? null : args[0])
                        || (args != null && args.length == 1 && delegate.equals(args[0]));
            }
            try {
                if ("computeIfAbsent".equals(m) && args != null && args.length == 2) {
                    Object value = method.invoke(delegate, args);
                    Object guard = guardCollection(value, name + ".computed");
                    if (guard != value) {
                        ((Map<Object, Object>) delegate).put(args[0], guard);
                    }
                    return guard;
                } else if ("put".equals(m) && args != null && args.length == 2) {
                    Object old = method.invoke(delegate, args);
                    Object guard = guardCollection(args[1], name + ".put");
                    if (guard != args[1]) {
                        ((Map<Object, Object>) delegate).put(args[0], guard);
                    }
                    return old;
                } else if ("remove".equals(m) && args != null && args.length >= 1) {
                    if (isProtected(args[0]) || (args.length >= 2 && isProtected(args[1]))) {
                        deny(name, args[0]);
                        return defaultValue(method.getReturnType());
                    }
                } else if ("removeAll".equals(m) && args != null && args.length == 1
                        && args[0] instanceof Collection<?> collection) {
                    return removeAll(collection);
                } else if ("retainAll".equals(m) && args != null && args.length == 1
                        && args[0] instanceof Collection<?> collection) {
                    return retainAll(collection);
                } else if ("clear".equals(m)) {
                    clearUnprotected();
                    return null;
                } else if ("removeIf".equals(m) && args != null && args.length == 1) {
                    return removeIf(args[0]);
                } else if ("iterator".equals(m)) {
                    return guardedIterator(delegate, iteratorOf(delegate), kind);
                } else if ("listIterator".equals(m)) {
                    return guardedListIterator(delegate, args == null || args.length == 0 ? 0 : (Integer) args[0]);
                } else if ("keySet".equals(m)) {
                    if (kind == Kind.INT_MAP) {
                        // Int2ObjectMap.keySet() returns IntSet; a plain Set proxy would
                        // fail the call-site checkcast. The scalar remove(int) gate still
                        // covers the useful side.
                        return method.invoke(delegate, args);
                    }
                    Object view = delegate.getClass().getMethod("keySet").invoke(delegate);
                    return guardedView(view, Set.class, Kind.SET, name + ".keys");
                } else if ("values".equals(m)) {
                    if (kind == Kind.INT_MAP) {
                        // values() return type is ObjectCollection, not Collection.
                        return method.invoke(delegate, args);
                    }
                    Object view = delegate.getClass().getMethod("values").invoke(delegate);
                    return guardedView(view, Collection.class, Kind.LIST, name + ".values");
                } else if ("entrySet".equals(m)) {
                    if (kind == Kind.INT_MAP) {
                        return method.invoke(delegate, args);
                    }
                    Object view = delegate.getClass().getMethod("entrySet").invoke(delegate);
                    return guardedView(view, Set.class, Kind.MAP, name + ".entries");
                } else if ("int2ObjectEntrySet".equals(m)) {
                    if (kind == Kind.INT_MAP) {
                        // ObjectSet's iterator is ObjectIterator, not Iterator.
                        return method.invoke(delegate, args);
                    }
                    Object view = delegate.getClass().getMethod("int2ObjectEntrySet").invoke(delegate);
                    return guardedView(view, ObjectSet.class, Kind.MAP, name + ".entries");
                }
            } catch (Throwable ignored) {
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private Object guardedView(Object view, Class<?> iface, Kind kind, String name) {
            if (view == null) {
                return null;
            }
            if (Proxy.isProxyClass(view.getClass())
                    && Proxy.getInvocationHandler(view) instanceof GuardHandler) {
                return view;
            }
            return Proxy.newProxyInstance(WorldIndexGuard.class.getClassLoader(),
                    new Class<?>[]{iface}, new GuardHandler(view, kind, name));
        }

        private Object guardCollection(Object value, String name) {
            if (!(value instanceof Collection<?>) || Proxy.isProxyClass(value.getClass())) {
                return value;
            }
            Class<?> iface = value instanceof Set<?> ? Set.class
                    : value instanceof List<?> ? List.class : Collection.class;
            return Proxy.newProxyInstance(WorldIndexGuard.class.getClassLoader(),
                    new Class<?>[]{iface}, new GuardHandler(value, Kind.LIST, name));
        }

        private Object guardedIterator(Object owner, Iterator<?> iterator, Kind kind) {
            if (iterator == null) {
                return null;
            }
            return Proxy.newProxyInstance(WorldIndexGuard.class.getClassLoader(),
                    new Class<?>[]{Iterator.class}, new IteratorHandler(iterator, kind, name));
        }

        private Object guardedListIterator(Object owner, int index) {
            try {
                Method m = delegate.getClass().getMethod("listIterator", int.class);
                Object it = m.invoke(delegate, index);
                return Proxy.newProxyInstance(WorldIndexGuard.class.getClassLoader(),
                        new Class<?>[]{java.util.ListIterator.class},
                        new ListIteratorHandler(it, name));
            } catch (Throwable ignored) {
                return null;
            }
        }

        private Object removeIf(Object predicate) {
            try {
                Iterator<?> it = iteratorOf(delegate);
                boolean changed = false;
                while (it.hasNext()) {
                    Object value = it.next();
                    if (isProtected(value)) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    java.util.function.Predicate<Object> p = (java.util.function.Predicate<Object>) predicate;
                    if (p.test(value)) {
                        it.remove();
                        changed = true;
                    }
                }
                return changed;
            } catch (Throwable ignored) {
                return false;
            }
        }

        private Object removeAll(Collection<?> collection) {
            boolean changed = false;
            Iterator<?> it = iteratorOf(delegate);
            while (it.hasNext()) {
                Object value = it.next();
                if (isProtected(value)) {
                    continue;
                }
                if (collection.contains(value)) {
                    it.remove();
                    changed = true;
                }
            }
            return changed;
        }

        private Object retainAll(Collection<?> collection) {
            boolean changed = false;
            Iterator<?> it = iteratorOf(delegate);
            while (it.hasNext()) {
                Object value = it.next();
                if (isProtected(value)) {
                    continue;
                }
                if (!collection.contains(value)) {
                    it.remove();
                    changed = true;
                }
            }
            return changed;
        }

        private void clearUnprotected() {
            try {
                Iterator<?> it = iteratorOf(delegate);
                while (it.hasNext()) {
                    Object value = it.next();
                    if (!isProtected(value)) {
                        it.remove();
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        private Iterator<?> iteratorOf(Object container) {
            try {
                if (container instanceof Int2ObjectMap<?> map) {
                    return map.int2ObjectEntrySet().iterator();
                }
                if (container instanceof Map<?, ?> map) {
                    return map.entrySet().iterator();
                }
                if (container instanceof Iterable<?> iterable) {
                    return iterable.iterator();
                }
            } catch (Throwable ignored) {
            }
            return java.util.Collections.emptyIterator();
        }

        private Object defaultValue(Class<?> type) {
            if (type == boolean.class) {
                return false;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == float.class) {
                return 0.0F;
            }
            if (type == double.class) {
                return 0.0D;
            }
            return null;
        }
    }

    private static final class IteratorHandler implements InvocationHandler {
        private final Iterator<?> delegate;
        private final Kind kind;
        private final String name;
        private Object last;

        private IteratorHandler(Iterator<?> delegate, Kind kind, String name) {
            this.delegate = delegate;
            this.kind = kind;
            this.name = name;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            try {
                if ("next".equals(method.getName())) {
                    last = delegate.next();
                    return last;
                }
                if ("remove".equals(method.getName())) {
                    if (isProtected(last)) {
                        deny(name + ".iterator", last);
                        return null;
                    }
                    delegate.remove();
                    return null;
                }
                if ("hasNext".equals(method.getName())) {
                    return delegate.hasNext();
                }
            } catch (Throwable ignored) {
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    private static final class ListIteratorHandler implements InvocationHandler {
        private final Object delegate;
        private final String name;
        private Object last;

        private ListIteratorHandler(Object delegate, String name) {
            this.delegate = delegate;
            this.name = name;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            try {
                if ("next".equals(method.getName())) {
                    last = invokeDelegate("next", new Class<?>[0], new Object[0]);
                    return last;
                }
                if ("previous".equals(method.getName())) {
                    last = invokeDelegate("previous", new Class<?>[0], new Object[0]);
                    return last;
                }
                if ("remove".equals(method.getName())) {
                    if (!isProtected(last)) {
                        invokeDelegate("remove", new Class<?>[0], new Object[0]);
                    }
                    return null;
                }
            } catch (Throwable ignored) {
            }
            return invokeDelegate(method.getName(), method.getParameterTypes(), args);
        }

        private Object invokeDelegate(String name, Class<?>[] types, Object[] args) throws Throwable {
            Method m = delegate.getClass().getMethod(name, types);
            m.setAccessible(true);
            try {
                return m.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
