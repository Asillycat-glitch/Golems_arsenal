package a_silly_cat.golems_arsenal.client;

import a_silly_cat.golems_arsenal.compat.mekanism.gui.MekReflect;
import mekanism.api.chemical.gas.Gas;
import mekanism.client.render.MekanismRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 燃料条填充 —— **用 MEK 的气体贴图 + tint，但外框和刻度仍用我们贴图里画的**。
 *
 * <p>这是「方案 B」：不套用 MEK 的 {@code GuiGasGauge}（那会把 `normal.png` 灰框和
 * `standard.png` 刻度画上去，盖掉美术），只借用它的两样东西：
 * <pre>
 *   // GuiChemicalGauge
 *   public TextureAtlasSprite getIcon() {
 *       return MekanismRenderer.getChemicalTexture(tank.getType());
 *   }
 *   protected void applyRenderColor(GuiGraphics g) {
 *       MekanismRenderer.color(g, tank.getStack());
 *   }
 * </pre>
 * 也就是"气体自己的贴图 + 气体自己的颜色"，所以氢气是带纹理的灰白、乙烯是淡紫，
 * 和我们硬编码一个颜色完全不同。
 *
 * <p><b>门控</b>：这个类直接 import Mekanism 的类，只由 {@code RocketScreen} 在
 * {@code ModList.isLoaded("mekanism")} 之后调用，类加载因此被推迟。
 */
@OnlyIn(Dist.CLIENT)
public final class RocketGaugeRenderer {

    private RocketGaugeRenderer() {
    }

    /** 按注册名解析气体（{@code mekanism:ethene} 这种）；解析不到返回 null。 */
    private static Gas resolve(String gasId) {
        if (gasId == null || gasId.isEmpty()) {
            return null;
        }
        return MekReflect.gas(gasId) instanceof Gas gas ? gas : null;
    }

    /**
     * 在指定区域里按比例从下往上画燃料填充。
     *
     * <p>按 16 像素一格**平铺**（拉伸会把纹理压扁，MEK 也是平铺）。
     *
     * @param gasId  气体注册名；为空或解析不到时退回中性色
     * @param stored 当前储量
     * @param max    容量
     */
    public static void drawFill(GuiGraphics g, int x, int y, int w, int h,
                                int stored, int max, String gasId) {
        if (stored <= 0 || max <= 0 || w <= 0 || h <= 0) {
            return;
        }
        float ratio = Math.min(1.0F, (float) stored / max);
        int fillH = Math.max(1, Math.round((h - 2) * ratio));
        int top = y + h - fillH;

        Gas gas = resolve(gasId);
        TextureAtlasSprite icon = gas == null ? null : MekanismRenderer.getChemicalTexture(gas);
        if (gas == null || icon == null) {
            // 不知道是哪种气体（或 MEK 没给贴图）：用中性灰，至少比例是对的
            g.fill(x, top, x + w, y + h, 0xFFB0B0B0);
            return;
        }

        // 乘上气体自己的 tint；画完必须复位，否则后面所有元素都被染色
        MekanismRenderer.color(g, gas);
        int remaining = fillH;
        int bottom = y + h;
        while (remaining > 0) {
            int chunk = Math.min(16, remaining);
            g.blit(x, bottom - remaining, 0, w, chunk, icon);
            remaining -= chunk;
        }
        MekanismRenderer.resetColor(g);
    }

    /**
     * 燃料条的气体提示（鼠标悬停时显示）。
     *
     * <p><b>照搬 Mekanism 的 {@code GuiChemicalGauge.getTooltipText()}</b>：
     * <pre>
     *   list.add(MekanismLang.GENERIC_STORED_MB.translate(gas, TextUtils.format(amount)));
     *   ChemicalUtil.addChemicalDataToTooltip(list, gas, 高级提示开启);
     * </pre>
     * 第二行负责加上「燃烧时间 / 能量密度 / 注册名」。
     */
    public static void renderTooltip(GuiGraphics g, net.minecraft.client.gui.Font font,
                                     int mouseX, int mouseY, int stored, int max, String gasId) {
        java.util.List<net.minecraft.network.chat.Component> lines = new java.util.ArrayList<>();
        Gas gas = resolve(gasId);
        if (stored <= 0 || gas == null) {
            lines.add(mekanism.common.MekanismLang.EMPTY.translate());
        } else {
            lines.add(mekanism.common.MekanismLang.GENERIC_STORED_MB.translate(
                    gas, mekanism.common.util.text.TextUtils.format(stored)));
            mekanism.common.util.ChemicalUtil.addChemicalDataToTooltip(lines, gas,
                    net.minecraft.client.Minecraft.getInstance().options.advancedItemTooltips);
        }
        g.renderTooltip(font, lines, java.util.Optional.empty(), mouseX, mouseY);
    }
}
