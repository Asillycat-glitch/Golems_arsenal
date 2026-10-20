# MEK GUI / 渲染 API 反射清单

由 `javap -p` 从 mekanism.jar 直接导出，供 `compat/mekanism/gui` 的反射桥接层使用。

## mekanism_client_gui_element_gauge_GaugeInfo
```java
Compiled from "GaugeInfo.java"
public final class mekanism.client.gui.element.gauge.GaugeInfo extends java.lang.Enum<mekanism.client.gui.element.gauge.GaugeInfo> {
  public static final mekanism.client.gui.element.gauge.GaugeInfo STANDARD;
  public static final mekanism.client.gui.element.gauge.GaugeInfo BLUE;
  public static final mekanism.client.gui.element.gauge.GaugeInfo RED;
  public static final mekanism.client.gui.element.gauge.GaugeInfo YELLOW;
  public static final mekanism.client.gui.element.gauge.GaugeInfo ORANGE;
  public static final mekanism.client.gui.element.gauge.GaugeInfo AQUA;
  private final mekanism.api.text.EnumColor color;
  private final int sideWidth;
  private final int sideHeight;
  private final net.minecraft.resources.ResourceLocation resourceLocation;
  private static final mekanism.client.gui.element.gauge.GaugeInfo[] $VALUES;
  public static mekanism.client.gui.element.gauge.GaugeInfo[] values();
  public static mekanism.client.gui.element.gauge.GaugeInfo valueOf(java.lang.String);
  private mekanism.client.gui.element.gauge.GaugeInfo(java.lang.String, int, int, mekanism.api.text.EnumColor);
  public mekanism.api.text.EnumColor getColor();
  public int getSideWidth();
  public int getSideHeight();
  public net.minecraft.resources.ResourceLocation getResourceLocation();
  public static mekanism.client.gui.element.gauge.GaugeInfo get(mekanism.common.tile.component.config.DataType);
  private static mekanism.client.gui.element.gauge.GaugeInfo[] $values();
  static {};
}
```

## mekanism_client_gui_element_gauge_GaugeOverlay
```java
Compiled from "GaugeOverlay.java"
public final class mekanism.client.gui.element.gauge.GaugeOverlay extends java.lang.Enum<mekanism.client.gui.element.gauge.GaugeOverlay> {
  public static final mekanism.client.gui.element.gauge.GaugeOverlay SMALL;
  public static final mekanism.client.gui.element.gauge.GaugeOverlay SMALL_MED;
  public static final mekanism.client.gui.element.gauge.GaugeOverlay STANDARD;
  public static final mekanism.client.gui.element.gauge.GaugeOverlay MEDIUM;
  public static final mekanism.client.gui.element.gauge.GaugeOverlay WIDE;
  private final int width;
  private final int height;
  private final net.minecraft.resources.ResourceLocation barOverlay;
  private static final mekanism.client.gui.element.gauge.GaugeOverlay[] $VALUES;
  public static mekanism.client.gui.element.gauge.GaugeOverlay[] values();
  public static mekanism.client.gui.element.gauge.GaugeOverlay valueOf(java.lang.String);
  private mekanism.client.gui.element.gauge.GaugeOverlay(int, int, java.lang.String);
  public int getWidth();
  public int getHeight();
  public net.minecraft.resources.ResourceLocation getBarOverlay();
  private static mekanism.client.gui.element.gauge.GaugeOverlay[] $values();
  static {};
}
```

## mekanism_client_gui_element_gauge_GaugeType
```java
Compiled from "GaugeType.java"
public class mekanism.client.gui.element.gauge.GaugeType {
  public static final mekanism.client.gui.element.gauge.GaugeType STANDARD;
  public static final mekanism.client.gui.element.gauge.GaugeType SMALL;
  public static final mekanism.client.gui.element.gauge.GaugeType SMALL_MED;
  public static final mekanism.client.gui.element.gauge.GaugeType MEDIUM;
  public static final mekanism.client.gui.element.gauge.GaugeType WIDE;
  private final mekanism.client.gui.element.gauge.GaugeInfo gaugeInfo;
  private final mekanism.client.gui.element.gauge.GaugeOverlay gaugeOverlay;
  private mekanism.client.gui.element.gauge.GaugeType(mekanism.client.gui.element.gauge.GaugeInfo, mekanism.client.gui.element.gauge.GaugeOverlay);
  public mekanism.client.gui.element.gauge.GaugeInfo getGaugeInfo();
  public mekanism.client.gui.element.gauge.GaugeOverlay getGaugeOverlay();
  public mekanism.client.gui.element.gauge.GaugeType with(mekanism.common.tile.component.config.DataType);
  public mekanism.client.gui.element.gauge.GaugeType with(mekanism.client.gui.element.gauge.GaugeInfo);
  public static mekanism.client.gui.element.gauge.GaugeType get(mekanism.client.gui.element.gauge.GaugeInfo, mekanism.client.gui.element.gauge.GaugeOverlay);
  static {};
}
```

