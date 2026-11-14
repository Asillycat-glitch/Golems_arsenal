package a_silly_cat.golems_arsenal.client;

import a_silly_cat.golems_arsenal.tech.orbit.LaserConsoleMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 激光发射台的配置界面。
 *
 * <p>没有物品槽，整块面板都是自绘的：深色工业底 + 六行开关/档位。
 * 每行右侧是一个按钮，点击只把**按钮 id** 发给服务端（
 * {@code ServerboundContainerButtonClickPacket}），判定与写值全在
 * {@link LaserConsoleMenu#clickMenuButton} 里完成。
 *
 * <p>数据来自 {@link LaserConsoleMenu} 的 {@code ContainerData}：服务端每 tick 广播，
 * 客户端只读。因此界面打开时档位变化会立即反映，关掉再开也不会残留旧值。
 */
@OnlyIn(Dist.CLIENT)
public class LaserConsoleScreen extends AbstractContainerScreen<LaserConsoleMenu> {

    // ---- 面板几何 ----
    private static final int PANEL_W = 176;
    private static final int PANEL_H = 166;
    private static final int ROW_X = 8;
    private static final int ROW_W = 160;
    private static final int ROW_H = 15;
    private static final int BTN_X = 102;
    private static final int BTN_W = 66;
    private static final int[] ROW_Y = {34, 52, 70, 88, 106, 124};
    private static final int ROW_COUNT = 6;

    // ---- 颜色 ----
    private static final int COLOR_BG = 0xFF10161A;
    private static final int COLOR_HEADER = 0xFF1D2A33;
    private static final int COLOR_BORDER = 0xFF4C6673;
    private static final int COLOR_ROW = 0xFF182228;
    private static final int COLOR_ROW_LINE = 0xFF24333B;
    private static final int COLOR_TEXT = 0xFFD8E4EA;
    private static final int COLOR_MUTED = 0xFF8FA3AD;
    private static final int COLOR_ON = 0xFF54D98C;
    private static final int COLOR_OFF = 0xFFE06C75;
    private static final int COLOR_WARN = 0xFFE0B050;
    private static final int COLOR_ACCENT = 0xFF66D9FF;

    public LaserConsoleScreen(LaserConsoleMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = PANEL_W;
        this.imageHeight = PANEL_H;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelY = 10_000;   // 没有玩家背包，别画 "Inventory"
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;

        // 底 + 外框
        g.fill(x, y, x + PANEL_W, y + PANEL_H, COLOR_BG);
        border(g, x, y, PANEL_W, PANEL_H, COLOR_BORDER);

        // 标题栏
        g.fill(x + 1, y + 1, x + PANEL_W - 1, y + 24, COLOR_HEADER);
        g.fill(x + 1, y + 24, x + PANEL_W - 1, y + 25, COLOR_ACCENT);

        // 六行
        for (int i = 0; i < ROW_COUNT; i++) {
            int ry = y + ROW_Y[i];
            g.fill(x + ROW_X, ry, x + ROW_X + ROW_W, ry + ROW_H, COLOR_ROW);
            g.fill(x + ROW_X, ry + ROW_H - 1, x + ROW_X + ROW_W, ry + ROW_H, COLOR_ROW_LINE);

            boolean hover = isHovering(BTN_X, ROW_Y[i], BTN_W, ROW_H, mouseX, mouseY);
            g.fill(x + BTN_X, ry, x + BTN_X + BTN_W, ry + ROW_H, buttonColor(i, hover));
            border(g, x + BTN_X, ry, BTN_W, ROW_H, hover ? COLOR_ACCENT : COLOR_BORDER);
        }

        // 页脚分隔线
        g.fill(x + ROW_X, y + 143, x + ROW_X + ROW_W, y + 144, COLOR_ROW_LINE);
    }

    private int buttonColor(int row, boolean hover) {
        int base = switch (row) {
            case 0 -> data(LaserConsoleMenu.DATA_ENABLED) != 0 ? 0xFF1E5A3C : 0xFF5A2027;
            case 1 -> data(LaserConsoleMenu.DATA_FRIENDLY) != 0 ? 0xFF1E4D5A : 0xFF5A3C20;
            case 2 -> data(LaserConsoleMenu.DATA_SAFE_RADIUS) > 0 ? 0xFF1E3F5A : 0xFF303A40;
            case 3 -> 0xFF243A50;
            case 4 -> 0xFF2C3340;
            default -> 0xFF2A3A44;
        };
        return hover ? lighten(base) : base;
    }

    private static int lighten(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.min(255, ((argb >> 16) & 0xFF) + 24);
        int g = Math.min(255, ((argb >> 8) & 0xFF) + 24);
        int b = Math.min(255, (argb & 0xFF) + 24);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static void border(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        int textY = 5;
        g.drawCenteredString(this.font,
                Component.translatable("block.golems_arsenal.laser_console"),
                PANEL_W / 2, textY, COLOR_TEXT);

        // 平台状态
        int platformIndex = data(LaserConsoleMenu.DATA_PLATFORM_INDEX);
        int platformCount = data(LaserConsoleMenu.DATA_PLATFORM_COUNT);
        boolean linked = data(LaserConsoleMenu.DATA_LINKED) != 0;
        Component platform = !linked
                ? Component.translatable("gui.golems_arsenal.laser_console.platform.none")
                .withStyle(ChatFormatting.RED)
                : Component.translatable("gui.golems_arsenal.laser_console.platform.linked",
                platformIndex, platformCount).withStyle(ChatFormatting.AQUA);
        g.drawString(this.font, platform, ROW_X + 2, 15, COLOR_MUTED, false);

        // 六行标签 + 按钮值
        rowLabel(g, 0, "gui.golems_arsenal.laser_console.enabled");
        rowLabel(g, 1, "gui.golems_arsenal.laser_console.friendly");
        rowLabel(g, 2, "gui.golems_arsenal.laser_console.safe_zone");
        rowLabel(g, 3, "gui.golems_arsenal.laser_console.energy");
        rowLabel(g, 4, "gui.golems_arsenal.laser_console.cooldown");
        rowLabel(g, 5, "gui.golems_arsenal.laser_console.platform");

        buttonText(g, 0, Component.translatable(data(LaserConsoleMenu.DATA_ENABLED) != 0
                ? "gui.golems_arsenal.laser_console.on"
                : "gui.golems_arsenal.laser_console.off"),
                data(LaserConsoleMenu.DATA_ENABLED) != 0 ? COLOR_ON : COLOR_OFF);
        buttonText(g, 1, Component.translatable(data(LaserConsoleMenu.DATA_FRIENDLY) != 0
                ? "gui.golems_arsenal.laser_console.on"
                : "gui.golems_arsenal.laser_console.off"),
                data(LaserConsoleMenu.DATA_FRIENDLY) != 0 ? COLOR_ON : COLOR_OFF);

        int safe = data(LaserConsoleMenu.DATA_SAFE_RADIUS);
        buttonText(g, 2, safe <= 0
                        ? Component.translatable("gui.golems_arsenal.laser_console.safe_off")
                        : Component.translatable("gui.golems_arsenal.laser_console.safe_value", safe),
                safe > 0 ? COLOR_TEXT : COLOR_MUTED);

        int energy = data(LaserConsoleMenu.DATA_ENERGY_TIER);
        buttonText(g, 3, Component.translatable("gui.golems_arsenal.laser_console.energy."
                + energyKey(energy)), energy == 2 ? COLOR_WARN : COLOR_TEXT);

        int cooldown = data(LaserConsoleMenu.DATA_COOLDOWN_TIER);
        buttonText(g, 4, Component.translatable("gui.golems_arsenal.laser_console.cooldown."
                + cooldownKey(cooldown)), cooldown == 0 ? COLOR_ACCENT : COLOR_TEXT);

        buttonText(g, 5, linked
                        ? Component.translatable("gui.golems_arsenal.laser_console.switch_platform")
                        : Component.translatable("gui.golems_arsenal.laser_console.bind"),
                linked ? COLOR_TEXT : COLOR_WARN);

        // 页脚：当前档位的实际数值（按满级引导计算）
        int cost = data(LaserConsoleMenu.DATA_COST);
        float damage = data(LaserConsoleMenu.DATA_DAMAGE_X100) / 100.0F;
        int cooldownTicks = data(LaserConsoleMenu.DATA_COOLDOWN_TICKS);
        g.drawString(this.font,
                Component.translatable("gui.golems_arsenal.laser_console.summary_shot",
                        cost, String.format("%.1f", damage)),
                ROW_X + 2, 146, COLOR_MUTED, false);
        g.drawString(this.font,
                Component.translatable("gui.golems_arsenal.laser_console.summary_cooldown",
                        cooldownTicks / 20.0F),
                ROW_X + 2, 156, COLOR_MUTED, false);
    }

    private void rowLabel(GuiGraphics g, int row, String key) {
        g.drawString(this.font, Component.translatable(key),
                ROW_X + 2, ROW_Y[row] + 4, COLOR_TEXT, false);
    }

    private void buttonText(GuiGraphics g, int row, Component text, int color) {
        g.drawCenteredString(this.font, text,
                BTN_X + BTN_W / 2, ROW_Y[row] + 4, color);
    }

    private int data(int index) {
        return this.menu.getData(index);
    }

    private static String energyKey(int tier) {
        return switch (tier) {
            case 0 -> "eco";
            case 2 -> "overload";
            default -> "standard";
        };
    }

    private static String cooldownKey(int tier) {
        return switch (tier) {
            case 0 -> "fast";
            case 2 -> "charge";
            default -> "standard";
        };
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        for (int row = 0; row < ROW_COUNT; row++) {
            if (isHovering(BTN_X, ROW_Y[row], BTN_W, ROW_H, mouseX, mouseY)) {
                g.renderTooltip(this.font,
                        Component.translatable("gui.golems_arsenal.laser_console.tip." + row),
                        mouseX, mouseY);
                break;
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (int row = 0; row < ROW_COUNT; row++) {
                if (isHovering(BTN_X, ROW_Y[row], BTN_W, ROW_H, mouseX, mouseY)) {
                    this.click(row);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 把按钮 id 发给服务端；服务端在 {@link LaserConsoleMenu#clickMenuButton} 里执行。 */
    private void click(int buttonId) {
        if (this.minecraft == null || this.minecraft.gameMode == null) {
            return;
        }
        this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, buttonId);
        this.minecraft.getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
}
