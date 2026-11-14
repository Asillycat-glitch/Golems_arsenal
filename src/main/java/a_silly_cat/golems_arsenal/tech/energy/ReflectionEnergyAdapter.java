package a_silly_cat.golems_arsenal.tech.energy;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.energy.IEnergyStorage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * 数据包驱动的**反射适配器**：只做“按字段名读一个值 / 写回一个值”。
 *
 * <p>为什么只做字段：需要反射的“带 FE 缓存的生物”绝大多数是把能量放在一个私有字段里
 * （int/long/float/double，或一个实现了 {@link IEnergyStorage} 的容器对象）。字段名由数据包
 * 给出，加载时解析一次并缓存 {@link Field}；运行期只做 {@code get/set}，不在每 tick 查类名。
 *
 * <p>支持的能量形状：
 * <ul>
 *   <li>{@code forge_energy_storage}：字段值是 Forge {@link IEnergyStorage}；</li>
 *   <li>{@code int / long / float / double}：字段是数字，必须给 {@code capacity} 或 {@code capacity_field}。</li>
 * </ul>
 *
 * <p>主人判定：默认由 {@link MinionEnergyAccess} 的 {@code OwnableEntity} / 模块化傀儡逻辑处理；
 * 如果实体没有这些接口，数据包可以给 {@code owner_field} 或 {@code owner_method}（UUID / Player /
 * Entity）。{@code charge_unowned=true} 时不看主人。
 */
public final class ReflectionEnergyAdapter implements MinionEnergyAccess.Access {

    public enum Kind {
        FORGE_ENERGY,
        INT,
        LONG,
        FLOAT,
        DOUBLE;

        public boolean isNumeric() {
            return this != FORGE_ENERGY;
        }

        public static Kind parse(String value) {
            return switch (value) {
                case "forge_energy_storage", "forge_energy", "fe" -> FORGE_ENERGY;
                case "int", "integer" -> INT;
                case "long" -> LONG;
                case "float" -> FLOAT;
                case "double" -> DOUBLE;
                default -> null;
            };
        }
    }

    private final Field field;
    private final Kind kind;
    private final long capacityConstant;
    private final Field capacityField;
    private final Field ownerField;
    private final Method ownerMethod;
    private final boolean chargeUnowned;
    private final String source;
    private boolean errorLogged;

    private ReflectionEnergyAdapter(Field field, Kind kind, long capacityConstant,
                                    Field capacityField, Field ownerField, Method ownerMethod,
                                    boolean chargeUnowned, String source) {
        this.field = field;
        this.kind = kind;
        this.capacityConstant = capacityConstant;
        this.capacityField = capacityField;
        this.ownerField = ownerField;
        this.ownerMethod = ownerMethod;
        this.chargeUnowned = chargeUnowned;
        this.source = source;
    }

    /**
     * 从一条数据包 JSON 构造适配器；字段/方法解析失败时返回 null 并打印一次警告。
     *
     * <p>JSON 形状见 {@code docs/MINION-ENERGY-DATAPACK.md}。
     */
    public static ReflectionEnergyAdapter parse(JsonObject json, Class<?> entityClass,
                                                ResourceLocation file, String entityId) {
        String fieldName = getString(json, "field", "");
        if (fieldName.isBlank()) {
            Golems_arsenal.LOGGER.warn("[minion_energy] {} ({}): missing 'field'", file, entityId);
            return null;
        }
        Field field = findField(entityClass, fieldName);
        if (field == null) {
            Golems_arsenal.LOGGER.warn("[minion_energy] {} ({}): field '{}' not found on {}",
                    file, entityId, fieldName, entityClass.getName());
            return null;
        }
        Kind kind = Kind.parse(getString(json, "energy", "forge_energy_storage"));
        if (kind == null) {
            Golems_arsenal.LOGGER.warn("[minion_energy] {} ({}): unknown energy kind '{}'",
                    file, entityId, getString(json, "energy", ""));
            return null;
        }
        if (kind == Kind.FORGE_ENERGY && !IEnergyStorage.class.isAssignableFrom(field.getType())) {
            Golems_arsenal.LOGGER.warn("[minion_energy] {} ({}): field '{}' is not IEnergyStorage",
                    file, entityId, fieldName);
            return null;
        }
        if (kind.isNumeric() && !isNumericType(field.getType())) {
            Golems_arsenal.LOGGER.warn("[minion_energy] {} ({}): field '{}' is not numeric",
                    file, entityId, fieldName);
            return null;
        }
        if (kind.isNumeric() && java.lang.reflect.Modifier.isFinal(field.getModifiers())) {
            Golems_arsenal.LOGGER.warn("[minion_energy] {} ({}): numeric field '{}' is final; cannot write it",
                    file, entityId, fieldName);
            return null;
        }

        long capacityConstant = json.has("capacity")
                ? json.get("capacity").getAsLong() : -1L;
        Field capacityField = null;
        String capacityFieldName = getString(json, "capacity_field", "");
        if (!capacityFieldName.isBlank()) {
            capacityField = findField(entityClass, capacityFieldName);
            if (capacityField == null || !isNumericType(capacityField.getType())) {
                Golems_arsenal.LOGGER.warn("[minion_energy] {} ({}): capacity_field '{}' not readable",
                        file, entityId, capacityFieldName);
                return null;
            }
        }
        if (kind.isNumeric() && capacityConstant < 0 && capacityField == null) {
            Golems_arsenal.LOGGER.warn("[minion_energy] {} ({}): numeric field needs capacity or capacity_field",
                    file, entityId);
            return null;
        }

        Field ownerField = null;
        Method ownerMethod = null;
        String ownerFieldName = getString(json, "owner_field", "");
        String ownerMethodName = getString(json, "owner_method", "");
        if (!ownerFieldName.isBlank()) {
            ownerField = findField(entityClass, ownerFieldName);
            if (ownerField == null) {
                Golems_arsenal.LOGGER.warn("[minion_energy] {} ({}): owner_field '{}' not found",
                        file, entityId, ownerFieldName);
                return null;
            }
        } else if (!ownerMethodName.isBlank()) {
            ownerMethod = findMethod(entityClass, ownerMethodName);
            if (ownerMethod == null) {
                Golems_arsenal.LOGGER.warn("[minion_energy] {} ({}): owner_method '{}' not found",
                        file, entityId, ownerMethodName);
                return null;
            }
        }

        boolean chargeUnowned = json.has("charge_unowned") && json.get("charge_unowned").getAsBoolean();
        return new ReflectionEnergyAdapter(field, kind, capacityConstant, capacityField,
                ownerField, ownerMethod, chargeUnowned, file + " / " + entityId);
    }

