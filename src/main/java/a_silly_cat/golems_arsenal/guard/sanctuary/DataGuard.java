package a_silly_cat.golems_arsenal.guard.sanctuary;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.compat.artifact.DamageAdmission;
import a_silly_cat.golems_arsenal.guard.HealthLedger;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 每 tick 的"收敛守卫"——<b>不依赖拦截</b>。
 *
 * <p>为什么要有它：agent 的字段门、mixin 的方法门、读侧撒谎，全都建立在"我们知道对方怎么写的"这个前提上。
 * 实测已经证明这个前提不成立（补丁打上了、方法没被执行，值照样被改）。所以这里换一个模型：</p>
 *
 * <blockquote>不管是谁写的、用什么写的、什么时候写的——<b>每 tick 对一次账，不一致就写回去</b>。</blockquote>
 *
 * <p>对三个数据面：血量（`DataItem.value` + `guardedData`）、位置（`f_19825_`）、以及 entity 数据
 * （只统计变化，不在默认配置下回写，避免和原版的正常同步打架）。</p>
 *
 * <p>计数全部在内存里，不依赖日志文件（日志可以被删、被截断、被改）。用 {@code /ga guard watch} 读。</p>
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID)
public final class DataGuard {

    /** 位置偏离超过这个距离就拉回最后一次正常坐标。 */
    private static final double POS_TOLERANCE = 8.0D;

    private static final Map<UUID, double[]> LAST_GOOD_POS = new HashMap<>();
    private static final Map<UUID, Map<Integer, Object>> LAST_DATA = new HashMap<>();
    private static final Map<UUID, Long> LAST_RESTORE_LOG = new HashMap<>();

    private static long ticks;
    private static long healthRestored;
    private static long guardedRestored;
    private static long posRestored;
    private static long dataChanged;
    private static boolean enabled = true;

