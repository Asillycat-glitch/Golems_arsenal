package a_silly_cat.golems_arsenal.compat.mekanism.gui;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;

/**
 * Mekanism GUI 的**反射桥接层**（实体版）。
 *
 * <p><b>为什么用反射而不是直接 import</b>：
 * <ul>
 *   <li>我们的界面是 l2 的 {@code BaseContainerScreen}，不是 MEK 的 {@code GuiMekanism}，
 *       所以这些组件对我们来说是"借用"，不该在编译期硬绑；</li>
 *   <li>MEK 的这几个类签名在 1.19→1.20 之间改过（比如 {@code IGuiWrapper} 的方法增减），
 *       反射 + 延迟解析可以在版本不符时**降级成"没有这个组件"**而不是类加载崩溃；</li>
 *   <li>不反射的话，{@code RocketScreen} 一旦引用 MEK 的类，没装 MEK 时整个界面类都加载不了。</li>
 * </ul>
 *
 * <p><b>完整的 API 清单见</b> {@code docs/MEK-GUI-REFLECTION.md}（由 javap 从 mekanism.jar 直出）。
 *
 * <p><b>已经验证过的事实</b>（写在这里免得以后重复查）：
 * <pre>
 *   IGuiWrapper           只有一个抽象方法 getFont()，其余全是 default
 *                         → 我们的 Screen 只要有 getFont/getLeft/getTop 就能当它用
 *   GuiElement            extends AbstractWidget → render(GuiGraphics,int,int,float)
 *   GaugeOverlay.STANDARD 内容区 16 x 58（+1px 边框 = 18 x 60）
 *   GaugeInfo.STANDARD    "normal.png"，九宫格，side = 2
 *   GuiChemicalGauge      getIcon()  = MekanismRenderer.getChemicalTexture(气体)
 *                         applyRenderColor() = MekanismRenderer.color(g, 气体)
 *                         getScaledLevel()   = round(比例 * (height - 2))
 *   MekanismRenderer      color(GuiGraphics, Chemical) / resetColor(GuiGraphics)
 *                         getChemicalTexture(Chemical) → TextureAtlasSprite
 * </pre>
 */
public final class MekReflect {

    private static final String P = "mekanism.";

    private static boolean tried;
    private static boolean ok;

    // 类
    private static Class<?> clsGuiElement;
    private static Class<?> clsGuiGasGauge;
    private static Class<?> clsGaugeType;
    private static Class<?> clsMekanismRenderer;
    private static Class<?> clsChemical;
    private static Class<?> clsIGasTank;
    private static Class<?> clsMekanismGases;

    // 成员
    private static Method mRender;              // GuiElement#render(GuiGraphics,int,int,float)
    private static Method mColorChemical;       // MekanismRenderer#color(GuiGraphics, Chemical)
    private static Method mResetColor;          // MekanismRenderer#resetColor(GuiGraphics)
    private static Method mGetChemicalTexture;  // MekanismRenderer#getChemicalTexture(Chemical)
    private static Method mGetTooltipText;      // GuiGauge#getTooltipText()
    private static Method mGetDisplayName;      // GuiGauge#getLabel()
    private static Constructor<?> ctorGasGauge; // GuiGasGauge(Supplier,Supplier,GaugeType,IGuiWrapper,int,int)

    private static Object gaugeTypeStandard;    // GaugeType.STANDARD

    private MekReflect() {
    }

