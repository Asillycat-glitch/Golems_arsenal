package a_silly_cat.golems_arsenal.compat.mekanism;

import a_silly_cat.golems_arsenal.tech.energy.MinionEnergyAccess;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.math.FloatingLong;
import mekanism.common.entity.EntityRobit;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * MEK 机器人的能量适配器。
 *
 * <p>Mekanism 的 Robit 在实体上没有挂 Forge {@code IEnergyStorage}，只有一个公开的
 * {@link EntityRobit#getEnergyContainer()}。它的能量容器是 {@code BasicEnergyContainer.input}：
 * 外部可以插入、不能抽取，正好符合“只给仆从充电、不反向抽干”的语义。
 *
 * <p>这个类只在 {@code MekanismCompat.register} 里被装载，因此没装 MEK 的包里不会出现
 * 任何 {@code mekanism.*} 类型。
 */
public final class MekanismMinionEnergy implements MinionEnergyAccess.Bridge {

    public static void install() {
        MinionEnergyAccess.setBridge(new MekanismMinionEnergy());
    }

    @Override
    public boolean hasEnergy(Entity entity) {
        return entity instanceof EntityRobit robit
                && robit.getEnergyContainer().getNeeded().greaterThan(FloatingLong.ZERO);
    }

    @Override
    public int insert(Entity entity, int amount, boolean simulate) {
        if (!(entity instanceof EntityRobit robit) || amount <= 0) {
            return 0;
        }
        FloatingLong accepted = robit.getEnergyContainer().insert(
                FloatingLong.create(amount),
                simulate ? Action.SIMULATE : Action.EXECUTE,
                AutomationType.EXTERNAL);
        return accepted.intValue();
    }

    @Override
    public boolean isOwnedBy(Entity entity, UUID player) {
        return entity instanceof EntityRobit robit && player.equals(robit.getOwnerUUID());
    }
}