## mekanism_client_gui_element_gauge_GuiChemicalGauge
```java
Compiled from "GuiChemicalGauge.java"
public abstract class mekanism.client.gui.element.gauge.GuiChemicalGauge<CHEMICAL extends mekanism.api.chemical.Chemical<CHEMICAL>, STACK extends mekanism.api.chemical.ChemicalStack<CHEMICAL>, TANK extends mekanism.api.chemical.IChemicalTank<CHEMICAL, STACK>> extends mekanism.client.gui.element.gauge.GuiTankGauge<CHEMICAL, TANK> {
  protected net.minecraft.network.chat.Component label;
  public mekanism.client.gui.element.gauge.GuiChemicalGauge(mekanism.client.gui.element.gauge.GuiTankGauge$ITankInfoHandler<TANK>, mekanism.client.gui.element.gauge.GaugeType, mekanism.client.gui.IGuiWrapper, int, int, int, int, mekanism.common.network.to_server.PacketDropperUse$TankType);
  public mekanism.client.gui.element.gauge.GuiChemicalGauge(java.util.function.Supplier<TANK>, java.util.function.Supplier<java.util.List<TANK>>, mekanism.client.gui.element.gauge.GaugeType, mekanism.client.gui.IGuiWrapper, int, int, mekanism.common.network.to_server.PacketDropperUse$TankType);
  public mekanism.client.gui.element.gauge.GuiChemicalGauge(java.util.function.Supplier<TANK>, java.util.function.Supplier<java.util.List<TANK>>, mekanism.client.gui.element.gauge.GaugeType, mekanism.client.gui.IGuiWrapper, int, int, int, int, mekanism.common.network.to_server.PacketDropperUse$TankType);
  public mekanism.client.gui.element.gauge.GuiChemicalGauge<CHEMICAL, STACK, TANK> setLabel(net.minecraft.network.chat.Component);
  public int getScaledLevel();
  public net.minecraft.client.renderer.texture.TextureAtlasSprite getIcon();
  public net.minecraft.network.chat.Component getLabel();
  public java.util.List<net.minecraft.network.chat.Component> getTooltipText();
  protected void applyRenderColor(net.minecraft.client.gui.GuiGraphics);
  public java.util.Optional<?> getIngredient(double, double);
  public net.minecraft.client.renderer.Rect2i getIngredientBounds(double, double);
}
```

## mekanism_client_gui_element_gauge_GuiGasGauge
```java
Compiled from "GuiGasGauge.java"
public class mekanism.client.gui.element.gauge.GuiGasGauge extends mekanism.client.gui.element.gauge.GuiChemicalGauge<mekanism.api.chemical.gas.Gas, mekanism.api.chemical.gas.GasStack, mekanism.api.chemical.gas.IGasTank> {
  public mekanism.client.gui.element.gauge.GuiGasGauge(mekanism.client.gui.element.gauge.GuiTankGauge$ITankInfoHandler<mekanism.api.chemical.gas.IGasTank>, mekanism.client.gui.element.gauge.GaugeType, mekanism.client.gui.IGuiWrapper, int, int, int, int);
  public mekanism.client.gui.element.gauge.GuiGasGauge(java.util.function.Supplier<mekanism.api.chemical.gas.IGasTank>, java.util.function.Supplier<java.util.List<mekanism.api.chemical.gas.IGasTank>>, mekanism.client.gui.element.gauge.GaugeType, mekanism.client.gui.IGuiWrapper, int, int);
  public mekanism.client.gui.element.gauge.GuiGasGauge(java.util.function.Supplier<mekanism.api.chemical.gas.IGasTank>, java.util.function.Supplier<java.util.List<mekanism.api.chemical.gas.IGasTank>>, mekanism.client.gui.element.gauge.GaugeType, mekanism.client.gui.IGuiWrapper, int, int, int, int);
  public static mekanism.client.gui.element.gauge.GuiGasGauge getDummy(mekanism.client.gui.element.gauge.GaugeType, mekanism.client.gui.IGuiWrapper, int, int);
  public mekanism.common.lib.transmitter.TransmissionType getTransmission();
}
```

