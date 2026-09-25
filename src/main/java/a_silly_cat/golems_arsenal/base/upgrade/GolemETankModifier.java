package a_silly_cat.golems_arsenal.base.upgrade;

import a_silly_cat.golems_arsenal.init.ModNetwork;
import a_silly_cat.golems_arsenal.network.ClientboundETankPacket;
import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;

/**
 * E 罐升级：傀儡可以存储修复材料，在战斗时使用。
 * <p>
 * 罐内以"份"为单位，1 份 = 一次手动喂食的量（最大生命 / 4）。容量 = {@link #CAPACITY_PER_LEVEL}
 * × 等级 → 4 / 8 份，也就是 100% / 200% 最大生命。
 * <p>
 * 判定（不含重铸）：
 * <ul>
 *   <li>喂材料时血量 ≤ 75% → 直接补血（一份 25% 正好不溢出，和本家的界线一致）；</li>
 *   <li>喂材料时血量 &gt; 75%（含满血）→ 存进罐子；罐满则放行给本家，让"重铸加护甲"那条路照旧；</li>
 *   <li>战斗中（有攻击目标或刚受击）血量 ≤ 75% 且罐里有份 → 自动消耗 1 份回 25% 最大生命。</li>
 * </ul>
 * 全部走 {@code golem.repair(float)}，不会消耗重铸次数、也不会播本家的修复音效。
 */
public class GolemETankModifier extends GolemModifier {
    public static final int MAX_LEVEL = 2;

    /** 每份材料回复 = 最大生命 / 该值（和本家手动喂食一致）。 */
    public static final float HEAL_DENOMINATOR = 4.0F;
    /** 罐容量（份）= 该值 × 等级 → 4 / 8 份 = 100% / 200% 最大生命。 */
    public static final int CAPACITY_PER_LEVEL = 4;

    /**
     * "值得回血"的血量界线：一份材料回 25% 最大生命，所以血量高于 75% 时喂进去必有一部分被上限吃掉。
     * 本家 {@code repairWithItem()} 用的就是这条线（&gt;75% 时不再回血、改走重铸），这里沿用同一条，
     * 手动喂和自动用都按它判定，避免"99% 血喂一份只回 1%"这种浪费。
     */
    public static final float EFFECTIVE_HEAL_RATIO = 0.75F;

    /** 自动使用的最小间隔（tick）。 */
    private static final int AUTO_INTERVAL = 20;
    /** 往客户端同步"罐内存量"的间隔（tick）。 */
    private static final int SYNC_INTERVAL = 10;
    private static final String STORED_KEY = "GolemsArsenalETankStored";
    private static final String AUTO_CD_KEY = "GolemsArsenalETankAutoCooldown";
    /** 上一次同步出去的值，用来避免每 tick 都发包。 */
    private static final String SYNCED_KEY = "GolemsArsenalETankSynced";
    private static final String SYNCED_CAP_KEY = "GolemsArsenalETankSyncedCapacity";

    public GolemETankModifier() {
        super(StatFilterType.HEALTH, MAX_LEVEL);
    }

    /** 傀儡身上这个升级的等级（没装 = 0）。 */
    public static int levelOf(AbstractGolemEntity<?, ?> golem) {
        return GolemUpgradeLevels.levelOf(golem, "golems_arsenal:e_tank");
    }

    /** 该等级下罐子的容量（份）。 */
    public static int capacity(int level) {
        return Math.max(0, level) * CAPACITY_PER_LEVEL;
    }

    /** 罐内现有份数。 */
    public static int stored(AbstractGolemEntity<?, ?> golem) {
        return Math.max(0, golem.getPersistentData().getInt(STORED_KEY));
    }

    /** 存 1 份；罐满返回 false（调用方据此决定放行给本家逻辑）。 */
    public static boolean storeOne(AbstractGolemEntity<?, ?> golem, int level) {
        int stored = stored(golem);
        if (stored >= capacity(level)) {
            return false;
        }
        golem.getPersistentData().putInt(STORED_KEY, stored + 1);
        return true;
    }

    /** 取走 1 份；罐里没有返回 false。 */
    public static boolean consumeOne(AbstractGolemEntity<?, ?> golem) {
        int stored = stored(golem);
        if (stored <= 0) {
            return false;
        }
        golem.getPersistentData().putInt(STORED_KEY, stored - 1);
        return true;
    }