    /** 解析一次；失败就永远降级（记一条日志，不刷屏）。 */
    private static synchronized void init() {
        if (tried) {
            return;
        }
        tried = true;
        try {
            clsGuiElement = Class.forName(P + "client.gui.element.GuiElement");
            clsGuiGasGauge = Class.forName(P + "client.gui.element.gauge.GuiGasGauge");
            clsGaugeType = Class.forName(P + "client.gui.element.gauge.GaugeType");
            clsMekanismRenderer = Class.forName(P + "client.render.MekanismRenderer");
            clsChemical = Class.forName(P + "api.chemical.Chemical");
            clsIGasTank = Class.forName(P + "api.chemical.gas.IGasTank");
            clsMekanismGases = Class.forName(P + "common.registries.MekanismGases");

            Class<?> guiWrapper = Class.forName(P + "client.gui.IGuiWrapper");
            Class<?> gasStack = Class.forName(P + "api.chemical.gas.GasStack");

            // **按参数类型找，不按名字找**。
            //
            // 线上 jar 里「重写了 MC 方法」的那些方法名是 SRG 混淆名：
            //   GuiElement.render  → m_88315_      （它重写 AbstractWidget.render）
            // 而 MEK 自己的方法（color / resetColor / getChemicalTexture / getTooltipText ...）
            // 保持原名。所以只要按名字去 getMethod("render", ...) 必然抛
            // NoSuchMethodException —— 实测就是这一句把整个反射层废掉的
            // （日志：`[ga-gui] ... 反射层不可用: java.lang.NoSuchMethodException`）。
            mRender = findByParams(clsGuiElement, GuiGraphics.class, int.class, int.class, float.class);
            if (mRender == null) {
                throw new NoSuchMethodException("GuiElement 上找不到 (GuiGraphics,int,int,float) 的渲染方法");
            }

            mColorChemical = clsMekanismRenderer.getMethod("color", GuiGraphics.class, clsChemical);
            mResetColor = clsMekanismRenderer.getMethod("resetColor", GuiGraphics.class);
            mGetChemicalTexture = clsMekanismRenderer.getMethod("getChemicalTexture", clsChemical);

            ctorGasGauge = clsGuiGasGauge.getConstructor(
                    Supplier.class, Supplier.class, clsGaugeType, guiWrapper, int.class, int.class);

            Field standard = clsGaugeType.getField("STANDARD");
            gaugeTypeStandard = standard.get(null);

            mGetTooltipText = clsGuiGasGauge.getMethod("getTooltipText");
            ok = true;
            Golems_arsenal.LOGGER.info("[ga-gui] Mekanism GUI 反射层就绪（GuiGasGauge / GaugeType.STANDARD / MekanismRenderer）");
        } catch (Throwable t) {
            ok = false;
            Golems_arsenal.LOGGER.warn("[ga-gui] Mekanism GUI 反射层不可用，界面将降级为自绘: {}", t.toString());
        }
    }

    /** 反射层是否可用（MEK 在、且签名匹配）。 */
    public static boolean available() {
        init();
        return ok;
    }

