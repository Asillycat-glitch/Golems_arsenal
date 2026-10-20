package a_silly_cat.golems_arsenal.tech.mekanism;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 用反射取 Mekanism 的激光伤害源 {@code MekanismDamageTypes.LASER.source(level)}。
 *
 * <p><b>为什么不直接编译期引用。</b>调用它的实体/修饰符是**无条件注册**的，如果常量池里出现
 * {@code mekanism.common.registries.MekanismDamageTypes}，那么没装 Mekanism 时任何执行到那一步的
 * 路径都会 {@code NoClassDefFoundError}。反射把这条依赖变成"查不到就退化成普通伤害"。
 *
 * <p>与辐射那边（{@code GolemRadiationGeneratorModifier}）的做法一致；两边各自持有一份静态缓存，
 * 因为解析目标不同（那边要 {@code IRadiationManager}，这边要伤害类型）。
 */
public final class MekanismLaserDamage {

    private static final Class<?> DAMAGE_TYPES_CLASS = resolveClass("mekanism.common.registries.MekanismDamageTypes");
    private static final Field LASER_FIELD = resolveField(DAMAGE_TYPES_CLASS, "LASER");
    private static final Method SOURCE_METHOD = resolveMethod(
            resolveClass("mekanism.common.registries.MekanismDamageTypes$MekanismDamageType"),
            "source", Level.class);

    /** MEK 的激光伤害源；Mekanism 不在场或签名变化时返回 {@code null}。 */
    public static DamageSource source(Level level) {
        if (LASER_FIELD == null || SOURCE_METHOD == null || level == null) {
            return null;
        }
        try {
            Object laser = LASER_FIELD.get(null);
            if (laser == null) {
                return null;
            }
            Object result = SOURCE_METHOD.invoke(laser, level);
            return result instanceof DamageSource source ? source : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** 是否成功解析到 MEK 激光（给调试命令/日志用）。 */
    public static boolean available() {
        return LASER_FIELD != null && SOURCE_METHOD != null;
    }

    private static Class<?> resolveClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private static Field resolveField(Class<?> owner, String name) {
        if (owner == null) {
            return null;
        }
        try {
            Field f = owner.getField(name);
            return java.lang.reflect.Modifier.isStatic(f.getModifiers()) ? f : null;
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static Method resolveMethod(Class<?> owner, String name, Class<?>... params) {
        if (owner == null) {
            return null;
        }
        try {
            return owner.getMethod(name, params);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private MekanismLaserDamage() {
    }
}
