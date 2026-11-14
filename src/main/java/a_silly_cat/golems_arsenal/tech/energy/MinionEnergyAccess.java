package a_silly_cat.golems_arsenal.tech.energy;

import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;

import java.util.UUID;

/**
 * “给一个仆从充能”的统一入口。
 *
 * <p>优先级：
 * <ol>
 *   <li>实体自己的 Forge {@link ForgeCapabilities#ENERGY}（模块化傀儡、绝大多数科技模组实体）；</li>
 *   <li>**数据包反射适配器** {@link MinionEnergyRegistry}（给只有私有 FE 缓存、没挂能力的老模组用）；</li>
 *   <li>Mekanism Robit 的专用适配器（它只暴露 {@code IEnergyContainer}，没有 Forge 能力）。</li>
 * </ol>
 *
 * <p>反射部分刻意只做“按数据包给的字段名取一个值”这一种最通用的形状；没有按模组写死
 * 反射，也没有在每 tick 做类名查找。
 */
public final class MinionEnergyAccess {

    /** 数据包/内置适配器共用的最小接口。 */
    public interface Access {
        boolean hasEnergy(Entity entity);

        int insert(Entity entity, int amount, boolean simulate);

        boolean isOwnedBy(Entity entity, UUID player);
    }

    /**
     * 非 Forge-ENERGY 的内置适配器（目前是 MEK Robit）。
     *
     * <p>由 {@code MekanismCompat} 在 MEK 存在时安装；没装 MEK 时保持 null。
     */
    public interface Bridge extends Access {
    }

    private static volatile Bridge bridge;

    public static void setBridge(Bridge value) {
        bridge = value;
    }

    /** 这个实体能不能接收 FE（有容量、可接收、且还没满）。 */
    public static boolean hasEnergy(Entity entity) {
        var forge = entity.getCapability(ForgeCapabilities.ENERGY);
        if (forge.isPresent()) {
            IEnergyStorage storage = forge.orElse(null);
            return storage != null
                    && storage.canReceive()
                    && storage.getMaxEnergyStored() > 0
                    && storage.getEnergyStored() < storage.getMaxEnergyStored();
        }
        Access custom = MinionEnergyRegistry.find(entity);
        if (custom != null) {
            return custom.hasEnergy(entity);
        }
        Bridge b = bridge;
        return b != null && b.hasEnergy(entity);
    }

    /**
     * 向实体插入 FE，返回实际接受量。
     *
     * <p>先试 Forge 能力；没有时再交给数据包适配器 / MEK 适配器。
     */
    public static int insert(Entity entity, int amount, boolean simulate) {
        if (amount <= 0) {
            return 0;
        }
        var forge = entity.getCapability(ForgeCapabilities.ENERGY);
        if (forge.isPresent()) {
            IEnergyStorage storage = forge.orElse(null);
            return storage == null ? 0 : storage.receiveEnergy(amount, simulate);
        }
        Access custom = MinionEnergyRegistry.find(entity);
        if (custom != null) {
            return custom.insert(entity, amount, simulate);
        }
        Bridge b = bridge;
        return b == null ? 0 : b.insert(entity, amount, simulate);
    }

    /**
     * 这个实体是不是该玩家的仆从。
     *
     * <p>顺序很重要：模块化傀儡在 {@link AbstractGolemEntity} 上有稳定的 owner UUID，
     * 即使主人不在线也能判断；其余走 {@link OwnableEntity}；数据包适配器可以声明
     * 自己的 owner 字段/方法；MEK Robit 交给内置适配器。
     */
    public static boolean isOwnedBy(Entity entity, UUID player) {
        if (!(entity instanceof LivingEntity) || player == null) {
            return false;
        }
        if (entity instanceof AbstractGolemEntity<?, ?> golem
                && player.equals(golem.getOwnerUUID())) {
            return true;
        }
        if (entity instanceof OwnableEntity ownable
                && player.equals(ownable.getOwnerUUID())) {
            return true;
        }
        // 数据包可以声明自己的 owner 字段/方法，也可以用 charge_unowned 显式覆盖。
        Access custom = MinionEnergyRegistry.find(entity);
        if (custom != null && custom.isOwnedBy(entity, player)) {
            return true;
        }
        Bridge b = bridge;
        return b != null && b.isOwnedBy(entity, player);
    }

    private MinionEnergyAccess() {
    }
}
