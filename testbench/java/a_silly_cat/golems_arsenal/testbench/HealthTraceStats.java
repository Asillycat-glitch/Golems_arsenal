package a_silly_cat.golems_arsenal.testbench;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * P0 的汇总账（PRIVATE TESTBENCH）。
 *
 * <p>这里只在内存里做计数与求和，不做判定之外的任何游戏操作。报告里的
 * {@code unpaired} 不是"外部写血"的定论 —— 它只表示"这次 setHealth/guardedData 写入
 * 没有在前后 1 tick 内匹配到一条 L2 finalize"。P1 的影子账本会用真正的 expected/actual 比较
 * 把"外部写"坐实。</p>
 */
final class HealthTraceStats {

    /** 一次 A/B 会话的元信息。 */
    record SessionInfo(String file, long lines, long startedAt, long now,
                       boolean trackAll, int sampleEvery, List<HealthTraceData.Tracked> tracked) {
    }

    static final class Agg {
        long n;
        long canceled;
        double sum;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;

        void add(float amount, boolean wasCanceled) {
            n++;
            sum += amount;
            min = Math.min(min, amount);
            max = Math.max(max, amount);
            if (wasCanceled) {
                canceled++;
            }
        }

        double avg() {
            return n == 0 ? 0.0D : sum / n;
        }
    }

    static final class RoleStats {
        final Map<String, Agg> events = new TreeMap<>();
        final Map<String, Long> writeOps = new LinkedHashMap<>();
        final Map<String, Long> writeCallers = new LinkedHashMap<>();
        final Map<String, Long> getCallers = new LinkedHashMap<>();
        long writes;
        /** 没配到 L2 的“伤害写入”（不含治疗/嵌套同步）。 */
        long unpaired;
        /** 治疗造成的顶层写入；它们本来就不会有 L2 pending。 */
        long healWrites;
        /** P1 影子账本抓到的“事件之外的血量变化”。 */
        long unexplained;
        /** P2 Guard：would-block / blocked / restored。 */
        long guardWouldBlock;
        long guardBlocked;
        long guardRestored;
        long rejected;
        long deaths;
        long removals;
        long clientRemoves;
        long maxHealthChanges;
        long getTotal;
        long getSampled;
        long l2Finalized;
        long l2Applied;
        double sumRaw;
        double sumBase;
        double sumFin;
        double sumKept;
        double sumApplied;
        double sumFinForApplied;
        double sumRatio;
        double dropSum;
        double healSum;
        long dropSamples;
        long healSamples;
    }

    private final Map<String, RoleStats> roles = new LinkedHashMap<>();
    private long lines;

    // ------------------------------------------------------------------ 记账

    void line() {
        lines++;
    }

    long lines() {
        return lines;
    }

    RoleStats roleOrNull(String role) {
        return roles.get(role);
    }

    private RoleStats role(String id) {
        return roles.computeIfAbsent(id == null ? "all" : id, k -> new RoleStats());
    }

    void event(String role, String event, String source, String type, float amount, boolean canceled) {
        role(role).events.computeIfAbsent(event + "|" + source + "|" + type, k -> new Agg())
                .add(amount, canceled);
    }

    void write(String role, String op, String caller, boolean wasRejected, boolean paired,
               boolean nested, boolean damageWrite, boolean healWrite) {
        RoleStats r = role(role);
        r.writes++;
        if (wasRejected) {
            r.rejected++;
        }
        // 只有“顶层的伤害写入”才算 unpaired；嵌套的 super.setHealth 同步、
        // 以及治疗写入都不应污染这个指标。
        if (!paired && !nested && damageWrite) {
            r.unpaired++;
        }
        if (!nested && healWrite) {
            r.healWrites++;
        }
        r.writeOps.merge(op == null ? "?" : op, 1L, Long::sum);
        r.writeCallers.merge(caller == null ? "?" : caller, 1L, Long::sum);
    }

    /** 每次 getHealth 都调用；sampled 表示这一条是否真的写进了日志。 */
    void read(String role, String caller, boolean sampled) {
        RoleStats r = role(role);
        r.getTotal++;
        if (sampled) {
            r.getSampled++;
            r.getCallers.merge(caller == null ? "?" : caller, 1L, Long::sum);
        }
    }

    void death(String role) {
        role(role).deaths++;
    }