## mekanism_client_gui_element_gauge_GuiGauge
```java
Compiled from "GuiGauge.java"
public abstract class mekanism.client.gui.element.gauge.GuiGauge<T> extends mekanism.client.gui.element.GuiTexturedElement implements mekanism.common.inventory.warning.ISupportsWarning<mekanism.client.gui.element.gauge.GuiGauge<T>> {
  private final mekanism.client.gui.element.gauge.GaugeType gaugeType;
  protected boolean dummy;
  protected T dummyType;
  private java.util.function.BooleanSupplier warningSupplier;
  public mekanism.client.gui.element.gauge.GuiGauge(mekanism.client.gui.element.gauge.GaugeType, mekanism.client.gui.IGuiWrapper, int, int);
  public mekanism.client.gui.element.gauge.GuiGauge(mekanism.client.gui.element.gauge.GaugeType, mekanism.client.gui.IGuiWrapper, int, int, int, int);
  public mekanism.client.gui.element.gauge.GuiGauge<T> warning(mekanism.common.inventory.warning.WarningTracker$WarningType, java.util.function.BooleanSupplier);
  public abstract int getScaledLevel();
  public abstract net.minecraft.client.renderer.texture.TextureAtlasSprite getIcon();
  public abstract net.minecraft.network.chat.Component getLabel();
  public abstract java.util.List<net.minecraft.network.chat.Component> getTooltipText();
  public mekanism.client.gui.element.gauge.GaugeOverlay getGaugeOverlay();
  protected mekanism.client.gui.element.gauge.GaugeInfo getGaugeColor();
  protected void applyRenderColor(net.minecraft.client.gui.GuiGraphics);
  public void drawBackground(net.minecraft.client.gui.GuiGraphics, int, int, float);
  public void renderContents(net.minecraft.client.gui.GuiGraphics);
  public void drawBarOverlay(net.minecraft.client.gui.GuiGraphics);
  public void renderToolTip(net.minecraft.client.gui.GuiGraphics, int, int);
  public abstract mekanism.common.lib.transmitter.TransmissionType getTransmission();
  public void setDummyType(T);
  public mekanism.common.inventory.warning.ISupportsWarning warning(mekanism.common.inventory.warning.WarningTracker$WarningType, java.util.function.BooleanSupplier);
}
```

## mekanism_client_gui_element_gauge_GuiTankGauge
```java
Compiled from "GuiTankGauge.java"
public abstract class mekanism.client.gui.element.gauge.GuiTankGauge<T, TANK> extends mekanism.client.gui.element.gauge.GuiGauge<T> implements mekanism.client.jei.interfaces.IJEIIngredientHelper {
  private final mekanism.client.gui.element.gauge.GuiTankGauge$ITankInfoHandler<TANK> infoHandler;
  private final mekanism.common.network.to_server.PacketDropperUse$TankType tankType;
  public mekanism.client.gui.element.gauge.GuiTankGauge(mekanism.client.gui.element.gauge.GaugeType, mekanism.client.gui.IGuiWrapper, int, int, int, int, mekanism.client.gui.element.gauge.GuiTankGauge$ITankInfoHandler<TANK>, mekanism.common.network.to_server.PacketDropperUse$TankType);
  public TANK getTank();
  protected mekanism.client.gui.element.gauge.GaugeInfo getGaugeColor();
  public void onClick(double, double, int);
  public boolean m_7972_(int);
}
```

