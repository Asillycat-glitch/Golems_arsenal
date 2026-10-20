package a_silly_cat.golems_arsenal.client;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.base.entity.RocketEntity;
import a_silly_cat.golems_arsenal.tech.orbit.RocketMenu;
import dev.xkmc.l2library.base.menu.base.BaseContainerScreen;
import dev.xkmc.l2library.base.menu.base.MenuLayoutConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 火箭界面 —— 用 l2library 的 {@code BaseContainerScreen}。
 *
 * <p>背景贴图由框架按固定路径去找：
 * {@code assets/golems_arsenal/textures/gui/container/rocket.png}（名字来自
 * {@code SpriteManager} 的 path，见 {@code RocketMenu.SPRITE}）。
 *
 * <p><b>所有元素都是画的贴图</b>，布局由
 * {@code data/golems_arsenal/l2library_config/menu_layout/rocket.json} 指定：
 * <pre>
 *   comp.fuel     (26, 67)  燃料槽（位置由框架用来放真实槽位）
 *   comp.launch   (80, 38)  发射按钮的位置
 *   side.btn_launch  (176, 90) 79×24  发射按钮的图案（在贴图右半区）
 *   side.gauge_fill  (176, 24) 16×58  燃料条填充图案
 * </pre>
 * 「发射按钮」不是原版 {@code Button} 组件，而是<b>贴图 + 自己处理点击</b>——
 * 这样按钮的样子完全由贴图决定（用户要求）。
 *
 * <p><b>框架自带的两个坑</b>：
 * <ul>
 *   <li>布局 JSON 缺失会直接 NPE；</li>
 *   <li>组件名写错会<b>静默</b>把槽位放到 (0,0)。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public class RocketScreen extends BaseContainerScreen<RocketMenu> {


    /** 燃料条在图里的位置与尺寸——**按贴图实测**：(48,25) 18×60。 */
    private static final int GAUGE_X = 48;
    private static final int GAUGE_Y = 25;
    private static final int GAUGE_W = 18;
    private static final int GAUGE_H = 60;

    /** 布局 JSON 里的组件名（改 JSON 就改这里）。 */
    private static final String COMP_LAUNCH = "launch";
    /** 燃料条的刻度覆盖图（透明底、只有刻度线；贴图右半区）。 */
    private static final String SIDE_TICKS = "gauge_ticks";
    /** 鼠标是否正按在发射按钮上（用于"按住"那条色带）。 */
    private boolean buttonHeld;
    /** 最近一次渲染时的鼠标位置（tooltip 要用）。 */
    private int lastMouseX;
    private int lastMouseY;

    public RocketScreen(RocketMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    /** 取布局里的组件矩形。 */
    private MenuLayoutConfig.Rect comp(String name) {
        return this.menu.sprite.get().getComp(name);
    }

    /** 鼠标是否悬停在发射按钮上。 */
    private boolean isOverLaunch(double mx, double my) {
        MenuLayoutConfig.Rect r = comp(COMP_LAUNCH);
        double lx = mx - this.leftPos;
        double ly = my - this.topPos;
        return lx >= r.x && lx < r.x + r.w && ly >= r.y && ly < r.y + r.h;
    }

    /**
     * 我们自己的绘制是否出过错（只记一次，避免刷屏卡死）。
     *
     * <p>背景：2026-10-17 测乙烯罐时客户端**卡白屏**，日志显示服务端一直正常心跳
     * （GA-Probe 每 30 秒一条），玩家是被「连接超时」踢掉的——说明卡在客户端渲染。
     * 这里把自定义绘制全部包起来，**任何异常都不会再拖死渲染线程**，同时留下栈。
     */
    private static boolean renderFailureLogged;

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        // 1) 整张底图（含面板、燃料槽框、燃料条框、玩家背包格）
        this.menu.sprite.get().getRenderer(this).start(g);

        // 2) 燃料条填充 + 3) 发射按钮
        //
        // 可以用 -Dgolems_arsenal.disableRocketGui=true 把这两步整个关掉，
        // 用来二分定位「卡白屏到底是不是我们的绘制引起的」。
        if (Boolean.getBoolean("golems_arsenal.disableRocketGui")) {
            this.lastMouseX = mouseX;
            this.lastMouseY = mouseY;
            return;
        }
        try {
            // 燃料条：**方案 B** —— 外框和刻度用贴图里画的，只有填充借 MEK 的
            // 气体贴图 + tint（不用 GuiGasGauge，那会盖掉美术）。
            this.drawFuelGauge(g);
            this.drawLaunchButton(g, mouseX, mouseY);
        } catch (RuntimeException e) {
            if (!renderFailureLogged) {
                renderFailureLogged = true;
                a_silly_cat.golems_arsenal.Golems_arsenal.LOGGER.error(
                        "Rocket GUI rendering failed (fuel={}, state={}); "
                                + "the panel will keep drawing without the custom parts",
                        this.menu.rocket() == null ? -1 : this.menu.rocket().fuelDisplay(),
                        this.menu.rocket() == null ? -1 : this.menu.rocket().state(), e);
            }
        }

        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
    }

    /**
     * 燃料条：按储量比例从下往上填。
     *
     * <p><b>颜色/纹理交给 MEK</b>：{@link RocketGaugeRenderer} 照 MEK 的
     * {@code GuiChemicalGauge} 做法，把<b>氢气自己的贴图</b>裁切着画并乘上气体的 tint。
     * 之前这里写死 {@code 0xFF3FC7E8} 纯青色，所以和 MEK 本家的氢气长得不一样。
     *
     * <p>内区 = 外框内缩 1 像素（贴图实测外框 {@code (48,25) 18×60}）。
     */
    private void drawFuelGauge(GuiGraphics g) {
        RocketEntity rocket = this.menu.rocket();
        int capacity = rocket == null
                ? a_silly_cat.golems_arsenal.Config.ROCKET_FUEL_CAPACITY.get()
                : rocket.fuelCapacity();
        int stored = rocket == null ? 0 : rocket.fuelDisplay();
        int innerX = this.leftPos + GAUGE_X + 1;
        int innerY = this.topPos + GAUGE_Y + 1;
        int innerW = GAUGE_W - 2;
        int innerH = GAUGE_H - 2;

        if (stored > 0 && capacity > 0) {
            if (net.minecraftforge.fml.ModList.get().isLoaded("mekanism")) {
                // 引用 MEK 的类，类加载就发生在这里（门控之后）。
                // **方案 B**：只借 MEK 的「气体贴图 + tint」，外框和刻度仍用我们贴图里画的。
                String gasId = rocket == null ? "" : rocket.fuelGasId();
                RocketGaugeRenderer.drawFill(g, innerX, innerY, innerW, innerH, stored, capacity, gasId);
            } else {
                int h = Math.max(1, Math.round(innerH * Math.min(1.0F, (float) stored / capacity)));
                g.fill(innerX, innerY + innerH - h, innerX + innerW, innerY + innerH, 0xFFB0B0B0);
            }
        }
        // **刻度最后画**：不然填充会把刻度盖掉。
        // 贴图里那块刻度是"透明底 + 只有刻度线"（MEK 也是这么分层的）。
        this.drawGaugeTicks(g, innerX, innerY, innerW, innerH);
    }

    /** 鼠标是否在燃料条区域上。 */
    private boolean isOverGauge(double mx, double my) {
        double lx = mx - this.leftPos;
        double ly = my - this.topPos;
        return lx >= GAUGE_X && lx < GAUGE_X + GAUGE_W
                && ly >= GAUGE_Y && ly < GAUGE_Y + GAUGE_H;
    }

    /** 悬停在燃料条上时显示气体信息（用 MEK 自己的文本格式）。 */
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        RocketEntity rocket = this.menu.rocket();
        if (rocket != null && this.isOverGauge(mouseX, mouseY)
                && net.minecraftforge.fml.ModList.get().isLoaded("mekanism")) {
            RocketGaugeRenderer.renderTooltip(g, this.font, mouseX, mouseY,
                    rocket.fuelDisplay(), rocket.fuelCapacity(), rocket.fuelGasId());
        }
    }

    /**
     * 把刻度覆盖图叠在燃料条上。
     *
     * <p>贴图实测：刻度图 {@code (176, 17) 16×49}，燃料条内区 {@code 16×58}。
     * <b>49 ≠ 58</b>——直接把刻度贴在内区顶上，下面会空 9 像素、间距也对不上。
     * 所以这里用**缩放着画**，把 16×49 拉成 16×58，刻度就正好铺满整条。
     *
     * <p>顺带确认过：面板里那条燃料条内部**没有任何刻度线**（逐行扫过，只有左右 2 个边框像素），
     * 所以刻度只有这一个来源，不会重复绘制。
     */
    private void drawGaugeTicks(GuiGraphics g, int x, int y, int w, int h) {
        MenuLayoutConfig.Rect t = this.menu.sprite.get().getSide(SIDE_TICKS);
        if (t.w <= 0 || t.h <= 0 || w <= 0 || h <= 0) {
            return;
        }
        // 缩放 blit：把源区域 (t.x,t.y,t.w,t.h) 拉进目标区域 (x,y,w,h)
        g.blit(this.menu.sprite.get().getTexture(),
                x, y, w, h,
                t.x, t.y, t.w, t.h, 256, 256);
    }

    /**
     * 发射按钮：**三个状态用贴图里的三条 79×8 色带**。
     *
     * <p>贴图右半区那块 24 像素高其实是三个状态叠在一起（用户指出后才去量的）：
     * <pre>
     *   y 90..97   79×8   暗色 (40~49)      未充满 / 不可用
     *   y 98..105  79×8   中灰 (101~150)    就绪且燃油充满
     *   y 106..113 79×8   亮绿 (52,207,140) 按下不放
     * </pre>
     *
     * <p>之前把整块 24 高当一张图用，所以只显示了最上面那条暗色——那是个 bug，
     * 不是设计。
     */
    private void drawLaunchButton(GuiGraphics g, int mouseX, int mouseY) {
        MenuLayoutConfig.Rect r = comp(COMP_LAUNCH);
        if (r.w <= 0) {
            return;
        }
        this.menu.sprite.get().getRenderer(this).draw(g, COMP_LAUNCH, this.buttonSide());
        // 悬停时只叠一层很淡的高光（三个状态已经由贴图区分，不再需要额外提示）
        if (this.isOverLaunch(mouseX, mouseY) && !this.buttonHeld) {
            g.fill(this.leftPos + r.x, this.topPos + r.y,
                    this.leftPos + r.x + r.w, this.topPos + r.y + r.h,
                    0x18FFFFFF);
        }
    }

    /** 当前该用哪条色带：按住 → 绿；就绪 → 中灰；否则 → 暗。 */
    private String buttonSide() {
        if (this.buttonHeld) {
            return "btn_pressed";
        }
        RocketEntity rocket = this.menu.rocket();
        boolean counting = rocket != null && rocket.state() == RocketEntity.STATE_COUNTDOWN;
        // 倒计时中可以"中止"，也算可用（显示就绪色）
        return (counting || (rocket != null && rocket.canLaunch()))
                ? "btn_ready"
                : "btn_disabled";
    }

    /**
     * 点击：先判发射按钮，其余交回容器界面（槽位拖放等）。
     *
     * <p>只发按钮 id，**判定在服务端**（{@code RocketMenu.clickMenuButton}）——
     * 客户端改不了"能不能发射"的规则。
     */
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && this.isOverLaunch(mx, my)) {
            this.buttonHeld = true;
            if (this.click(RocketMenu.BUTTON_LAUNCH)) {
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    /** 松开鼠标就退出"按住"状态。 */
    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        this.buttonHeld = false;
        return super.mouseReleased(mx, my, button);
    }

    /** 鼠标移出按钮时不保留按住状态（防止松手事件收不到）。 */
    @Override
    public void mouseMoved(double mx, double my) {
        if (this.buttonHeld && !this.isOverLaunch(mx, my)) {
            this.buttonHeld = false;
        }
        super.mouseMoved(mx, my);
    }

    /** 数值文字与倒计时。 */
    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY);
        RocketEntity rocket = this.menu.rocket();
        if (rocket == null) {
            return;
        }
        // 按钮上的文字：待发 → 发射；倒计时中 → 中止
        MenuLayoutConfig.Rect r = comp(COMP_LAUNCH);
        boolean counting = rocket.state() == RocketEntity.STATE_COUNTDOWN;
        Component label = Component.translatable(counting
                ? "gui.golems_arsenal.rocket.abort"
                : "gui.golems_arsenal.rocket.launch");
        g.drawCenteredString(this.font, label,
                r.x + r.w / 2, r.y + (r.h - 8) / 2, 0xFFFFFF);

        // 燃料数值：贴在燃料条右侧
        g.drawString(this.font,
                Component.translatable("gui.golems_arsenal.rocket.fuel",
                        rocket.fuelDisplay(), rocket.fuelCapacity()),
                68, 64, 0x404040, false);

        if (counting) {
            int seconds = (rocket.countdown() + 19) / 20;
            g.drawString(this.font,
                    Component.translatable("gui.golems_arsenal.rocket.countdown", seconds),
                    r.x, r.y + r.h + 4, 0xC02020, false);
        }
    }
}
