package a_silly_cat.golems_arsenal.compat.jerotes;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.base.upgrade.GolemMerorAfterglowModifier;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;

import java.lang.reflect.Method;

/**
 * 把「装了莫厄余烬升级的傀儡」的攻击标记成<b>莫厄伤害</b>（联动路线 b）。
 *
 * <p>背景（已查证）：jerotes 判定"莫厄伤害"有两条判据 —— 近战武器在物品 tag
 * {@code jerotes:meror} 里，或攻击者实体在实体类型 tag {@code jerotes:meror_damage} 里
 * （{@code EntityAndItemFind} / {@code EntityAboutEvent}）。两条都是原版 tag，
 * 没法按"某台傀儡装了什么升级"来区分 —— 所以这里用他们官方留的第三个入口：
 * 监听 {@code JerotesMerorDamageEvent} 并调用 {@code setMerorDamage(true)}。</p>
 *
 * <p><b>为什么用反射</b>：jerotes 不是本模组的编译期依赖（build.gradle 里没有它），
 * 直接引用事件类会编译失败；而它又是可选依赖，硬加依赖会让本模组在没装 jerotes 的包里
 * 变得不可用。反射注册（按类名 + 方法名）两头都安全：没装时 {@code Class.forName} 失败，
 * 记一条日志就算了；装了时行为与直接写一样。这是本仓库对可选依赖的一贯做法
 * （见 {@code GolemStanceModifier.resolveL2Attribute}）。</p>
 */
public final class MerorDamageCompat {
    private static final String EVENT_CLASS = "com.jerotes.jerotes.forge.JerotesMerorDamageEvent";

    private static Method getDamageSource;
    private static Method setMerorDamage;

    /** mod 在场时由 {@code Golems_arsenal} 构造器调用；失败只记日志。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void register() {
        try {
            Class<?> type = Class.forName(EVENT_CLASS);
            getDamageSource = type.getMethod("getDamageSource");
            setMerorDamage = type.getMethod("setMerorDamage", boolean.class);
            MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
                    (Class) type, MerorDamageCompat::onMerorQuery);
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("meror damage compat not registered: {}", t.toString());
        }
    }

    private static void onMerorQuery(Object event) {
        try {
            if (!(getDamageSource.invoke(event) instanceof DamageSource source)) {
                return;
            }
            Entity attacker = source.getEntity();
            if (attacker instanceof AbstractGolemEntity<?, ?> golem
                    && GolemMerorAfterglowModifier.hasUpgrade(golem)) {
                setMerorDamage.invoke(event, true);
            }
        } catch (Throwable ignored) {
            // 反射调用失败不影响主流程：无非是这一击不算莫厄伤害。
        }
    }

    private MerorDamageCompat() {
    }
}