## mekanism_client_gui_element_GuiElement
```java
Compiled from "GuiElement.java"
public abstract class mekanism.client.gui.element.GuiElement extends net.minecraft.client.gui.components.AbstractWidget implements mekanism.client.render.IFancyFontRenderer {
  private static final int BUTTON_TEX_X;
  private static final int BUTTON_TEX_Y;
  private static final int BUTTON_INDIVIDUAL_TEX_Y;
  public static final net.minecraft.resources.ResourceLocation WARNING_BACKGROUND_TEXTURE;
  public static final net.minecraft.resources.ResourceLocation WARNING_TEXTURE;
  public static final net.minecraft.client.Minecraft minecraft;
  protected mekanism.client.gui.element.GuiElement$ButtonBackground buttonBackground;
  protected final java.util.List<mekanism.client.gui.element.GuiElement> children;
  private final java.util.List<mekanism.client.gui.element.GuiElement> positionOnlyChildren;
  private mekanism.client.gui.IGuiWrapper guiObj;
  protected java.util.function.Supplier<net.minecraft.sounds.SoundEvent> clickSound;
  protected int relativeX;
  protected int relativeY;
  public boolean isOverlay;
  public mekanism.client.gui.element.GuiElement(mekanism.client.gui.IGuiWrapper, int, int, int, int);
  public mekanism.client.gui.element.GuiElement(mekanism.client.gui.IGuiWrapper, int, int, int, int, net.minecraft.network.chat.Component);
  public void m_168797_(net.minecraft.client.gui.narration.NarrationElementOutput);
  public int getRelativeX();
  public int getRelativeY();
  public void transferToNewGui(mekanism.client.gui.IGuiWrapper);
  private void transferToNewGuiInternal(mekanism.client.gui.IGuiWrapper);
  protected <ELEMENT extends mekanism.client.gui.element.GuiElement> ELEMENT addChild(ELEMENT);
  protected <ELEMENT extends mekanism.client.gui.element.GuiElement> ELEMENT addPositionOnlyChild(ELEMENT);
  public final mekanism.client.gui.IGuiWrapper gui();
  public final int getGuiLeft();
  public final int getGuiTop();
  public final int getGuiWidth();
  public final int getGuiHeight();
  public java.util.List<mekanism.client.gui.element.GuiElement> children();
  public void tick();
  public void resize(int, int, int, int);
  public boolean childrenContainsElement(java.util.function.Predicate<mekanism.client.gui.element.GuiElement>);
  public boolean containsElement(java.util.function.Predicate<mekanism.client.gui.element.GuiElement>);
  public void move(int, int);
  public void onWindowClose();
  protected net.minecraft.resources.ResourceLocation getButtonLocation(java.lang.String);
  protected mekanism.client.gui.element.GuiElement$IHoverable getOnHover(mekanism.api.text.ILangEntry);
  protected mekanism.client.gui.element.GuiElement$IHoverable getOnHover(java.util.function.Supplier<net.minecraft.network.chat.Component>);
  public boolean hasPersistentData();
  public void syncFrom(mekanism.client.gui.element.GuiElement);
  public final void onRenderForeground(net.minecraft.client.gui.GuiGraphics, int, int, int, int);
  public void renderForeground(net.minecraft.client.gui.GuiGraphics, int, int);
  public void renderBackgroundOverlay(net.minecraft.client.gui.GuiGraphics, int, int);
  public void renderToolTip(net.minecraft.client.gui.GuiGraphics, int, int);
  public void displayTooltips(net.minecraft.client.gui.GuiGraphics, int, int, net.minecraft.network.chat.Component...);
  public void displayTooltips(net.minecraft.client.gui.GuiGraphics, int, int, java.util.List<net.minecraft.network.chat.Component>);
  public mekanism.client.gui.element.GuiElement mouseClickedNested(double, double, int);
  public final boolean m_6375_(double, double, int);
  public final void m_5716_(double, double);
  public void onClick(double, double, int);
  public boolean m_7933_(int, int, int);
  public boolean m_5534_(char, int);
  public void m_7212_(double, double, double, double);
  public void m_7691_(double, double);
  public boolean m_6050_(double, double, double);
  public net.minecraft.client.gui.Font getFont();
  public int getXSize();
  public void setButtonBackground(mekanism.client.gui.element.GuiElement$ButtonBackground);
  protected boolean m_93680_(double, double);
  protected int getButtonX();
  protected int getButtonY();
  protected int getButtonWidth();
  protected int getButtonHeight();
  protected boolean resetColorBeforeRender();
  public boolean m_5953_(double, double);
  public final boolean isMouseOverCheckWindows(double, double);
  protected final boolean checkWindows(double, double);
  protected final boolean checkWindows(double, double, boolean);
  public void drawBackground(net.minecraft.client.gui.GuiGraphics, int, int, float);
  public final void onDrawBackground(net.minecraft.client.gui.GuiGraphics, int, int, float);
  public final void renderShifted(net.minecraft.client.gui.GuiGraphics, int, int, float);
  public void m_88315_(net.minecraft.client.gui.GuiGraphics, int, int, float);
  public void m_87963_(net.minecraft.client.gui.GuiGraphics, int, int, float);
  public int getFGColor();
  protected int getButtonTextColor(int, int);
  protected void drawButtonText(net.minecraft.client.gui.GuiGraphics, int, int);
  protected int getButtonTextureY(boolean);
  protected void drawButton(net.minecraft.client.gui.GuiGraphics, int, int);
  protected void renderExtendedTexture(net.minecraft.client.gui.GuiGraphics, net.minecraft.resources.ResourceLocation, int, int);
  protected void renderBackgroundTexture(net.minecraft.client.gui.GuiGraphics, net.minecraft.resources.ResourceLocation, int, int);
  public void m_7435_(net.minecraft.client.sounds.SoundManager);
  protected static void playClickSound(java.util.function.Supplier<net.minecraft.sounds.SoundEvent>);
  private static void playClickSound(net.minecraft.client.sounds.SoundManager, java.util.function.Supplier<net.minecraft.sounds.SoundEvent>);
  protected void drawTiledSprite(net.minecraft.client.gui.GuiGraphics, int, int, int, int, int, net.minecraft.client.renderer.texture.TextureAtlasSprite, mekanism.client.gui.GuiUtils$TilingDirection);
  public void drawCenteredTextScaledBound(net.minecraft.client.gui.GuiGraphics, net.minecraft.network.chat.Component, float, float, float, int);
  private boolean lambda$checkWindows$20(mekanism.client.gui.element.GuiElement);
  private static boolean lambda$isMouseOver$19(double, double, mekanism.client.gui.element.GuiElement);
  private static boolean lambda$mouseScrolled$18(double, double, double, mekanism.client.gui.element.GuiElement);
  private static void lambda$onRelease$17(double, double, mekanism.client.gui.element.GuiElement);
  private static void lambda$onDrag$16(double, double, double, double, mekanism.client.gui.element.GuiElement);
  private static boolean lambda$charTyped$15(char, int, mekanism.client.gui.element.GuiElement);
  private static boolean lambda$keyPressed$14(int, int, int, mekanism.client.gui.element.GuiElement);
  private static void lambda$renderToolTip$13(net.minecraft.client.gui.GuiGraphics, int, int, mekanism.client.gui.element.GuiElement);
  private static boolean lambda$renderToolTip$12(int, int, mekanism.client.gui.element.GuiElement);
  private static void lambda$onRenderForeground$11(com.mojang.blaze3d.vertex.PoseStack, net.minecraft.client.gui.GuiGraphics, int, int, int, mekanism.client.gui.element.GuiElement);
  private static void lambda$onRenderForeground$10(net.minecraft.client.gui.GuiGraphics, int, int, mekanism.client.gui.element.GuiElement);
  private static void lambda$onRenderForeground$9(net.minecraft.client.gui.GuiGraphics, int, int, mekanism.client.gui.element.GuiElement);
  private void lambda$getOnHover$8(java.util.function.Supplier, mekanism.client.gui.element.GuiElement, net.minecraft.client.gui.GuiGraphics, int, int);
  private static net.minecraft.network.chat.Component lambda$getOnHover$7(mekanism.api.text.ILangEntry);
  private static void lambda$move$6(int, int, mekanism.client.gui.element.GuiElement);
  private static void lambda$move$5(int, int, mekanism.client.gui.element.GuiElement);
  private static boolean lambda$childrenContainsElement$4(java.util.function.Predicate, mekanism.client.gui.element.GuiElement);
  private static void lambda$resize$3(int, int, int, int, mekanism.client.gui.element.GuiElement);
  private static void lambda$resize$2(int, int, int, int, mekanism.client.gui.element.GuiElement);
  private static void lambda$transferToNewGuiInternal$1(mekanism.client.gui.IGuiWrapper, mekanism.client.gui.element.GuiElement);
  private static void lambda$transferToNewGuiInternal$0(mekanism.client.gui.IGuiWrapper, mekanism.client.gui.element.GuiElement);
  static {};
}
```

