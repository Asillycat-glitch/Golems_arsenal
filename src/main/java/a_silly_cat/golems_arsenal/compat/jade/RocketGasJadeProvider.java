package a_silly_cat.golems_arsenal.compat.jade;

import a_silly_cat.golems_arsenal.base.entity.RocketEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IEntityComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * 玉（Jade）里显示火箭**实体内部那个储罐**的气体。
 *
 * <p><b>为什么只需要客户端 provider</b>：参考傀儡装配的做法，它的
 * {@code JadeGolemPlugin.register(IWailaCommonRegistration)} 是<b>空的</b>，
 * 只注册了两个 {@code IEntityComponentProvider}——因为它显示的东西客户端本来就有。
 * 我们的气体种类（{@code DATA_FUEL_GAS}）和储量（{@code DATA_FUEL}）也已经在
 * 实体的 {@code SynchedEntityData} 里，客户端直接读得到，所以<b>不需要服务端数据提供者</b>。
 *
 * <p><b>为什么 MEK 不能替我们做</b>：查过 MEK 的
 * {@code LookingAtUtils.addInfo(LookingAtHelper, Entity)}，它只处理
 * {@code EntityRobit}（罗比特），别的实体一律不加信息。所以这块只能自己写。
 *
 * <p>气体名字走 {@link a_silly_cat.golems_arsenal.compat.mekanism.gui.MekReflect#gasName}
 * ——反射拿 MEK 的 {@code Chemical.getTextComponent()}，这样"氢气/乙烯"是 MEK 的语言文件
 * 翻出来的，我们不用自己维护一份气体名表。
 */
public enum RocketGasJadeProvider implements IEntityComponentProvider {
    INSTANCE;

    private static final ResourceLocation UID = new ResourceLocation("golems_arsenal", "rocket_gas");

    @Override
    public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
        if (!(accessor.getEntity() instanceof RocketEntity rocket)) {
            return;
        }
        String gasId = rocket.fuelGasId();
        int stored = rocket.fuelDisplay();
        int capacity = rocket.fuelCapacity();

        if (gasId.isEmpty() || stored <= 0) {
            tooltip.add(Component.translatable("jade.golems_arsenal.rocket.empty"));
            return;
        }
        Component name = a_silly_cat.golems_arsenal.compat.mekanism.gui.MekReflect.gasName(gasId);
        tooltip.add(Component.translatable("jade.golems_arsenal.rocket.fuel",
                name, stored, capacity));
    }

    @Override
    public ResourceLocation getUid() {
        return UID;
    }
}