    /** 一份材料的回复量。 */
    public static float healAmount(AbstractGolemEntity<?, ?> golem) {
        return golem.getMaxHealth() / HEAL_DENOMINATOR;
    }

    /** 把罐内份数发给追踪这只傀儡的客户端（信息面板显示用）。 */
    public static void sync(AbstractGolemEntity<?, ?> golem, int level) {
        ModNetwork.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> golem),
                new ClientboundETankPacket(golem.getId(), displayStored(golem, capacity(level)),
                        capacity(level)));
    }

    /**
     * 发给客户端的份数（显示用）。
     * <p>
     * 正常路径上罐子是存不超的（{@link #storeOne} 会挡），只有"升级被拆下来、容量变小"时
     * 罐里的份数才会大于容量，这时显示上夹一下，别显示成 8 / 4 这种。
     */
    private static int displayStored(AbstractGolemEntity<?, ?> golem, int capacity) {
        return Math.min(stored(golem), Math.max(0, capacity));
    }

    /** 只把当前存量发给某一个玩家（他刚开始追踪这只傀儡时补发用）。 */
    public static void sendTo(net.minecraft.server.level.ServerPlayer player,
                              AbstractGolemEntity<?, ?> golem, int level) {
        int capacity = capacity(level);
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new ClientboundETankPacket(golem.getId(), displayStored(golem, capacity), capacity));
    }

    /** 值（份数或容量）变了才发，避免每秒都在发包。 */
    public static void syncIfChanged(AbstractGolemEntity<?, ?> golem, int level) {
        CompoundTag tag = golem.getPersistentData();
        int capacity = capacity(level);
        int stored = displayStored(golem, capacity);
        if (tag.getInt(SYNCED_KEY) == stored && tag.getInt(SYNCED_CAP_KEY) == capacity) {
            return;
        }
        tag.putInt(SYNCED_KEY, stored);
        tag.putInt(SYNCED_CAP_KEY, capacity);
        sync(golem, level);
    }

    /** 现在消耗一份材料能不能"全额"回血（= 本家那条 75% 界线）。 */
    public static boolean canHealEffectively(AbstractGolemEntity<?, ?> golem) {
        return golem.getHealth() <= golem.getMaxHealth() * EFFECTIVE_HEAL_RATIO;
    }

    @Override
    public Component getTooltip(int level) {
        return Component.translatable("upgrade.golems_arsenal.e_tank")
                .withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        return List.of(
                Component.translatable("upgrade.golems_arsenal.e_tank.desc")
                        .withStyle(ChatFormatting.GREEN),
                Component.translatable("upgrade.golems_arsenal.e_tank.capacity",
                        capacity(level)).withStyle(ChatFormatting.GRAY),
                Component.translatable("upgrade.golems_arsenal.e_tank.heal",
                        Math.round(100.0F / HEAL_DENOMINATOR)).withStyle(ChatFormatting.GRAY),
                Component.translatable("upgrade.golems_arsenal.e_tank.threshold",
                        Math.round(EFFECTIVE_HEAL_RATIO * 100.0F)).withStyle(ChatFormatting.GRAY));
    }

    @Override
    public void onAiStep(AbstractGolemEntity<?, ?> golem, int level) {
        if (golem.level().isClientSide) {
            return;
        }
        // 定时把存量同步给客户端（值没变就不发），这样信息面板上能一直看到份数。
        if (golem.tickCount % SYNC_INTERVAL == 0) {
            syncIfChanged(golem, level);
        }
        if (golem.tickCount % AUTO_INTERVAL != 0) {
            return;
        }
        // "可以用材料修复就修复"：沿用本家 75% 那条界线（再高就会被上限吃掉），罐里没份也不用。
        if (!canHealEffectively(golem) || stored(golem) <= 0) {
            return;
        }
        // 战斗时使用：有攻击目标，或者刚刚被打到。
        if (golem.getTarget() == null && golem.hurtTime <= 0) {
            return;
        }
        long now = golem.level().getGameTime();
        CompoundTag tag = golem.getPersistentData();
        if (now < tag.getLong(AUTO_CD_KEY)) {
            return;
        }
        tag.putInt(STORED_KEY, stored(golem) - 1);
        tag.putLong(AUTO_CD_KEY, now + AUTO_INTERVAL);
        golem.repair(healAmount(golem));
        if (golem.level() instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.HEART, golem.getX(),
                    golem.getY() + golem.getBbHeight() * 0.8, golem.getZ(),
                    3, 0.3, 0.2, 0.3, 0.02);
        }
    }
}