    void removal(String role) {
        role(role).removals++;
    }

    void clientRemove(String role) {
        role(role).clientRemoves++;
    }

    void maxHealthChange(String role) {
        role(role).maxHealthChanges++;
    }

    void unexplained(String role) {
        role(role).unexplained++;
    }

    void guard(String role, String decision) {
        RoleStats r = role(role);
        switch (decision) {
            case "would_block", "would_restore" -> r.guardWouldBlock++;
            case "blocked" -> r.guardBlocked++;
            case "restored" -> r.guardRestored++;
            default -> {
            }
        }
    }

    void tick(String role, float before, float after) {
        RoleStats r = role(role);
        double delta = after - before;
        if (delta < 0.0D) {
            r.dropSum += -delta;
            r.dropSamples++;
        } else if (delta > 0.0D) {
            r.healSum += delta;
            r.healSamples++;
        }
    }

    void l2Finalized(String role, float raw, float base, float fin) {
        RoleStats r = role(role);
        r.l2Finalized++;
        if (Float.isFinite(raw)) {
            r.sumRaw += raw;
        }
        if (Float.isFinite(base)) {
            r.sumBase += base;
        }
        if (Float.isFinite(fin)) {
            r.sumFin += fin;
            if (base > 0.0F) {
                r.sumKept += fin / base;
            }
        }
    }

    void l2Applied(String role, float applied, float fin, float ratio) {
        RoleStats r = role(role);
        r.l2Applied++;
        if (Float.isFinite(applied)) {
            r.sumApplied += applied;
        }
        if (Float.isFinite(fin)) {
            r.sumFinForApplied += fin;
        }
        if (Float.isFinite(ratio)) {
            r.sumRatio += ratio;
        }
    }

    // ------------------------------------------------------------------ 报告

    List<String> summaryLines(SessionInfo info) {
        List<String> out = new ArrayList<>();
        out.add("[GA-Health] ===== P0 血量基线汇总（只读观测，Guard 未启用）=====");
        out.add(String.format(Locale.ROOT,
                "[GA-Health] file=%s lines=%d duration=%.1fs trackAll=%s sampleEvery=%d",
                info.file(), info.lines(), (info.now() - info.startedAt()) / 1000.0D,
                info.trackAll(), info.sampleEvery()));
        if (info.tracked().isEmpty()) {
            out.add("[GA-Health] 没有追踪任何实体。先 /ga health track control|test 对着目标执行。");
        } else {
            for (HealthTraceData.Tracked t : info.tracked()) {
                out.add("[GA-Health] tracked: " + HealthTraceData.describe(t));
            }
        }
        if (roles.isEmpty()) {
            out.add("[GA-Health] 没有任何事件/写入记录。");
            return out;
        }
        for (String id : orderedRoles()) {
            RoleStats r = roles.get(id);
            out.add("");
            out.add("[GA-Health] role=" + id + "  events=" + r.events.size()
                    + "  writes=" + r.writes + "  l2Finalized=" + r.l2Finalized);
            for (Map.Entry<String, Agg> e : r.events.entrySet()) {
                String[] p = splitEventKey(e.getKey());
                Agg a = e.getValue();
                out.add(String.format(Locale.ROOT,
                        "[GA-Health]   %-13s source=%-24s target=%-34s n=%3d avg=%8.3f min=%8.3f max=%8.3f cancelled=%d",
                        p[0], p[1], p[2], a.n, a.avg(),
                        a.min == Double.MAX_VALUE ? 0.0D : a.min, a.max, a.canceled));
            }
            out.add(String.format(Locale.ROOT,
                    "[GA-Health]   writes=%d unpairedDamage=%d healWrites=%d unexplained=%d rejected=%d deaths=%d removals=%d clientRemoves=%d maxHealthChanges=%d guard(would=%d blocked=%d restored=%d)",
                    r.writes, r.unpaired, r.healWrites, r.unexplained, r.rejected, r.deaths,
                    r.removals, r.clientRemoves, r.maxHealthChanges,
                    r.guardWouldBlock, r.guardBlocked, r.guardRestored));
            if (!r.writeOps.isEmpty()) {
                out.add("[GA-Health]   write ops: " + joinCounts(r.writeOps));
            }
            if (!r.writeCallers.isEmpty()) {
                out.add("[GA-Health]   write callers: " + topCounts(r.writeCallers, 8));
            }
            out.add(String.format(Locale.ROOT,
                    "[GA-Health]   getHealth total=%d sampled=%d callers=%s",
                    r.getTotal, r.getSampled,
                    r.getCallers.isEmpty() ? "-" : topCounts(r.getCallers, 6)));
            out.add(String.format(Locale.ROOT,
                    "[GA-Health]   actual drop avg=%.3f (%d samples)  heal avg=%.3f (%d samples)",
                    avg(r.dropSum, r.dropSamples), r.dropSamples,
                    avg(r.healSum, r.healSamples), r.healSamples));
            out.add(String.format(Locale.ROOT,
                    "[GA-Health]   L2 finalized=%d rawAvg=%.3f baseAvg=%.3f finAvg=%.3f keptAvg=%.3f"
                            + " | applied=%d appliedAvg=%.3f finOfAppliedAvg=%.3f ratioAvg=%.3f",
                    r.l2Finalized,
                    avg(r.sumRaw, r.l2Finalized), avg(r.sumBase, r.l2Finalized),
                    avg(r.sumFin, r.l2Finalized), avg(r.sumKept, r.l2Finalized),
                    r.l2Applied, avg(r.sumApplied, r.l2Applied),
                    avg(r.sumFinForApplied, r.l2Applied), avg(r.sumRatio, r.l2Applied)));
        }
        compare(out);
        out.add("");
        out.add("[GA-Health] 读法：P0 两边都没挂 Guard，control 与 test 的事件次数/数值/实际掉血应当一致；");
        out.add("[GA-Health]      writes 里 unpairedDamage（顶层伤害写入未配到 L2）在正常 L2 命中下应为 0；");
        out.add("[GA-Health]      healWrites 是回血/反治疗，不应该算作外部写血。若 unpairedDamage>0，先看 caller 与 before/after，");
        out.add("[GA-Health]      这可能是外部写血，也可能是 L2 走了我们没有埋点的写入路径（P1 会用影子账本坐实）。");
        return out;
    }