    // ------------------------------------------------------------------
    // Access
    // ------------------------------------------------------------------

    @Override
    public boolean hasEnergy(Entity entity) {
        try {
            if (this.kind == Kind.FORGE_ENERGY) {
                Object value = this.field.get(entity);
                if (!(value instanceof IEnergyStorage storage)) {
                    return false;
                }
                return storage.canReceive()
                        && storage.getMaxEnergyStored() > 0
                        && storage.getEnergyStored() < storage.getMaxEnergyStored();
            }
            long current = readNumeric(entity);
            long capacity = capacity(entity);
            return capacity > 0 && current < capacity;
        } catch (ReflectiveOperationException | RuntimeException e) {
            this.logError(e);
            return false;
        }
    }

    @Override
    public int insert(Entity entity, int amount, boolean simulate) {
        if (amount <= 0) {
            return 0;
        }
        try {
            if (this.kind == Kind.FORGE_ENERGY) {
                Object value = this.field.get(entity);
                if (!(value instanceof IEnergyStorage storage)) {
                    return 0;
                }
                return storage.receiveEnergy(amount, simulate);
            }
            long current = readNumeric(entity);
            long capacity = capacity(entity);
            long accepted = Math.min(amount, Math.max(0L, capacity - current));
            if (!simulate && accepted > 0) {
                writeNumeric(entity, current + accepted);
            }
            return (int) Math.min(Integer.MAX_VALUE, accepted);
        } catch (ReflectiveOperationException | RuntimeException e) {
            this.logError(e);
            return 0;
        }
    }

    @Override
    public boolean isOwnedBy(Entity entity, UUID player) {
        if (this.chargeUnowned) {
            return true;
        }
        try {
            Object value;
            if (this.ownerMethod != null) {
                value = this.ownerMethod.invoke(entity);
            } else if (this.ownerField != null) {
                value = this.ownerField.get(entity);
            } else {
                return false;
            }
            return matchesOwner(value, player);
        } catch (ReflectiveOperationException | RuntimeException e) {
            this.logError(e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 值读写
    // ------------------------------------------------------------------

    private long readNumeric(Entity entity) throws IllegalAccessException {
        Object value = this.field.get(entity);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("field is not a Number");
        }
        return number.longValue();
    }

    private void writeNumeric(Entity entity, long value) throws IllegalAccessException {
        Class<?> type = this.field.getType();
        if (type == int.class || type == Integer.class) {
            this.field.set(entity, (int) Math.min(Integer.MAX_VALUE, Math.max(Integer.MIN_VALUE, value)));
        } else if (type == long.class || type == Long.class) {
            this.field.set(entity, value);
        } else if (type == float.class || type == Float.class) {
            this.field.set(entity, (float) value);
        } else if (type == double.class || type == Double.class) {
            this.field.set(entity, (double) value);
        } else if (type == Number.class) {
            this.field.set(entity, value);
        } else {
            throw new IllegalStateException("unsupported numeric field type " + type);
        }
    }

    private long capacity(Entity entity) throws IllegalAccessException {
        if (this.capacityConstant >= 0) {
            return this.capacityConstant;
        }
        if (this.capacityField != null) {
            Object value = this.capacityField.get(entity);
            return value instanceof Number number ? number.longValue() : 0L;
        }
        return 0L;
    }

    private static boolean matchesOwner(Object value, UUID player) {
        if (value instanceof UUID uuid) {
            return player.equals(uuid);
        }
        if (value instanceof Player owner) {
            return player.equals(owner.getUUID());
        }
        if (value instanceof Entity owner) {
            return player.equals(owner.getUUID());
        }
        return false;
    }

    private void logError(Throwable error) {
        if (this.errorLogged) {
            return;
        }
        this.errorLogged = true;
        Golems_arsenal.LOGGER.warn("[minion_energy] reflection failed for {}: {}",
                this.source, error.toString());
    }

    // ------------------------------------------------------------------
    // 解析工具
    // ------------------------------------------------------------------

    private static String getString(JsonObject json, String key, String fallback) {
        return json.has(key) ? json.get(key).getAsString() : fallback;
    }

    private static boolean isNumericType(Class<?> type) {
        return type == int.class || type == Integer.class
                || type == long.class || type == Long.class
                || type == float.class || type == Float.class
                || type == double.class || type == Double.class
                || type == Number.class;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // 继续找父类
            } catch (RuntimeException e) {
                Golems_arsenal.LOGGER.warn("[minion_energy] cannot access field {}.{}: {}",
                        current.getName(), name, e.toString());
                return null;
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // 继续找父类
            } catch (RuntimeException e) {
                Golems_arsenal.LOGGER.warn("[minion_energy] cannot access method {}.{}: {}",
                        current.getName(), name, e.toString());
                return null;
            }
        }
        return null;
    }
}