    /**
     * 按**参数类型**在类及其父类里找一个方法，忽略方法名。
     *
     * <p>为什么需要它：线上 jar 里重写 MC 方法的方法名是 SRG 混淆名
     * （{@code render} → {@code m_88315_}），按名字找会失败。参数类型不受混淆影响。
     */
    @Nullable
    private static Method findByParams(Class<?> owner, Class<?>... params) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!java.util.Arrays.equals(m.getParameterTypes(), params)) {
                    continue;
                }
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        || java.lang.reflect.Modifier.isPrivate(m.getModifiers())) {
                    continue;
                }
                m.setAccessible(true);
                return m;
            }
        }
        for (Method m : owner.getMethods()) {
            if (java.util.Arrays.equals(m.getParameterTypes(), params)) {
                return m;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 气体
    // ------------------------------------------------------------------

    /** 按注册名取 {@code Gas}；取不到返回 null。 */
    @Nullable
    public static Object gas(String registryPath) {
        init();
        if (!ok) {
            return null;
        }
        try {
            // 允许传 "mekanism:ethene" 或 "ethene"；常量名是去掉命名空间后的大写
            String path = registryPath.contains(":")
                    ? registryPath.substring(registryPath.indexOf(':') + 1) : registryPath;
            Field f = clsMekanismGases.getField(path.toUpperCase(java.util.Locale.ROOT));
            Object registryObject = f.get(null);
            // GasRegistryObject#getChemical()（继承自 RegistryObject 的包装）
            Method getChemical = registryObject.getClass().getMethod("getChemical");
            return getChemical.invoke(registryObject);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 气体显示名（"氢气"/"乙烯"…），走 MEK 的 {@code Chemical.getTextComponent()}，
     * 所以语言文件由 MEK 提供，我们不用自己维护一份气体名表。
     *
     * <p>取不到时退回注册名（例如 {@code mekanism:ethylene}），至少不会显示空白。
     */
    public static Component gasName(String gasId) {
        init();
        String path = gasId.contains(":") ? gasId.substring(gasId.indexOf(':') + 1) : gasId;
        Object gas = gas(path);
        if (ok && gas != null) {
            try {
                Method m = clsChemical.getMethod("getTextComponent");
                return (Component) m.invoke(gas);
            } catch (Throwable ignored) {
                // 落到下面的兜底
            }
        }
        return Component.literal(gasId);
    }

    /** 该气体在液体图集里的贴图。 */
    @Nullable
    public static TextureAtlasSprite chemicalTexture(@Nullable Object gas) {
        init();
        if (!ok || gas == null) {
            return null;
        }
        try {
            return (TextureAtlasSprite) mGetChemicalTexture.invoke(null, gas);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按气体自己的颜色给后续绘制染色。**用完必须 {@link #resetColor}。** */
    public static void colorGas(GuiGraphics g, @Nullable Object gas) {
        init();
        if (!ok || gas == null) {
            return;
        }
        try {
            mColorChemical.invoke(null, g, gas);
        } catch (Throwable ignored) {
        }
    }

    public static void resetColor(GuiGraphics g) {
        init();
        if (!ok) {
            return;
        }
        try {
            mResetColor.invoke(null, g);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 储罐（客户端影子）
    // ------------------------------------------------------------------

    /**
     * 造一个**真正的**客户端储罐（不是 {@code createDummy} 的展示壳）。
     *
     * <p><b>为什么不用 createDummy</b>：那是 MEK 给 JEI / 创造模式用的展示对象，
     * 默认就处于"有内容"的状态，而且不一定吃 {@code setStack}——实测结果就是
     * 装乙烯还显示氢气。
     *
     * <p>这里调的是和服务端**完全同一个**工厂：
     * <pre>
     *   ChemicalTankBuilder.GAS.create(capacity, predicate, listener)
     * </pre>
     * 客户端拿到的就是一个正常储罐，{@code setStack} 会按真实规则接受。
     *
     * <p>谓词用"总是接受"——客户端只是显示用的影子，真正的把关在服务端。
     */
    @Nullable
    public static Object clientTank(long capacity) {
        init();
        if (!ok) {
            return null;
        }
        try {
            Class<?> builder = Class.forName(P + "api.chemical.ChemicalTankBuilder");
            Object gasBuilder = builder.getField("GAS").get(null);
            for (Method m : gasBuilder.getClass().getMethods()) {
                if (!m.getName().equals("create") || m.getParameterCount() != 3) {
                    continue;
                }
                Class<?>[] pt = m.getParameterTypes();
                if (pt[0] != long.class || pt[1] != java.util.function.Predicate.class) {
                    continue;
                }
                java.util.function.Predicate<Object> acceptAll = gas -> true;
                return m.invoke(gasBuilder, capacity, acceptAll, null);
            }
            Golems_arsenal.LOGGER.warn(
                    "[ga-gui] 找不到 ChemicalTankBuilder.GAS.create(long, Predicate, IContentsListener)");
            return null;
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("[ga-gui] 创建客户端储罐失败: {}", t.toString());
            return null;
        }
    }

    /**
     * 清空客户端影子储罐。
     *
     * <p>{@code IChemicalTank.setEmpty()} 是 default 方法（默认实现就是
     * {@code setStack(stack.getEmpty())}），所以不用自己造空的 {@code GasStack}
     * ——空 {@code GasStack} 需要从注册表按裸名 {@code "empty"} 取，取不到。
     */
    public static boolean clearTank(@Nullable Object tank) {
        init();
        if (!ok || tank == null) {
            return false;
        }
        try {
            for (Method m : tank.getClass().getMethods()) {
                if (m.getName().equals("setEmpty") && m.getParameterCount() == 0) {
                    m.invoke(tank);
                    return true;
                }
            }
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("[ga-gui] 清空影子储罐失败: {}", t.toString());
        }
        return false;
    }

    /**
     * 造一个客户端"影子储罐"（旧的展示壳版本，现在只作为 {@link #clientTank} 失败时的兜底）。
     *
     * <p>实体没有 MEK 的容器级同步（{@code SyncableChemicalTank} 是挂在
     * {@code MekanismContainer} 上的），所以客户端拿不到服务端那个真储罐，
     * 只能自己造一个再由同步过来的「气体种类 + 储量」填充。
     */
    @Nullable
    public static Object dummyTank(long capacity) {
        init();
        if (!ok) {
            return null;
        }
        try {
            Class<?> builder = Class.forName(P + "api.chemical.ChemicalTankBuilder");
            Field gasField = builder.getField("GAS");
            Object gasBuilder = gasField.get(null);
            Method createDummy = null;
            for (Method m : gasBuilder.getClass().getMethods()) {
                if (m.getName().equals("createDummy") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == long.class) {
                    createDummy = m;
                    break;
                }
            }
            if (createDummy == null) {
                Golems_arsenal.LOGGER.warn("[ga-gui] ChemicalTankBuilder.GAS 上找不到 createDummy(long)");
                return null;
            }
            return createDummy.invoke(gasBuilder, capacity);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 往影子储罐里写内容（客户端每帧刷新用）。
     *
     * <p><b>这里有个坑</b>：{@code IGasTank.setStack} 的**擦除签名是
     * {@code setStack(ChemicalStack)}，不是 {@code setStack(GasStack)}**。
     * 之前按 {@code GasStack.class} 去 {@code getMethod} 会抛 {@code NoSuchMethodException}，
     * 被 catch 吞掉 → 影子储罐从没被写入 → 界面永远显示同一个内容（实测：装乙烯还显示氢气）。
     * 所以改成<b>按名字 + 参数个数</b>查找，再直接 invoke（{@code GasStack} 是
     * {@code ChemicalStack} 的子类，传进去没问题）。
     *
     * @return 是否真的写进去了（失败时会打日志，不再静默）
     */
    public static boolean setTankContents(@Nullable Object tank, @Nullable Object gas, long amount) {
        init();
        if (!ok || tank == null || gas == null) {
            return false;
        }
        try {
            Class<?> gasStackCls = Class.forName(P + "api.chemical.gas.GasStack");
            // **GasStack 的构造器是 (IGasProvider, long)，不是 (Chemical, long)**：
            //   public GasStack(mekanism.api.providers.IGasProvider, long)
            //   public GasStack(GasStack, long)
            // Gas implements IGasProvider，所以直接传 gas 是合法的，只是**不能按
            // Chemical.class 去 getConstructor**（实测就抛 NoSuchMethodException）。
            // 改成按「参数类型能不能接收这个 gas」来找。
            Constructor<?> ctor = null;
            for (Constructor<?> c : gasStackCls.getConstructors()) {
                Class<?>[] pt = c.getParameterTypes();
                if (pt.length == 2 && pt[1] == long.class && pt[0].isInstance(gas)) {
                    ctor = c;
                    break;
                }
            }
            if (ctor == null) {
                Golems_arsenal.LOGGER.warn(
                        "[ga-gui] GasStack 上找不到 (可接收 {}, long) 的构造器", gas.getClass().getName());
                return false;
            }
            Object stack = ctor.newInstance(gas, amount);

            Method setStack = null;
            for (Method m : tank.getClass().getMethods()) {
                if (m.getName().equals("setStack") && m.getParameterCount() == 1) {
                    setStack = m;
                    break;
                }
            }
            if (setStack == null) {
                Golems_arsenal.LOGGER.warn("[ga-gui] 影子储罐上找不到 setStack，气体显示不会更新");
                return false;
            }
            setStack.invoke(tank, stack);
            return true;
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("[ga-gui] 写影子储罐失败: {}", t.toString());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 气体仪表盘
    // ------------------------------------------------------------------

    /**
     * 造一个 MEK 的气体仪表盘组件。
     *
     * @param guiWrapper 我们的 Screen（必须实现 {@code IGuiWrapper}）
     * @param tank       影子储罐
     */
    @Nullable
    public static Object createGasGauge(Object guiWrapper, @Nullable Object tank, int x, int y) {
        init();
        if (!ok || tank == null || gaugeTypeStandard == null) {
            return null;
        }
        try {
            Supplier<Object> one = () -> tank;
            Supplier<List<Object>> all = () -> List.of(tank);
            return ctorGasGauge.newInstance(one, all, gaugeTypeStandard, guiWrapper, x, y);
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("[ga-gui] 创建 GuiGasGauge 失败: {}", t.toString());
            return null;
        }
    }

    /**
     * 读 MEK 组件自己认定的 GUI 原点（{@code GuiElement.getGuiLeft()}）。
     *
     * <p>为什么要读它：MEK 的 {@code GuiElement.render} 内部会
     * {@code pose.translate(getGuiLeft(), getGuiTop())}，也就是组件的 x/y 是
     * **相对 GUI 原点**的。这两个方法名是 MEK 自己的（没被 MC 混淆），可以直接按名字取。
     *
     * <p>调用方拿它来算补偿量：如果它返回的值就是我们的 leftPos，补偿为 0；
     * 如果它返回 0（实测就是这种情况，组件被画到屏幕左上角），补偿量就是 leftPos 本身。
     */
    public static int guiLeftOf(@Nullable Object element) {
        return intOf(element, "getGuiLeft");
    }

    public static int guiTopOf(@Nullable Object element) {
        return intOf(element, "getGuiTop");
    }

    private static int intOf(@Nullable Object element, String name) {
        if (element == null) {
            return 0;
        }
        try {
            Method m = element.getClass().getMethod(name);
            Object v = m.invoke(element);
            return v instanceof Integer i ? i : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 渲染任意 MEK GUI 组件。 */
    public static void render(@Nullable Object element, GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        init();
        if (!ok || element == null) {
            return;
        }
        try {
            mRender.invoke(element, g, mouseX, mouseY, partialTick);
        } catch (Throwable ignored) {
        }
    }

    /** 仪表盘的 tooltip 文本（MEK 自己的格式，含「燃烧时间/能量密度/注册名」）。 */
    @SuppressWarnings("unchecked")
    public static List<Component> tooltipText(@Nullable Object gauge) {
        init();
        if (!ok || gauge == null) {
            return List.of();
        }
        try {
            return (List<Component>) mGetTooltipText.invoke(gauge);
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** 仪表盘标题（"氢气"之类）。 */
    @Nullable
    public static Component label(@Nullable Object gauge) {
        init();
        if (!ok || gauge == null) {
            return null;
        }
        try {
            Class<?> gaugeCls = Class.forName(P + "client.gui.element.gauge.GuiGauge");
            Method m = gaugeCls.getMethod("getLabel");
            return (Component) m.invoke(gauge);
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code GaugeType.STANDARD} 的内容区尺寸（16 x 58）——布局用。 */
    public static int standardGaugeWidth() {
        return 16;
    }

    public static int standardGaugeHeight() {
        return 58;
    }
}