    private void compare(List<String> out) {
        RoleStats c = roles.get("control");
        RoleStats t = roles.get("test");
        if (c == null || t == null) {
            out.add("");
            out.add("[GA-Health] compare: 需要同时追踪 control 与 test 才能做 A/B；当前 control="
                    + (c != null) + " test=" + (t != null));
            return;
        }
        out.add("");
        out.add("[GA-Health] ===== control vs test（同类型、同伤害源；P0 两边都未挂 Guard，期望完全一致）=====");
        TreeSet<String> keys = new TreeSet<>();
        keys.addAll(c.events.keySet());
        keys.addAll(t.events.keySet());
        if (keys.isEmpty()) {
            out.add("[GA-Health]   两边都没有事件记录。");
        }
        for (String key : keys) {
            String[] p = splitEventKey(key);
            Agg ca = c.events.get(key);
            Agg ta = t.events.get(key);
            if (ca == null || ta == null) {
                out.add(String.format(Locale.ROOT, "[GA-Health]   DIFF %-13s source=%-24s target=%-34s 只有一侧有数据",
                        p[0], p[1], p[2]));
                continue;
            }
            boolean match = ca.n == ta.n
                    && closeEnough(ca.avg(), ta.avg())
                    && ca.canceled == ta.canceled;
            out.add(String.format(Locale.ROOT,
                    "[GA-Health]   %s %-13s source=%-24s target=%-34s control n=%3d avg=%8.3f | test n=%3d avg=%8.3f | cancelled %d/%d",
                    match ? "OK  " : "DIFF", p[0], p[1], p[2],
                    ca.n, ca.avg(), ta.n, ta.avg(), ca.canceled, ta.canceled));
        }
        boolean l2Match = c.l2Finalized == t.l2Finalized
                && closeEnough(avg(c.sumFin, c.l2Finalized), avg(t.sumFin, t.l2Finalized))
                && closeEnough(avg(c.sumApplied, c.l2Applied), avg(t.sumApplied, t.l2Applied));
        out.add(String.format(Locale.ROOT,
                "[GA-Health]   L2 %s finalized %d/%d finAvg %.3f/%.3f | applied %d/%d appliedAvg %.3f/%.3f",
                l2Match ? "OK  " : "DIFF",
                c.l2Finalized, t.l2Finalized, avg(c.sumFin, c.l2Finalized),
                avg(t.sumFin, t.l2Finalized),
                c.l2Applied, t.l2Applied, avg(c.sumApplied, c.l2Applied),
                avg(t.sumApplied, t.l2Applied)));
        boolean dropMatch = c.dropSamples == t.dropSamples
                && closeEnough(avg(c.dropSum, c.dropSamples), avg(t.dropSum, t.dropSamples));
        out.add(String.format(Locale.ROOT,
                "[GA-Health]   tick drop %s control %.3f (%d samples) vs test %.3f (%d samples)",
                dropMatch ? "OK  " : "DIFF",
                avg(c.dropSum, c.dropSamples), c.dropSamples,
                avg(t.dropSum, t.dropSamples), t.dropSamples));
        out.add(String.format(Locale.ROOT,
                "[GA-Health]   writes control %d (unpairedDamage %d, heal %d, rejected %d) vs test %d (unpairedDamage %d, heal %d, rejected %d)",
                c.writes, c.unpaired, c.healWrites, c.rejected,
                t.writes, t.unpaired, t.healWrites, t.rejected));
        boolean unexplainedMatch = c.unexplained == 0 && t.unexplained == 0;
        out.add(String.format(Locale.ROOT,
                "[GA-Health]   unexplained %s control %d vs test %d（正常 L2 两边都应为 0）",
                unexplainedMatch ? "OK  " : "DIFF", c.unexplained, t.unexplained));
    }

