package a_silly_cat.golems_arsenal.util;

import net.minecraft.nbt.CompoundTag;

/**
 * 单个“时间窗”的值对象：绝对到期时刻 {@code until} + 该窗口重排时的长度 {@code cooldown}。
 *
 * <p>它只做三件事：读/写一个 NBT long、对异常值做 clamp、按 cooldown 重排。
 * 不碰世界、不碰事件，所以可以纯函数单测。</p>
 *
 * <p>约定：{@code until == 0} 表示“未排期”；{@code Long.MAX_VALUE} 是旧存档/外部写入的
 * “无限远”哨兵，clamp 成 {@link #MAX_WINDOW_TICKS}；{@code until < now} 视为已过期（0）。</p>
 */
public record TimeWindow(long until, long cooldown) {

    /** 哨兵上限：一年（20 tick/s × 3600 × 24 × 365）。 */
    public static final long MAX_WINDOW_TICKS = 20L * 60L * 60L * 24L * 365L;

    /** 负 cooldown 的最小修正值。 */
    public static final long MIN_COOLDOWN_TICKS = 1L;

    public static TimeWindow read(CompoundTag tag, String key, long cooldown) {
        return new TimeWindow(tag.getLong(key), cooldown);
    }

    /**
     * 把存储值修正成可比较的时间窗。
     * <ul>
     *   <li>{@code until == Long.MAX_VALUE} → {@code now + MAX_WINDOW_TICKS}（防溢出）；</li>
     *   <li>{@code cooldown < 0} → 到期时刻改为 {@code now + MIN_COOLDOWN_TICKS}；</li>
     *   <li>{@code until < now} → 0（已过期/未排期）。</li>
     * </ul>
     */
    public TimeWindow clamp(long now) {
        long nextUntil = until;
        long nextCooldown = cooldown;

        if (nextCooldown < 0L) {
            nextCooldown = MIN_COOLDOWN_TICKS;
            nextUntil = saturatingAdd(now, MIN_COOLDOWN_TICKS);
        }
        if (nextUntil == Long.MAX_VALUE) {
            nextUntil = saturatingAdd(now, MAX_WINDOW_TICKS);
        } else if (nextUntil < now) {
            nextUntil = 0L;
        }
        return new TimeWindow(nextUntil, nextCooldown);
    }

    public boolean scheduled() {
        return until != 0L;
    }

    public boolean active(long now) {
        return until != 0L && now < until;
    }

    public boolean due(long now) {
        return until != 0L && now >= until;
    }

    /** 从 {@code now} 开始按 cooldown 重排；负 cooldown 先修正到 MIN。 */
    public TimeWindow after(long now) {
        long cd = cooldown < 0L ? MIN_COOLDOWN_TICKS : cooldown;
        return new TimeWindow(saturatingAdd(now, cd), cd);
    }

    public void write(CompoundTag tag, String key) {
        tag.putLong(key, until);
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        if (right < 0L && left < Long.MIN_VALUE - right) {
            return Long.MIN_VALUE;
        }
        return left + right;
    }
}