## mekanism_client_gui_element_GuiTexturedElement
```java
Compiled from "GuiTexturedElement.java"
public abstract class mekanism.client.gui.element.GuiTexturedElement extends mekanism.client.gui.element.GuiElement {
  protected final net.minecraft.resources.ResourceLocation resource;
  public mekanism.client.gui.element.GuiTexturedElement(net.minecraft.resources.ResourceLocation, mekanism.client.gui.IGuiWrapper, int, int, int, int);
  protected net.minecraft.resources.ResourceLocation getResource();
}
```

## mekanism_client_gui_element_slot_GuiSlot
```java
Compiled from "GuiSlot.java"
public class mekanism.client.gui.element.slot.GuiSlot extends mekanism.client.gui.element.GuiTexturedElement implements mekanism.client.jei.interfaces.IJEIGhostTarget, mekanism.common.inventory.warning.ISupportsWarning<mekanism.client.gui.element.slot.GuiSlot> {
  private static final int INVALID_SLOT_COLOR;
  public static final int DEFAULT_HOVER_COLOR;
  private final mekanism.client.gui.element.slot.SlotType slotType;
  private java.util.function.Supplier<net.minecraft.world.item.ItemStack> validityCheck;
  private java.util.function.Supplier<net.minecraft.world.item.ItemStack> storedStackSupplier;
  private java.util.function.Supplier<mekanism.common.inventory.container.slot.SlotOverlay> overlaySupplier;
  private java.util.function.BooleanSupplier warningSupplier;
  private java.util.function.IntSupplier overlayColorSupplier;
  private mekanism.common.inventory.container.slot.SlotOverlay overlay;
  private mekanism.client.gui.element.GuiElement$IHoverable onHover;
  private mekanism.client.gui.element.GuiElement$IClickable onClick;
  private boolean renderHover;
  private boolean renderAboveSlots;
  private mekanism.client.jei.interfaces.IJEIGhostTarget$IGhostIngredientConsumer ghostHandler;
  public mekanism.client.gui.element.slot.GuiSlot(mekanism.client.gui.element.slot.SlotType, mekanism.client.gui.IGuiWrapper, int, int);
  public mekanism.client.gui.element.slot.GuiSlot validity(java.util.function.Supplier<net.minecraft.world.item.ItemStack>);
  public mekanism.client.gui.element.slot.GuiSlot warning(mekanism.common.inventory.warning.WarningTracker$WarningType, java.util.function.BooleanSupplier);
  public mekanism.client.gui.element.slot.GuiSlot stored(java.util.function.Supplier<net.minecraft.world.item.ItemStack>);
  public mekanism.client.gui.element.slot.GuiSlot hover(mekanism.client.gui.element.GuiElement$IHoverable);
  public mekanism.client.gui.element.slot.GuiSlot click(mekanism.client.gui.element.GuiElement$IClickable);
  public mekanism.client.gui.element.slot.GuiSlot click(mekanism.client.gui.element.GuiElement$IClickable, java.util.function.Supplier<net.minecraft.sounds.SoundEvent>);
  public mekanism.client.gui.element.slot.GuiSlot with(mekanism.common.inventory.container.slot.SlotOverlay);
  public mekanism.client.gui.element.slot.GuiSlot overlayColor(java.util.function.IntSupplier);
  public mekanism.client.gui.element.slot.GuiSlot with(java.util.function.Supplier<mekanism.common.inventory.container.slot.SlotOverlay>);
  public mekanism.client.gui.element.slot.GuiSlot setRenderHover(boolean);
  public mekanism.client.gui.element.slot.GuiSlot setGhostHandler(mekanism.client.jei.interfaces.IJEIGhostTarget$IGhostIngredientConsumer);
  public mekanism.client.gui.element.slot.GuiSlot setRenderAboveSlots();
  public void m_87963_(net.minecraft.client.gui.GuiGraphics, int, int, float);
  public void drawBackground(net.minecraft.client.gui.GuiGraphics, int, int, float);
  private void draw(net.minecraft.client.gui.GuiGraphics);
  protected void drawContents(net.minecraft.client.gui.GuiGraphics);
  public void renderForeground(net.minecraft.client.gui.GuiGraphics, int, int);
  public void renderToolTip(net.minecraft.client.gui.GuiGraphics, int, int);
  public mekanism.client.gui.element.GuiElement mouseClickedNested(double, double, int);
  public mekanism.client.jei.interfaces.IJEIGhostTarget$IGhostIngredientConsumer getGhostHandler();
  public int borderSize();
  public mekanism.common.inventory.warning.ISupportsWarning warning(mekanism.common.inventory.warning.WarningTracker$WarningType, java.util.function.BooleanSupplier);
  static {};
}
```