    /**
     * 0.030 vs 0.024 的绝对差只有 0.006，旧版会误判 OK；但在 fin≈0.03 时这是 20% 的相对差。
     * 判定要同时满足“绝对差 ≤0.01”和“相对差 ≤5%”。
     */
    private static boolean closeEnough(double a, double b) {
        double diff = Math.abs(a - b);
        double scale = Math.max(Math.max(Math.abs(a), Math.abs(b)), 1.0E-6D);
        return diff <= 0.01D && diff / scale <= 0.05D;
    }

    private List<String> orderedRoles() {
        List<String> order = new ArrayList<>();
        for (String preferred : List.of("control", "test", "all", "other")) {
            if (roles.containsKey(preferred)) {
                order.add(preferred);
            }
        }
        for (String id : roles.keySet()) {
            if (!order.contains(id)) {
                order.add(id);
            }
        }
        return order;
    }

    private static String[] splitEventKey(String key) {
        String[] p = key.split("\\|", 3);
        if (p.length == 3) {
            return p;
        }
        String[] ans = {"?", "?", "?"};
        System.arraycopy(p, 0, ans, 0, Math.min(p.length, ans.length));
        return ans;
    }

    // ------------------------------------------------------------------ 落盘

    /** /ga health unexplained 用：只打印账本相关的计数。 */
    List<String> unexplainedLines() {
        List<String> out = new ArrayList<>();
        if (roles.isEmpty()) {
            out.add("[GA-Health] 还没有记录。");
            return out;
        }
        for (String id : orderedRoles()) {
            RoleStats r = roles.get(id);
            out.add(String.format(Locale.ROOT,
                    "[GA-Health] role=%s unexplained=%d writes=%d unpairedDamage=%d healWrites=%d guard(would=%d blocked=%d restored=%d)",
                    id, r.unexplained, r.writes, r.unpaired, r.healWrites,
                    r.guardWouldBlock, r.guardBlocked, r.guardRestored));
        }
        return out;
    }

