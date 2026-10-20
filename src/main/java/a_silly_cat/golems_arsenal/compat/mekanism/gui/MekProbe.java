package a_silly_cat.golems_arsenal.compat.mekanism.gui;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MEK GUI/渲染 API 的**反射探针**：把还没接上的那一半也解析出来，用到时直接取。
 *
 * <p>目标里列出的组件里，{@link MekReflect} 已经接了
 * （IGuiWrapper / GuiElement / GuiGasGauge / GaugeType / MekanismRenderer）；
 * 这里补齐剩下这些，并提供一个 {@link #report()} 把"解析成功/失败"打进日志，
 * 方便换 MEK 版本时立刻看出哪个签名变了。
 *
 * <p>补齐的：
 * <ul>
 *   <li>{@code GaugeInfo} / {@code GaugeOverlay} —— 仪表盘边框与刻度的贴图和尺寸</li>
 *   <li>{@code GuiSlot} —— MEK 的槽位显示组件（带 overlay 图标）</li>
 *   <li>{@code GuiNumberGauge} —— 只吃数字的仪表盘（不需要 tank 对象）</li>
 *   <li>{@code GuiInnerScreen} —— 带内嵌"屏幕"背景的面板</li>
 *   <li>{@code SyncableChemicalTank} / {@code MekanismContainer} —— 方块机器的储罐同步，
 *       实体用不了，但先解析出来备用（将来若把发射台改成方块，直接接）</li>
 * </ul>
 */
public final class MekProbe {

    private static final String P = "mekanism.";

    private static boolean tried;
    private static final Map<String, Boolean> RESULTS = new LinkedHashMap<>();

    private MekProbe() {
    }

    private static synchronized void init() {
        if (tried) {
            return;
        }
        tried = true;
        // 仪表盘边框 / 刻度
        probe("GaugeInfo.STANDARD", () -> {
            Class<?> c = Class.forName(P + "client.gui.element.gauge.GaugeInfo");
            c.getField("STANDARD").get(null);
            c.getMethod("getResourceLocation");
            c.getMethod("getSideWidth");
            c.getMethod("getSideHeight");
        });
        probe("GaugeOverlay.STANDARD", () -> {
            Class<?> c = Class.forName(P + "client.gui.element.gauge.GaugeOverlay");
            c.getField("STANDARD").get(null);
            c.getMethod("getWidth");
            c.getMethod("getHeight");
            c.getMethod("getBarOverlay");
        });
        // 槽位组件
        // GuiSlot 只是备用（我们现在没用它），所以只确认类存在、并且有接受 IGuiWrapper+坐标
        // 的构造器即可。之前把构造器签名写死成 5 个参数，实际不是，日志里就一直报 false。
        probe("GuiSlot", () -> {
            Class<?> c = Class.forName(P + "client.gui.element.slot.GuiSlot");
            Class<?> gui = Class.forName(P + "client.gui.IGuiWrapper");
            for (Constructor<?> ctor : c.getConstructors()) {
                Class<?>[] pt = ctor.getParameterTypes();
                if (pt.length >= 3 && pt[0] == gui) {
                    return;
                }
            }
            throw new NoSuchMethodException("GuiSlot 没有 (IGuiWrapper, ...) 的构造器");
        });
        // 数字仪表盘（不需要 tank）
        probe("GuiNumberGauge", () -> {
            Class<?> c = Class.forName(P + "client.gui.element.gauge.GuiNumberGauge");
            Class<?> handler = Class.forName(P + "client.gui.element.gauge.GuiNumberGauge$INumberInfoHandler");
            Class<?> type = Class.forName(P + "client.gui.element.gauge.GaugeType");
            Class<?> gui = Class.forName(P + "client.gui.IGuiWrapper");
            c.getConstructor(handler, type, gui, int.class, int.class);
            handler.getMethod("getIcon");
            handler.getMethod("getLevel");
            handler.getMethod("getScaledLevel");
            handler.getMethod("getText");
        });
        // 内嵌屏幕面板
        probe("GuiInnerScreen", () -> Class.forName(P + "client.gui.element.custom.GuiInnerScreen"));
        // 方块机器的储罐同步（实体用不了，先解析）
        probe("SyncableChemicalTank", () -> {
            Class<?> c = Class.forName(P + "common/capabilities/chemical/SyncableChemicalTank");
            c.getMethod("getType");
            c.getMethod("getStored");
        });
        probe("MekanismContainer.track", () -> {
            Class<?> c = Class.forName(P + "common.inventory.container.MekanismContainer");
            for (Method m : c.getMethods()) {
                if (m.getName().equals("track")) {
                    return;
                }
            }
            throw new NoSuchMethodException("track");
        });

        Golems_arsenal.LOGGER.info("[ga-gui] MEK GUI 探针: {}", RESULTS);
    }

    private interface Body {
        void run() throws Throwable;
    }

    private static void probe(String name, Body body) {
        try {
            body.run();
            RESULTS.put(name, Boolean.TRUE);
        } catch (Throwable t) {
            RESULTS.put(name, Boolean.FALSE);
        }
    }

    /** 某个 API 是否解析成功。 */
    public static boolean has(String name) {
        init();
        return Boolean.TRUE.equals(RESULTS.get(name));
    }

    /** 全部解析结果（只读）。 */
    public static Map<String, Boolean> report() {
        init();
        return Map.copyOf(RESULTS);
    }

    // ------------------------------------------------------------------
    // 用到时再调的构造器
    // ------------------------------------------------------------------

    /** MEK 标准仪表盘的内容区尺寸（16 × 58）。 */
    public static int[] standardGaugeContent() {
        return new int[]{MekReflect.standardGaugeWidth(), MekReflect.standardGaugeHeight()};
    }

    /** 造一个 MEK 的槽位显示组件（带 overlay 图标）。失败返回 null。 */
    @Nullable
    public static Object createGuiSlot(Object guiWrapper, int x, int y) {
        init();
        if (!has("GuiSlot")) {
            return null;
        }
        try {
            Class<?> c = Class.forName(P + "client.gui.element.slot.GuiSlot");
            Constructor<?> ctor = c.getConstructor(
                    Class.forName(P + "client.gui.IGuiWrapper"), int.class, int.class, int.class, int.class);
            return ctor.newInstance(guiWrapper, x, y, 18, 18);
        } catch (Throwable t) {
            return null;
        }
    }
}