## mekanism_client_gui_GuiUtils
```java
Compiled from "GuiUtils.java"
public class mekanism.client.gui.GuiUtils {
  private mekanism.client.gui.GuiUtils();
  public static void renderExtendedTexture(net.minecraft.client.gui.GuiGraphics, net.minecraft.resources.ResourceLocation, int, int, int, int, int, int);
  public static void renderBackgroundTexture(net.minecraft.client.gui.GuiGraphics, net.minecraft.resources.ResourceLocation, int, int, int, int, int, int, int, int);
  public static void drawOutline(net.minecraft.client.gui.GuiGraphics, int, int, int, int, int);
  public static void fill(net.minecraft.client.gui.GuiGraphics, net.minecraft.client.renderer.RenderType, int, int, int, int, int);
  public static void fill(net.minecraft.client.gui.GuiGraphics, int, int, int, int, int);
  public static void drawBackdrop(net.minecraft.client.gui.GuiGraphics, net.minecraft.client.Minecraft, int, int, int, int);
  public static void drawBackdrop(net.minecraft.client.gui.GuiGraphics, net.minecraft.client.Minecraft, int, int, int, int, int);
  public static void drawTiledSprite(net.minecraft.client.gui.GuiGraphics, int, int, int, int, int, net.minecraft.client.renderer.texture.TextureAtlasSprite, int, int, int, mekanism.client.gui.GuiUtils$TilingDirection);
  public static void drawTiledSprite(net.minecraft.client.gui.GuiGraphics, int, int, int, int, int, net.minecraft.client.renderer.texture.TextureAtlasSprite, int, int, int, mekanism.client.gui.GuiUtils$TilingDirection, boolean);
  public static boolean checkChildren(java.util.List<? extends net.minecraft.client.gui.components.events.GuiEventListener>, java.util.function.Predicate<mekanism.client.gui.element.GuiElement>);
  public static int drawString(net.minecraft.client.gui.GuiGraphics, net.minecraft.client.gui.Font, net.minecraft.network.chat.Component, float, float, int, boolean);
  public static void renderItem(net.minecraft.client.gui.GuiGraphics, net.minecraft.world.item.ItemStack, int, int, float, net.minecraft.client.gui.Font, java.lang.String, boolean);
  public static void blitNineSlicedSized(net.minecraft.client.gui.GuiGraphics, net.minecraft.resources.ResourceLocation, int, int, int, int, int, int, int, int, int, int, int, int);
  public static void blitNineSlicedSized(net.minecraft.client.gui.GuiGraphics, net.minecraft.resources.ResourceLocation, int, int, int, int, int, int, int, int, int, int, int);
  private static void blit(com.mojang.blaze3d.vertex.BufferBuilder, org.joml.Matrix4f, int, int, float, float, int, int, int, int);
  private static void blitRepeating(com.mojang.blaze3d.vertex.BufferBuilder, org.joml.Matrix4f, int, int, int, int, int, int, int, int, int, int);
  private static it.unimi.dsi.fastutil.ints.IntIterator slices(int, int);
}
```