    JsonObject toJson(SessionInfo info) {
        JsonObject root = new JsonObject();
        root.addProperty("file", info.file());
        root.addProperty("lines", info.lines());
        root.addProperty("startedAt", info.startedAt());
        root.addProperty("now", info.now());
        root.addProperty("durationMs", info.now() - info.startedAt());
        root.addProperty("trackAll", info.trackAll());
        root.addProperty("sampleEvery", info.sampleEvery());
        JsonArray tracked = new JsonArray();
        for (HealthTraceData.Tracked t : info.tracked()) {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", t.uuid.toString());
            o.addProperty("role", t.role.id());
            o.addProperty("type", t.type);
            o.addProperty("name", t.name);
            o.addProperty("dim", t.dimension);
            tracked.add(o);
        }
        root.add("tracked", tracked);

        JsonArray roleArray = new JsonArray();
        for (Map.Entry<String, RoleStats> entry : roles.entrySet()) {
            RoleStats r = entry.getValue();
            JsonObject o = new JsonObject();
            o.addProperty("role", entry.getKey());
            o.addProperty("writes", r.writes);
            o.addProperty("unpairedDamage", r.unpaired);
            o.addProperty("healWrites", r.healWrites);
            o.addProperty("unexplained", r.unexplained);
            o.addProperty("guardWouldBlock", r.guardWouldBlock);
            o.addProperty("guardBlocked", r.guardBlocked);
            o.addProperty("guardRestored", r.guardRestored);
            o.addProperty("rejected", r.rejected);
            o.addProperty("deaths", r.deaths);
            o.addProperty("removals", r.removals);
            o.addProperty("clientRemoves", r.clientRemoves);
            o.addProperty("maxHealthChanges", r.maxHealthChanges);
            o.addProperty("getTotal", r.getTotal);
            o.addProperty("getSampled", r.getSampled);
            putNum(o, "dropAvg", avg(r.dropSum, r.dropSamples));
            putNum(o, "dropSamples", r.dropSamples);
            putNum(o, "healAvg", avg(r.healSum, r.healSamples));
            putNum(o, "healSamples", r.healSamples);
            o.addProperty("l2Finalized", r.l2Finalized);
            putNum(o, "rawAvg", avg(r.sumRaw, r.l2Finalized));
            putNum(o, "baseAvg", avg(r.sumBase, r.l2Finalized));
            putNum(o, "finAvg", avg(r.sumFin, r.l2Finalized));
            putNum(o, "keptAvg", avg(r.sumKept, r.l2Finalized));
            o.addProperty("l2Applied", r.l2Applied);
            putNum(o, "appliedAvg", avg(r.sumApplied, r.l2Applied));
            putNum(o, "appliedFinAvg", avg(r.sumFinForApplied, r.l2Applied));
            putNum(o, "appliedRatioAvg", avg(r.sumRatio, r.l2Applied));
            JsonArray events = new JsonArray();
            for (Map.Entry<String, Agg> e : r.events.entrySet()) {
                String[] p = splitEventKey(e.getKey());
                Agg a = e.getValue();
                JsonObject eo = new JsonObject();
                eo.addProperty("event", p[0]);
                eo.addProperty("source", p[1]);
                eo.addProperty("target", p[2]);
                eo.addProperty("n", a.n);
                putNum(eo, "avg", a.avg());
                putNum(eo, "min", a.min == Double.MAX_VALUE ? 0.0D : a.min);
                putNum(eo, "max", a.max);
                eo.addProperty("cancelled", a.canceled);
                events.add(eo);
            }
            o.add("events", events);
            o.add("writeOps", countsJson(r.writeOps));
            o.add("writeCallers", countsJson(r.writeCallers));
            o.add("getCallers", countsJson(r.getCallers));
            roleArray.add(o);
        }
        root.add("roles", roleArray);
        return root;
    }

    private static JsonObject countsJson(Map<String, Long> map) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Long> e : map.entrySet()) {
            o.addProperty(e.getKey(), e.getValue());
        }
        return o;
    }

    // ------------------------------------------------------------------ 小工具

    static void putNum(JsonObject o, String key, double value) {
        if (Double.isFinite(value)) {
            o.addProperty(key, value);
        } else {
            o.addProperty(key, String.valueOf(value));
        }
    }

    static void putNum(JsonObject o, String key, float value) {
        if (Float.isFinite(value)) {
            o.addProperty(key, value);
        } else {
            o.addProperty(key, String.valueOf(value));
        }
    }

    static double avg(double sum, long n) {
        return n == 0 ? 0.0D : sum / n;
    }

    private static String joinCounts(Map<String, Long> map) {
        StringBuilder sb = new StringBuilder();
        map.forEach((k, v) -> sb.append(sb.length() == 0 ? "" : ", ").append(k).append('=').append(v));
        return sb.toString();
    }

    private static String topCounts(Map<String, Long> map, int limit) {
        List<Map.Entry<String, Long>> list = new ArrayList<>(map.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(limit, list.size()); i++) {
            Map.Entry<String, Long> e = list.get(i);
            sb.append(sb.length() == 0 ? "" : ", ").append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
