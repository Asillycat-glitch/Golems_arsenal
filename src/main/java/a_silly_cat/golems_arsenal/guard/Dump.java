package a_silly_cat.golems_arsenal.guard;

import a_silly_cat.golems_arsenal.compat.artifact.DamageAdmission;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 三个数据面的快照：**血量 / entity 数据（全部 DataItem）/ 位置**。
 *
 * <p>为什么需要它：五把武器打完之后"变没变"必须能机械比对，不能靠肉眼。
 * 用法是 {@code /ga guard dump <半径>} → 打武器 → 再 dump → diff 两份文本。</p>
 *
 * <p>只读，不写任何东西。所有反射都在静态缓存里，失败返回 null 而不是抛。</p>
 */
public final class Dump {

    private static Field itemsByIdField;
    private static Field dataItemValueField;

    private Dump() {
    }

    /** 半径内的受保护傀儡快照，按 UUID 排序保证两次输出可比。 */
    public static List<String> snapshot(ServerLevel level, Vec3 center, double radius) {
        List<String> out = new ArrayList<>();
        List<AbstractGolemEntity<?, ?>> golems = new ArrayList<>();
        for (Entity entity : level.getEntities().getAll()) {
            if (entity instanceof AbstractGolemEntity<?, ?> golem
                    && golem.getOwnerUUID() != null
                    && golem.position().distanceTo(center) <= radius) {
                golems.add(golem);
            }
        }
        golems.sort(Comparator.comparing(Entity::getUUID));
        for (AbstractGolemEntity<?, ?> golem : golems) {
            out.add(a_silly_cat.golems_arsenal.guard.sanctuary.ReadShield.callTrusted(() -> line(golem)));
        }
        return out;
    }

    private static String line(AbstractGolemEntity<?, ?> golem) {
        UUID id = golem.getUUID();
        StringBuilder sb = new StringBuilder(256);
        sb.append("DUMP uuid=").append(id);
        sb.append(" type=").append(net.minecraft.world.entity.EntityType.getKey(golem.getType()));
        sb.append(" pos=").append(fmt(golem.getX())).append(',').append(fmt(golem.getY())).append(',').append(fmt(golem.getZ()));
        sb.append(" raw=").append(fmt(DamageAdmission.rawHealth(golem)));
        sb.append(" guarded=").append(fmt(guarded(golem)));
        sb.append(" read=").append(fmt(golem.getHealth()));
        sb.append(" ledger=").append(fmt(HealthLedger.expectedOf(golem)));
        sb.append(" upgrades=").append(upgrades(golem));
        sb.append(" data=").append(dataItems(golem));
        return sb.toString();
    }

    private static float guarded(AbstractGolemEntity<?, ?> golem) {
        try {
            return golem.getGuardedDataImpl();
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    private static int upgrades(AbstractGolemEntity<?, ?> golem) {
        try {
            return golem.getUpgrades() == null ? -1 : golem.getUpgrades().size();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * 全部 {@code DataItem} 的 {@code value}，按 id 升序。
     *
     * <p>这是"entity 数据"面：血量只是其中一个 id。AV 的 {@code entityDataModify}
     * 就是遍历这张表逐个写 {@code f_135391_}。</p>
     */
    private static String dataItems(AbstractGolemEntity<?, ?> golem) {
        try {
            if (itemsByIdField == null) {
                itemsByIdField = ObfuscationReflectionHelper.findField(
                        net.minecraft.network.syncher.SynchedEntityData.class, "f_135345_");
                itemsByIdField.setAccessible(true);
            }
            if (dataItemValueField == null) {
                dataItemValueField = ObfuscationReflectionHelper.findField(
                        net.minecraft.network.syncher.SynchedEntityData.DataItem.class, "f_135391_");
                dataItemValueField.setAccessible(true);
            }
            Object map = itemsByIdField.get(golem.getEntityData());
            if (!(map instanceof Map<?, ?> items) || items.isEmpty()) {
                return "none";
            }
            List<String> parts = new ArrayList<>(items.size());
            for (Map.Entry<?, ?> entry : items.entrySet()) {
                Object value = dataItemValueField.get(entry.getValue());
                parts.add(entry.getKey() + "=" + (value == null ? "null" : String.valueOf(value)));
            }
            parts.sort(Comparator.comparingInt(Dump::leadingInt));
            return String.join("|", parts);
        } catch (Throwable ignored) {
            return "unreadable";
        }
    }

    private static int leadingInt(String s) {
        int i = 0;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '-')) {
            i++;
        }
        try {
            return Integer.parseInt(s.substring(0, i));
        } catch (Throwable ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private static String fmt(float value) {
        if (!Float.isFinite(value)) {
            return String.valueOf(value);
        }
        return String.valueOf(Math.round(value * 1000.0F) / 1000.0F);
    }

    private static String fmt(double value) {
        if (!Double.isFinite(value)) {
            return String.valueOf(value);
        }
        return String.valueOf(Math.round(value * 1000.0D) / 1000.0D);
    }
}
