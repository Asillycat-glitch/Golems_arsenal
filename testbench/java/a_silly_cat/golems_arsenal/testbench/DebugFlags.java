package a_silly_cat.golems_arsenal.testbench;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 测试台的开关状态（PRIVATE TESTBENCH）。
 *
 * <p>存在存档级的 {@link SavedData} 里（服务器权威、局内即时生效、重启会读回），
 * 但 {@link #resetAll()} 会在每次服务器启动时被调用 —— 保证"忘了关"不会污染正常游玩。</p>
 */
public class DebugFlags extends SavedData {

    public static final String DATA_NAME = "golems_arsenal_testbench";

    /** 所有可开关的杠杆（组 2 / 3 / 4 / 光柱）。 */
    public static final List<String> LEVERS = List.of(
            "uncancel",          // 反取消：把 LivingAttackEvent 的取消撤销（破免疫）
            "floor_attack",      // hurt 阶段 PRE_NONLINEAR floor = 攻击力属性
            "floor_hurt",        // postHurt：把值拉回 preDamage
            "floor_dealt",       // dealt 阶段 PRE_NONLINEAR floor = preDamage
            "distinct_ids",      // 多段每段换一个伤害类型 id（破适应）
            "same_tick",         // 多段是否同一 tick 结算（测无敌帧）
            "strip_enchants",    // 摘掉目标装备的附魔 NBT（破保护 IV）
            "clear_iframe",      // 命中后把目标的 invulnerableTime 清零（mgdp quick_strike 与 JVG 莫厄武器用的手法）
            "write_health",      // 直接写血（破外层钳制与速率门）
            "trim_max_health",   // 砍最大生命属性（观察是否连带裁剪当前血量）
            "light_pillar_onhit" // 光柱伤害也吃这些杠杆（on-hit 给光柱）
    );

    /** 只有 -Ptestbench 构建里才会被读到；默认全关。 */
    public boolean enabled;
    /** 是否允许玩家手持测试武器也触发。按需求默认开。 */
    public boolean allowPlayer = true;
    /** 当前档位。 */
    public TestbenchTiers tier = TestbenchTiers.PLAIN;
    /** 杠杆开关。 */
    public final Map<String, Boolean> levers = new LinkedHashMap<>();
    /** 额外段数（0 = 只打一下）。 */
    public int extraHits;
    /** END 层返还比例 p（0 = 关）。 */
    public double restitution;
    /** 摘附魔件数（0 = 关）。 */
    public int stripPieces = 1;
    /** 摘附魔持续秒数。 */
    public int stripSeconds = 30;
    /** 写血：每次命中直接扣掉目标最大生命的这个比例（0 = 关）。 */
    public double writePct;
    /** 写血只在目标血量低于这个比例时执行（默认 1.0 = 必定执行，方便第一轮就测到）。 */
    public double writeBelowPct = 1.0D;
    /** 砍最大生命：每次命中砍掉基础最大生命的这个比例（0 = 关）。 */
    public double trimPct;
    /** 汇总打印阈值（记录多少条命中打印一次）。 */
    public int reportEvery = 20;

    public DebugFlags() {
        for (String lever : LEVERS) {
            levers.put(lever, false);
        }
    }

    // ------------------------------------------------------------------ 存取

    public static DebugFlags get(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        return server.overworld().getDataStorage()
                .computeIfAbsent(DebugFlags::load, DebugFlags::new, DATA_NAME);
    }

    public static DebugFlags load(CompoundTag tag) {
        DebugFlags ans = new DebugFlags();
        ans.enabled = tag.getBoolean("enabled");
        ans.allowPlayer = !tag.contains("allowPlayer") || tag.getBoolean("allowPlayer");
        TestbenchTiers tier = TestbenchTiers.byId(tag.getString("tier"));
        ans.tier = tier == null ? TestbenchTiers.PLAIN : tier;
        for (String lever : LEVERS) {
            ans.levers.put(lever, tag.getBoolean("lever_" + lever));
        }
        ans.extraHits = tag.getInt("extraHits");
        ans.restitution = tag.getDouble("restitution");
        ans.stripPieces = tag.contains("stripPieces") ? tag.getInt("stripPieces") : 1;
        ans.stripSeconds = tag.contains("stripSeconds") ? tag.getInt("stripSeconds") : 30;
        ans.writePct = tag.getDouble("writePct");
        ans.writeBelowPct = tag.contains("writeBelowPct") ? tag.getDouble("writeBelowPct") : 1.0D;
        ans.trimPct = tag.getDouble("trimPct");
        ans.reportEvery = tag.contains("reportEvery") ? tag.getInt("reportEvery") : 20;
        return ans;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean("enabled", enabled);
        tag.putBoolean("allowPlayer", allowPlayer);
        tag.putString("tier", tier.id());
        for (Map.Entry<String, Boolean> entry : levers.entrySet()) {
            tag.putBoolean("lever_" + entry.getKey(), entry.getValue());
        }
        tag.putInt("extraHits", extraHits);
        tag.putDouble("restitution", restitution);
        tag.putInt("stripPieces", stripPieces);
        tag.putInt("stripSeconds", stripSeconds);
        tag.putDouble("writePct", writePct);
        tag.putDouble("writeBelowPct", writeBelowPct);
        tag.putDouble("trimPct", trimPct);
        tag.putInt("reportEvery", reportEvery);
        return tag;
    }

    // ------------------------------------------------------------------ 操作

    /** 每次服务器启动强制全关，避免把测试内容带进正常游玩。 */
    public void resetAll() {
        enabled = false;
        allowPlayer = true;
        tier = TestbenchTiers.PLAIN;
        for (String lever : LEVERS) {
            levers.put(lever, false);
        }
        extraHits = 0;
        restitution = 0.0D;
        stripPieces = 1;
        stripSeconds = 30;
        writePct = 0.0D;
        writeBelowPct = 1.0D;
        trimPct = 0.0D;
        reportEvery = 20;
        setDirty();
    }

    public boolean lever(String id) {
        return Boolean.TRUE.equals(levers.get(id));
    }

    public void setLever(String id, boolean value) {
        levers.put(id, value);
        setDirty();
    }

    /** 当前生效的杠杆列表（用于测量标记与显示）。 */
    public String activeLeverTag() {
        StringBuilder sb = new StringBuilder();
        for (String lever : LEVERS) {
            if (lever(lever)) {
                sb.append(sb.length() == 0 ? "" : "+").append(lever);
            }
        }
        if (extraHits > 0) {
            sb.append(sb.length() == 0 ? "" : "+").append("hits=").append(extraHits + 1);
        }
        if (restitution > 0) {
            sb.append(sb.length() == 0 ? "" : "+").append("end=")
                    .append(String.format(Locale.ROOT, "%.2f", restitution));
        }
        if (stripPieces > 0 && lever("strip_enchants")) {
            sb.append(sb.length() == 0 ? "" : "+").append("strip=").append(stripPieces);
        }
        if (writePct > 0 && lever("write_health")) {
            sb.append(sb.length() == 0 ? "" : "+").append("write=")
                    .append(String.format(Locale.ROOT, "%.2f", writePct));
        }
        if (trimPct > 0 && lever("trim_max_health")) {
            sb.append(sb.length() == 0 ? "" : "+").append("trim=")
                    .append(String.format(Locale.ROOT, "%.2f", trimPct));
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    /** 把当前状态导出成一行可粘贴的文本，方便复现某一轮测试条件。 */
    public String exportLine() {
        StringBuilder sb = new StringBuilder("GA-TESTBENCH ")
                .append("enabled=").append(enabled)
                .append(" tier=").append(tier.id())
                .append(" allowPlayer=").append(allowPlayer)
                .append(" extraHits=").append(extraHits)
                .append(" restitution=").append(String.format(Locale.ROOT, "%.2f", restitution))
                .append(" stripPieces=").append(stripPieces)
                .append(" stripSeconds=").append(stripSeconds)
                .append(" writePct=").append(String.format(Locale.ROOT, "%.3f", writePct))
                .append(" writeBelowPct=").append(String.format(Locale.ROOT, "%.2f", writeBelowPct))
                .append(" trimPct=").append(String.format(Locale.ROOT, "%.3f", trimPct))
                .append(" reportEvery=").append(reportEvery);
        for (String lever : LEVERS) {
            sb.append(' ').append(lever).append('=').append(lever(lever));
        }
        return sb.toString();
    }
}
