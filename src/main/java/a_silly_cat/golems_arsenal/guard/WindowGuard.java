package a_silly_cat.golems_arsenal.guard;

import a_silly_cat.golems_arsenal.Config;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 第二层 enforce：光柱计时。
 *
 * <p>{@code PillarCd} 可以被 NBT/命令写成 0，从而绕过 600 tick 冷却。这里在服务端
 * 内存里记录上一次真正触发光柱的 gameTime；外部把 CD 清零后，最近一次触发仍在
 * 冷却内就补回 {@code last + PILLAR_CD_TICKS}，不再生成 24 根光柱。</p>
 *
 * <p>{@code PillarUntil} 被写成 {@code Long.MAX_VALUE} 时，读取层 {@code TimeWindow}
 * 会夹到一年；攻击入口额外检查原始 NBT，超过正常 bloom 窗口就直接忽略并清零。</p>
 */
public final class WindowGuard {

    private static final Map<UUID, Long> LAST_TRIGGER = new HashMap<>();
    private static volatile Boolean override;

    private WindowGuard() {
    }

    public static boolean enabled() {
        Boolean value = override;
        if (value != null) {
            return value;
        }
        try {
            return Config.DAMAGE_ADMISSION_WINDOW_ENFORCE.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static void setOverride(Boolean value) {
        override = value;
    }

    public static String status() {
        Boolean value = override;
        return "window=" + (enabled() ? "on" : "off")
                + (value == null ? "(config)" : "(runtime)");
    }

    public static long lastTrigger(UUID id) {
        return LAST_TRIGGER.getOrDefault(id, 0L);
    }

    public static void recordTrigger(UUID id, long now) {
        LAST_TRIGGER.put(id, now);
    }

    public static void reject(AbstractGolemEntity<?, ?> golem, String detail) {
        HealthLedger.noteWindowRejected(golem, detail);
    }

    public static void clear(UUID id) {
        LAST_TRIGGER.remove(id);
    }
}