## mekanism_client_gui_IGuiWrapper
```java
Compiled from "IGuiWrapper.java"
public interface mekanism.client.gui.IGuiWrapper {
  public default void displayTooltips(net.minecraft.client.gui.GuiGraphics, int, int, net.minecraft.network.chat.Component...);
  public default void displayTooltips(net.minecraft.client.gui.GuiGraphics, int, int, java.util.List<net.minecraft.network.chat.Component>);
  public default net.minecraft.world.item.ItemStack getCarriedItem();
  public default int getLeft();
  public default int getTop();
  public default int getWidth();
  public default int getHeight();
  public default void addWindow(mekanism.client.gui.element.window.GuiWindow);
  public default void removeWindow(mekanism.client.gui.element.window.GuiWindow);
  public default boolean currentlyQuickCrafting();
  public default mekanism.client.gui.element.window.GuiWindow getWindowHovering(double, double);
  public default java.util.function.BooleanSupplier trackWarning(mekanism.common.inventory.warning.WarningTracker$WarningType, java.util.function.BooleanSupplier);
  public abstract net.minecraft.client.gui.Font getFont();
  public default void renderItem(net.minecraft.client.gui.GuiGraphics, net.minecraft.world.item.ItemStack, int, int);
  public default void renderItem(net.minecraft.client.gui.GuiGraphics, net.minecraft.world.item.ItemStack, int, int, float);
  public default void renderItemTooltip(net.minecraft.client.gui.GuiGraphics, net.minecraft.world.item.ItemStack, int, int);
  public default void renderItemTooltipWithExtra(net.minecraft.client.gui.GuiGraphics, net.minecraft.world.item.ItemStack, int, int, java.util.List<net.minecraft.network.chat.Component>);
  public default void renderItemWithOverlay(net.minecraft.client.gui.GuiGraphics, net.minecraft.world.item.ItemStack, int, int, float, java.lang.String);
  public default void setSelectedWindow(mekanism.common.inventory.container.SelectedWindowData);
  public default void addFocusListener(mekanism.client.gui.element.GuiElement);
  public default void removeFocusListener(mekanism.client.gui.element.GuiElement);
  public default void focusChange(mekanism.client.gui.element.GuiElement);
  public default void incrementFocus(mekanism.client.gui.element.GuiElement);
}
```

