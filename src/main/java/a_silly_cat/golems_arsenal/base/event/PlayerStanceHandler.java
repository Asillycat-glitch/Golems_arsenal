package a_silly_cat.golems_arsenal.base.event;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.base.item.ShenTongStaffItem;
import a_silly_cat.golems_arsenal.base.upgrade.GolemStanceModifier;
import a_silly_cat.golems_arsenal.init.ModEnchantments;
import a_silly_cat.golems_arsenal.init.ModNetwork;
import a_silly_cat.golems_arsenal.network.ClientboundStancePacket;
import dev.xkmc.l2library.init.events.GeneralEventHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

/**
 * Player counterpart of the Black Monkey stance upgrade, driven by the {@code stance}
 * enchantment applied to a Shen Tong Staff through the meme-upgrade anvil recipe. Only the player
 * holding an enchanted staff is affected (golems keep using the regular stance modifier). The
 * player's own gauge fills from attacking, moving and being hurt (+25% while holding the staff);
 * right-clicking consumes every stack to charge the next melee attack, which then deals extra
 * stance magic damage (doubled by the staff).
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID)
public final class PlayerStanceHandler {
    private static final String PROGRESS_KEY = "ga_stance_progress";
    private static final String STACKS_KEY = "ga_stance_stacks";
    private static final String CHARGED_KEY = "ga_stance_charged";
    private static final String CHARGE_STACKS_KEY = "ga_stance_charge_stacks";

    private PlayerStanceHandler() {
    }

    /** Right-click with an enchanted staff: consume every stance stack and charge the next hit. */
    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        if (player.level().isClientSide || !isActive(player)) {
            return;
        }
        CompoundTag data = player.getPersistentData();
        int stacks = data.getInt(STACKS_KEY);
        if (stacks <= 0) {
            return;
        }
        data.putInt(CHARGE_STACKS_KEY, stacks);
        data.putInt(STACKS_KEY, 0);
        data.putDouble(PROGRESS_KEY, 0);
        data.putBoolean(CHARGED_KEY, true);
        sendSync(player);
        if (player instanceof ServerPlayer sp) {
            sp.displayClientMessage(
                    Component.translatable("message.golems_arsenal.stance_charge", stacks), true);
        }
    }

    /** Melee attack with the staff: charged hits deal bonus stance magic, then fill the gauge. */
    @SubscribeEvent
    public static void onPlayerAttack(LivingHurtEvent event) {
        if (event.getEntity().level().isClientSide
                || GolemStanceModifier.isStanceMagic(event.getSource())
                || !(event.getSource().getEntity() instanceof Player player)
                || !isActive(player)) {
            return;
        }
        CompoundTag data = player.getPersistentData();
        if (data.getBoolean(CHARGED_KEY)) {
            int stacks = data.getInt(CHARGE_STACKS_KEY);
            data.putBoolean(CHARGED_KEY, false);
            data.putInt(CHARGE_STACKS_KEY, 0);
            LivingEntity target = event.getEntity();
            if (stacks > 0 && target.isAlive()) {
                float bonus = event.getAmount() * stacks
                        * Config.STANCE_MELEE_RATIO.get().floatValue() * 2;
                if (bonus > 0) {
                    scheduleMagicHit(player, target, bonus);
                }
            }
        }
        sendSync(player);
        addProgress(player, Config.STANCE_ATTACK_GAIN.get());
    }

    /** Being hurt while holding the staff fills the gauge. */
    @SubscribeEvent
    public static void onPlayerHurt(LivingHurtEvent event) {
        if (event.getEntity().level().isClientSide
                || !(event.getEntity() instanceof Player player) || !isActive(player)) {
            return;
        }
        sendSync(player);
        addProgress(player, Config.STANCE_HURT_GAIN.get());
    }

    /** Movement fills the gauge; dropping the staff cancels a pending charge. */
    @SubscribeEvent
    public static void onTick(LivingEvent.LivingTickEvent event) {
        if (event.getEntity().level().isClientSide
                || !(event.getEntity() instanceof Player player)) {
            return;
        }
        CompoundTag data = player.getPersistentData();
        if (!isActive(player)) {
            if (data.getBoolean(CHARGED_KEY)) {
                data.putBoolean(CHARGED_KEY, false);
                data.putInt(CHARGE_STACKS_KEY, 0);
                sendSync(player);
            }
            if (player.tickCount % 10 == 0) {
                sendSync(player);
            }
            return;
        }
        if (player.tickCount % 10 == 0) {
            sendSync(player);
        }
        double dx = player.getX() - player.xo;
        double dz = player.getZ() - player.zo;
        double moved = Math.sqrt(dx * dx + dz * dz);
        if (moved > 0.001) {
            addProgress(player, moved * Config.STANCE_MOVE_GAIN.get());
        }
    }

    private static boolean isActive(Player player) {
        ItemStack stack = player.getMainHandItem();
        return stack.getItem() instanceof ShenTongStaffItem
                && stack.getEnchantmentLevel(ModEnchantments.STANCE.get()) > 0;
    }

    /** Shen Tong Staff bonus: +25% gauge from every source (attack/move/hurt). */
    private static void addProgress(Player player, double amount) {
        if (amount <= 0) {
            return;
        }
        amount *= 1.25;
        CompoundTag data = player.getPersistentData();
        double progress = data.getDouble(PROGRESS_KEY) + amount;
        int stacks = data.getInt(STACKS_KEY);
        double gauge = Config.STANCE_GAUGE_MAX.get();
        int maxStacks = maxStacks(player);
        while (progress >= gauge) {
            if (stacks >= maxStacks) {
                progress = 0;
                break;
            }
            progress -= gauge;
            stacks++;
        }
        data.putDouble(PROGRESS_KEY, progress);
        data.putInt(STACKS_KEY, stacks);
    }

    /** Level 1: 3 stacks; level 2 unlocks the 4th stack. */
    private static int maxStacks(Player player) {
        return player.getMainHandItem().getEnchantmentLevel(ModEnchantments.STANCE.get()) >= 2 ? 4 : 3;
    }

    /** Sends the current gauge to the player's client for HUD rendering. */
    private static void sendSync(Player player) {
        if (!(player instanceof ServerPlayer sp)) {
            return;
        }
        CompoundTag data = player.getPersistentData();
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp),
                new ClientboundStancePacket(data.getInt(STACKS_KEY),
                        data.getDouble(PROGRESS_KEY), data.getBoolean(CHARGED_KEY)));
    }

    /** Delivers the extra magic hit one tick later, so the melee event cannot recurse into itself. */
    private static void scheduleMagicHit(Player player, LivingEntity target, float bonus) {
        if (!(player.level() instanceof ServerLevel server)) {
            return;
        }
        long time = server.getGameTime();
        GeneralEventHandler.schedulePersistent(() -> {
            if (server.getGameTime() < time + 1) {
                return false;
            }
            if (target.isAlive()) {
                target.hurt(GolemStanceModifier.magicSource(player), bonus);
            }
            return true;
        });
    }
}