    private DataGuard() {
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !enabled) {
            return;
        }
        if (Sanctuary.mode() != SanctuaryMode.ENFORCE) {
            return;
        }
        ticks++;
        for (AbstractGolemEntity<?, ?> golem : Anchor.liveSnapshot()) {
            try {
                converge(golem);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 对一只傀儡做一次收敛。
     *
     * <p>顺序很重要：先修位置（否则后面所有按位置做的判断都会瞎），再修血量，最后统计 entity 数据。</p>
     */
    private static void converge(AbstractGolemEntity<?, ?> golem) {
        UUID id = golem.getUUID();

        // ---------- 1) 位置 ----------
        double x = golem.getX();
        double y = golem.getY();
        double z = golem.getZ();
        double[] good = LAST_GOOD_POS.get(id);
        boolean finite = Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
        if (finite && (good == null || Math.abs(x - good[0]) + Math.abs(y - good[1]) + Math.abs(z - good[2]) < POS_TOLERANCE)) {
            if (good == null || Math.abs(x - good[0]) + Math.abs(y - good[1]) + Math.abs(z - good[2]) > 1.0E-6D) {
                LAST_GOOD_POS.put(id, new double[]{x, y, z});
            }
        } else if (good != null) {
            final double gx = good[0];
            final double gy = good[1];
            final double gz = good[2];
            SanctuaryScopes.runInternal(() -> golem.moveTo(gx, gy, gz, golem.getYRot(), golem.getXRot()));
            posRestored++;
            note(id, "[Sanctuary] converge_pos uuid=" + id + " from=(" + x + "," + y + "," + z + ")");
        } else if (!finite) {
            // 没有任何"最后正常坐标"：用主人位置兜底
            Entity owner = golem.getOwner();
            if (owner != null && Double.isFinite(owner.getX())) {
                final double gx = owner.getX();
                final double gy = owner.getY();
                final double gz = owner.getZ();
                SanctuaryScopes.runInternal(() -> golem.moveTo(gx, gy, gz, golem.getYRot(), golem.getXRot()));
                LAST_GOOD_POS.put(id, new double[]{gx, gy, gz});
                posRestored++;
                note(id, "[Sanctuary] converge_pos_owner uuid=" + id);
            }
        }

        // ---------- 2) 血量 ----------
        float expected = HealthLedger.expectedOf(golem);
        if (Float.isFinite(expected) && expected > 1.0F) {
            float raw = DamageAdmission.rawHealth(golem);
            if (!Float.isFinite(raw) || raw < expected - 0.5F) {
                boolean ok = DamageAdmission.writeRawHealth(golem, expected);
                if (ok) {
                    healthRestored++;
                    note(id, "[Sanctuary] converge_health uuid=" + id + " raw=" + raw + " -> " + expected);
                }
            }
            float guarded = guarded(golem);
            if (Float.isFinite(guarded) && guarded < expected - 0.5F) {
                SanctuaryScopes.runInternal(() -> golem.setGuardedDataImpl(expected, true, false));
                guardedRestored++;
                note(id, "[Sanctuary] converge_guarded uuid=" + id + " " + guarded + " -> " + expected);
            }
        }

        // ---------- 3) entity 数据（只统计，不回写） ----------
        Map<Integer, Object> now = readData(golem);
        if (now != null) {
            Map<Integer, Object> before = LAST_DATA.put(id, now);
            if (before != null) {
                for (Map.Entry<Integer, Object> e : now.entrySet()) {
                    Object old = before.get(e.getKey());
                    if (old != null && !old.equals(e.getValue())) {
                        dataChanged++;
                        note(id, "[Sanctuary] converge_data uuid=" + id + " id=" + e.getKey()
                                + " " + old + " -> " + e.getValue());
                        break; // 每 tick 每只只记一处，避免刷屏
                    }
                }
            }
        }
    }

    private static float guarded(AbstractGolemEntity<?, ?> golem) {
        try {
            return golem.getGuardedDataImpl();
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    private static java.lang.reflect.Field itemsField;
    private static java.lang.reflect.Field valueField;

    private static Map<Integer, Object> readData(AbstractGolemEntity<?, ?> golem) {
        try {
            if (itemsField == null) {
                itemsField = net.minecraftforge.fml.util.ObfuscationReflectionHelper.findField(
                        net.minecraft.network.syncher.SynchedEntityData.class, "f_135345_");
                itemsField.setAccessible(true);
            }
            if (valueField == null) {
                valueField = net.minecraftforge.fml.util.ObfuscationReflectionHelper.findField(
                        net.minecraft.network.syncher.SynchedEntityData.DataItem.class, "f_135391_");
                valueField.setAccessible(true);
            }
            Object map = itemsField.get(golem.getEntityData());
            if (!(map instanceof Map<?, ?> items)) {
                return null;
            }
            Map<Integer, Object> out = new HashMap<>(items.size());
            for (Map.Entry<?, ?> entry : items.entrySet()) {
                if (entry.getKey() instanceof Integer key) {
                    out.put(key, valueField.get(entry.getValue()));
                }
            }
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void note(UUID id, String line) {
        // 日志节流：同一只傀儡 20 tick 一条，但计数永远累加（计数才是证据）。
        long now = ticks;
        Long last = LAST_RESTORE_LOG.get(id);
        if (last != null && now - last < 20) {
            return;
        }
        LAST_RESTORE_LOG.put(id, now);
        Golems_arsenal.LOGGER.warn(line);
    }

    public static String statusLine() {
        return "[sanctuary] converge=" + (enabled ? "on" : "off")
                + " ticks=" + ticks
                + " health=" + healthRestored
                + " guarded=" + guardedRestored
                + " pos=" + posRestored
                + " dataChanges=" + dataChanged;
    }

    public static void rotateIdentity(UUID old, UUID fresh) {
        double[] pos = LAST_GOOD_POS.remove(old);
        if (pos != null) {
            LAST_GOOD_POS.put(fresh, pos);
        }
        Map<Integer, Object> data = LAST_DATA.remove(old);
        if (data != null) {
            LAST_DATA.put(fresh, data);
        }
        Long log = LAST_RESTORE_LOG.remove(old);
        if (log != null) {
            LAST_RESTORE_LOG.put(fresh, log);
        }
    }

    public static void reset() {
        ticks = 0;
        healthRestored = 0;
        guardedRestored = 0;
        posRestored = 0;
        dataChanged = 0;
        LAST_GOOD_POS.clear();
        LAST_DATA.clear();
        LAST_RESTORE_LOG.clear();
    }
}
