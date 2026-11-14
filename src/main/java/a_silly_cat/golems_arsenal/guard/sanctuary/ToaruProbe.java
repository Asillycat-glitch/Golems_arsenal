package a_silly_cat.golems_arsenal.guard.sanctuary;

import a_silly_cat.golems_arsenal.compat.artifact.DamageAdmission;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.util.FakePlayerFactory;

import java.lang.reflect.Method;

/**
 * toaru（魔法禁书目录）测试刺激。
 *
 * <p>为什么需要它：toaru 的 {@code CombatMagic.canTarget} 会把"主人的傀儡"排除，
 * 所以正常玩法下它<b>永远打不到自己的傀儡</b>，没法用来验证守护层。</p>
 *
 * <p>这里用一个 <b>FakePlayer</b>（UUID 不是主人）当施法者，直接调它的伤害 API
 * {@code GungnirDamageApi.service().strike(caster, target, mode)}，让 toaru 走完整的
 * 自己的执行链（属性压制 → hurt → setHealth → health cell），目标就是我们的傀儡。</p>
 *
 * <p>全程反射，没有编译期依赖：toaru 没装时命令会报 "toaru 未加载"。</p>
 */
public final class ToaruProbe {

    private ToaruProbe() {
    }

    public static boolean loaded() {
        try {
            Class.forName("dev.othinus.toaru.GungnirDamageApi");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String strike(ServerLevel level, AbstractGolemEntity<?, ?> golem) {
        if (!loaded()) {
            return "toaru 未加载";
        }
        float before = DamageAdmission.rawHealth(golem);
        float maxBefore = golem.getMaxHealth();
        try {
            ServerPlayer caster = FakePlayerFactory.getMinecraft(level);
            caster.moveTo(golem.getX() + 1.0D, golem.getY(), golem.getZ(), 0.0F, 0.0F);

            Class<?> api = Class.forName("dev.othinus.toaru.GungnirDamageApi");
            Object service = api.getMethod("service").invoke(null);
            if (service == null) {
                return "toaru service=null";
            }
            Class<?> serviceItf = Class.forName("dev.othinus.toaru.DamageService");
            Class<?> modeCls = Class.forName("dev.othinus.toaru.GungnirMode");
            Object mode = modeCls.getEnumConstants()[0];
            Method strike = serviceItf.getMethod("strike", ServerPlayer.class, Entity.class, modeCls);
            Object result = strike.invoke(service, caster, golem, mode);

            float after = DamageAdmission.rawHealth(golem);
            return "mode=" + mode + " result=" + result
                    + " raw " + before + " -> " + after
                    + " max " + maxBefore + " -> " + golem.getMaxHealth();
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            return "失败: " + cause.getClass().getSimpleName() + ": " + cause.getMessage()
                    + " raw=" + before + " -> " + DamageAdmission.rawHealth(golem);
        }
    }
}
