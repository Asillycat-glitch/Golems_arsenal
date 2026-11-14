package a_silly_cat.golems_arsenal.tech.energy;

import a_silly_cat.golems_arsenal.Config;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * **仆从充能链接** —— 把“玩家身上的物品充电”转换成“给附近仆从充电”的中转卡。
 *
 * <p>设计背景：傀儡的 FE 池只能靠玩家手动回收（或雷击）补充，战时没有补给手段。
 * Powah 的玩家供电仪 / 通量网络的控制器的无线充电、MEK 充电台，都是**给玩家背包里的
 * 物品**充 FE；它们不认识傀儡实体。所以这里放一张带 Forge {@code IEnergyStorage}
 * 的卡在玩家背包里：
 *
 * <pre>
 *   Powah / Flux / MEK 充电台
 *          ↓  IEnergyStorage.receiveEnergy
 *   仆从充能链接（1M FE 缓冲）
 *          ↓  每 10 tick，把缓冲发给附近“属于玩家、且有能量能力”的仆从
 *   模块化傀儡 / MEK 机器人 / 其它 OwnableEntity
 * </pre>
 *
 * <p>这样不需要给 Powah/Flux 写硬依赖，也不需要把傀儡伪装成物品；任何会调用
 * {@code IEnergyStorage.receiveEnergy} 的充电器都能用。卡本身不发电、不抽仆从的能量。
 *
 * <p><b>限制</b>：卡必须放在玩家背包/快捷栏/副手/盔甲槽里（见 {@code MinionChargeLinkEvents}）；
 * 范围与传输率见 {@code Config}；只给“主人 UUID = 该玩家”的仆从充能。
 */
public class MinionChargeLinkItem extends Item {

    /** 卡内缓冲的 NBT 键。 */
    public static final String TAG_ENERGY = "MinionChargeLinkEnergy";

    public MinionChargeLinkItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    // ------------------------------------------------------------------
    // 卡内缓冲
    // ------------------------------------------------------------------

    public static int capacity() {
        return Math.max(0, Config.MINION_CHARGE_LINK_BUFFER.get());
    }

    public static int stored(ItemStack stack) {
        int raw = stack.getOrCreateTag().getInt(TAG_ENERGY);
        return Mth.clamp(raw, 0, capacity());
    }

    private static void setStored(ItemStack stack, int value) {
        stack.getOrCreateTag().putInt(TAG_ENERGY, Mth.clamp(value, 0, capacity()));
    }

    // ------------------------------------------------------------------
    // 每 10 tick：把缓冲发给附近己方仆从
    // ------------------------------------------------------------------

    /**
     * 由 {@code MinionChargeLinkEvents} 每 10 tick 调用一次。
     *
     * <p>预算 = {@code transfer × 10}，在范围内所有合法仆从之间平均分配；只扣实际接受量。
     * 没有仆从 / 仆从满了时 FE 留在卡里，等下一轮。
     */
    public static void distribute(ItemStack stack, Player player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        int stored = stored(stack);
        if (stored <= 0) {
            return;
        }
        double range = Math.max(1.0, Config.MINION_CHARGE_LINK_RANGE.get());
        AABB box = player.getBoundingBox().inflate(range);
        List<LivingEntity> minions = new ArrayList<>();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (entity == player || !entity.isAlive()) {
                continue;
            }
            if (!MinionEnergyAccess.isOwnedBy(entity, player.getUUID())) {
                continue;
            }
            if (!MinionEnergyAccess.hasEnergy(entity)) {
                continue;
            }
            minions.add(entity);
        }
        if (minions.isEmpty()) {
            return;
        }
        minions.sort(Comparator.comparingDouble(player::distanceToSqr));

        long perPulse = (long) Math.max(0, Config.MINION_CHARGE_LINK_BANDWIDTH.get()) * 10L;
        int budget = (int) Math.min(stored, Math.min(Integer.MAX_VALUE, perPulse));
        if (budget <= 0) {
            return;
        }
        int perMinion = Math.max(1, budget / minions.size());
        int spent = 0;
        for (LivingEntity minion : minions) {
            int offer = Math.min(perMinion, budget - spent);
            if (offer <= 0) {
                break;
            }
            spent += MinionEnergyAccess.insert(minion, offer, false);
        }
        if (spent > 0) {
            setStored(stack, stored - spent);
            player.getInventory().setChanged();
        }
    }

    // ------------------------------------------------------------------
    // Forge 能量能力（让 Powah / Flux / MEK 充电台能给它充电）
    // ------------------------------------------------------------------

    @Override
    public @Nullable ICapabilityProvider initCapabilities(ItemStack stack, @Nullable CompoundTag nbt) {
        return new Provider(stack);
    }

    private static final class Provider implements ICapabilityProvider {
        private final LazyOptional<IEnergyStorage> energy;

        private Provider(ItemStack stack) {
            this.energy = LazyOptional.of(() -> new Storage(stack));
        }

        @Override
        public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap,
                                                          @Nullable Direction side) {
            return cap == ForgeCapabilities.ENERGY ? this.energy.cast() : LazyOptional.empty();
        }
    }

    private static final class Storage implements IEnergyStorage {
        private final ItemStack stack;

        private Storage(ItemStack stack) {
            this.stack = stack;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            int accepted = Math.min(Math.max(maxReceive, 0), capacity() - stored(this.stack));
            if (!simulate && accepted > 0) {
                setStored(this.stack, stored(this.stack) + accepted);
            }
            return accepted;
        }

        /** 只进不出：这张卡是给仆从的补给，不应该被别的模组反向抽干。 */
        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return stored(this.stack);
        }

        @Override
        public int getMaxEnergyStored() {
            return capacity();
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return true;
        }
    }

    // ------------------------------------------------------------------
    // 文案
    // ------------------------------------------------------------------

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("item.golems_arsenal.minion_charge_link.desc")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.golems_arsenal.minion_charge_link.stored",
                        stored(stack), capacity())
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("item.golems_arsenal.minion_charge_link.stats",
                        Config.MINION_CHARGE_LINK_RANGE.get(),
                        Config.MINION_CHARGE_LINK_BANDWIDTH.get())
                .withStyle(ChatFormatting.DARK_AQUA));
        tooltip.add(Component.translatable("item.golems_arsenal.minion_charge_link.hint")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