## mekanism_client_render_MekanismRenderer
```java
Compiled from "MekanismRenderer.java"
public class mekanism.client.render.MekanismRenderer {
  public static net.minecraft.client.renderer.texture.TextureAtlasSprite energyIcon;
  public static net.minecraft.client.renderer.texture.TextureAtlasSprite heatIcon;
  public static net.minecraft.client.renderer.texture.TextureAtlasSprite whiteIcon;
  public static net.minecraft.client.renderer.texture.TextureAtlasSprite teleporterPortal;
  public static net.minecraft.client.renderer.texture.TextureAtlasSprite redstonePulse;
  public static final java.util.Map<mekanism.common.lib.transmitter.TransmissionType, net.minecraft.client.renderer.texture.TextureAtlasSprite> overlays;
  public mekanism.client.render.MekanismRenderer();
  public static net.minecraft.client.renderer.texture.TextureAtlasSprite getBaseFluidTexture(net.minecraft.world.level.material.Fluid, mekanism.client.render.MekanismRenderer$FluidTextureType);
  public static net.minecraft.client.renderer.texture.TextureAtlasSprite getFluidTexture(net.minecraftforge.fluids.FluidStack, mekanism.client.render.MekanismRenderer$FluidTextureType);
  public static net.minecraft.client.renderer.texture.TextureAtlasSprite getChemicalTexture(mekanism.api.chemical.Chemical<?>);
  public static net.minecraft.client.renderer.texture.TextureAtlasSprite getSprite(net.minecraft.resources.ResourceLocation);
  public static void renderObject(mekanism.client.render.MekanismRenderer$Model3D, com.mojang.blaze3d.vertex.PoseStack, com.mojang.blaze3d.vertex.VertexConsumer, int, int, int, mekanism.client.render.RenderResizableCuboid$FaceDisplay, net.minecraft.client.Camera, net.minecraft.core.BlockPos);
  public static void renderObject(mekanism.client.render.MekanismRenderer$Model3D, com.mojang.blaze3d.vertex.PoseStack, com.mojang.blaze3d.vertex.VertexConsumer, int, int, int, mekanism.client.render.RenderResizableCuboid$FaceDisplay, net.minecraft.client.Camera);
  public static void renderObject(mekanism.client.render.MekanismRenderer$Model3D, com.mojang.blaze3d.vertex.PoseStack, com.mojang.blaze3d.vertex.VertexConsumer, int, int, int, mekanism.client.render.RenderResizableCuboid$FaceDisplay, net.minecraft.client.Camera, net.minecraft.world.phys.Vec3);
  public static void renderObject(mekanism.client.render.MekanismRenderer$Model3D, com.mojang.blaze3d.vertex.PoseStack, com.mojang.blaze3d.vertex.VertexConsumer, int[], int, int, mekanism.client.render.RenderResizableCuboid$FaceDisplay, net.minecraft.client.Camera);
  public static void renderObject(mekanism.client.render.MekanismRenderer$Model3D, com.mojang.blaze3d.vertex.PoseStack, com.mojang.blaze3d.vertex.VertexConsumer, int[], int, int, mekanism.client.render.RenderResizableCuboid$FaceDisplay, net.minecraft.client.Camera, net.minecraft.world.phys.Vec3);
  public static void renderValves(com.mojang.blaze3d.vertex.PoseStack, com.mojang.blaze3d.vertex.VertexConsumer, java.util.Set<mekanism.common.lib.multiblock.IValveHandler$ValveData>, mekanism.client.render.data.FluidRenderData, float, net.minecraft.core.BlockPos, int, int, mekanism.client.render.RenderResizableCuboid$FaceDisplay, net.minecraft.client.Camera);
  public static void resetColor(net.minecraft.client.gui.GuiGraphics);
  public static float getRed(int);
  public static float getGreen(int);
  public static float getBlue(int);
  public static float getAlpha(int);
  public static void color(net.minecraft.client.gui.GuiGraphics, int);
  public static void color(net.minecraft.client.gui.GuiGraphics, mekanism.client.render.lib.ColorAtlas$ColorRegistryObject);
  public static void color(net.minecraft.client.gui.GuiGraphics, mekanism.common.lib.Color);
  public static void color(net.minecraft.client.gui.GuiGraphics, net.minecraftforge.fluids.FluidStack);
  public static void color(net.minecraft.client.gui.GuiGraphics, mekanism.api.chemical.ChemicalStack<?>);
  public static void color(net.minecraft.client.gui.GuiGraphics, mekanism.api.chemical.Chemical<?>);
  public static void color(net.minecraft.client.gui.GuiGraphics, mekanism.api.SupportsColorMap);
  public static void color(net.minecraft.client.gui.GuiGraphics, mekanism.api.SupportsColorMap, float);
  public static int getColorARGB(mekanism.api.SupportsColorMap, float);
  public static int getColorARGB(net.minecraftforge.fluids.FluidStack);
  public static int getColorARGB(net.minecraftforge.fluids.FluidStack, float);
  public static int getColorARGB(mekanism.api.chemical.ChemicalStack<?>, float, boolean);
  public static int getColorARGB(mekanism.api.chemical.Chemical<?>, float, boolean);
  public static int getColorARGB(float, float, float, float);
  public static int getColorARGB(int, int, int, float);
  public static int calculateGlowLight(int, net.minecraftforge.fluids.FluidStack);
  public static int calculateGlowLight(int, int);
  public static void renderColorOverlay(net.minecraft.client.gui.GuiGraphics, int, int, int);
  public static float getPartialTick();
  public static void rotate(com.mojang.blaze3d.vertex.PoseStack, net.minecraft.core.Direction, float, float, float, float);
  private static <T extends java.lang.Enum<T> & mekanism.api.SupportsColorMap> void parseColorAtlas(net.minecraft.resources.ResourceLocation, T[]);
  public static void onStitch(net.minecraftforge.client.event.TextureStitchEvent$Post);
  static {};
}
```

