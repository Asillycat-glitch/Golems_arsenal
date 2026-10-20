package a_silly_cat.golems_arsenal.client;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端"三连击"演出状态（每只傀儡一条，<b>而且允许同时挂着多段</b>）。
 * <p>
 * 为什么是"多段"：服务端按<b>起手那一刻起、每 {@code LINK_TICKS} 开一段</b>来排，
 * 而每段动画本身有 4~7 tick，所以相邻两段<b>必然重叠</b> —— 场上会同时出现两三把刀，
 * 这是预期效果（衔接更连贯），不是 bug。
 * <p>
 * 服务端只负责说清三件事：<b>谁、第几段、什么时候开始</b>；进度由客户端按 tick 自己推，
 * 于是特效永远挂在"渲染插值之后的傀儡"身上，不会出现"傀儡动了、刀光留在原地"的错位。
 */
public final class ClientComboData {

    public static final class ComboState {
        /** 客户端 gameTime。 */
        public final long startTick;
        /** 播第几段（{@link GolemComboStages#STAGES} 的下标）。 */
        public final int stageIndex;

        public ComboState(long startTick, int stageIndex) {
            this.startTick = startTick;
            this.stageIndex = stageIndex;
        }
    }

    private static final Map<Integer, List<ComboState>> COMBOS = new ConcurrentHashMap<>();

    /** 收到服务端的某一"段"：挂上去，跟已经在演的段并存。 */
    public static void start(int entityId, int stageIndex) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || GolemComboStages.stage(stageIndex) == null) {
            return;
        }
        long now = mc.level.getGameTime();
        prune(now);
        COMBOS.computeIfAbsent(entityId, id -> new ArrayList<>())
                .add(new ComboState(now, stageIndex));
    }

    /**
     * 该傀儡当前<b>还在演</b>的所有段（渲染层用）。顺手把播完的清掉，
     * 所以"傀儡演到一半死了/走远了"的残留也不会积着。
     */
    public static List<ComboState> active(int entityId, long now) {
        List<ComboState> list = COMBOS.get(entityId);
        if (list == null) {
            return List.of();
        }
        list.removeIf(state -> {
            GolemComboStages.Stage stage = GolemComboStages.stage(state.stageIndex);
            return stage == null || now - state.startTick >= stage.ticks();
        });
        if (list.isEmpty()) {
            COMBOS.remove(entityId);
            return List.of();
        }
        return list;
    }

    /** 清掉明显过期的记录（比如整段都没机会被渲染到的情况）。 */
    public static void prune(long now) {
        int maxAge = GolemComboStages.maxTicks() + 40;
        COMBOS.values().forEach(list -> list.removeIf(state -> now - state.startTick > maxAge));
        COMBOS.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    public static void clear() {
        COMBOS.clear();
    }

    private ClientComboData() {
    }
}
