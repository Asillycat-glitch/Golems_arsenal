package a_silly_cat.golems_arsenal.guard.combat;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.Golems_arsenal;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 源级“能力准入”：<b>只看攻击路径</b>。
 *
 * <p>2026-11 收窄：不再取消 {@code RightClickItem}/{@code RightClickBlock}/{@code EntityInteract}/
 * {@code UseItem.Start}，也不再碰 {@code inventoryTick}/{@code swing}。原因有两条：</p>
 * <ol>
 *   <li>用户要的是“别左键误伤自己的傀儡”，不是“右键范围整段被吞掉”——后者看起来就像模组冲突；</li>
 *   <li>现在有目标侧 {@code Sanctuary} 兜底：主人/友军的伤害在受击侧就被拒，
 *       不需要在源侧猜物品。</li>
 * </ol>
 *
 * <p>留下的两个入口（{@code AttackEntityEvent} + {@code LivingAttackEvent}）只做一件事：
 * 打的是玩家傀儡、手里的物品是“复合战斗物品”且没有适配器时，在 SHADOW 记一笔 / 在 ENFORCE 取消。
 * 默认 SHADOW。</p>
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID)
public final class CombatAdmission {

    private static final Map<UUID, Integer> LAST_LOG = new ConcurrentHashMap<>();
    private static volatile CombatMode override;
    private static int lastGlobalLogTick;

    private CombatAdmission() {
    }

    public static CombatMode mode() {
        CombatMode value = override;
        if (value != null) {
            return value;
        }
        try {
            return Config.DAMAGE_ADMISSION_COMBAT_MODE.get();
        } catch (Throwable ignored) {
            return CombatMode.SHADOW;
        }
    }

    public static void setOverride(CombatMode value) {
        override = value;
    }

    public static String status() {
        CombatMode value = override;
        return "combat=" + mode()
                + (value == null ? "(config)" : "(runtime)");
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttackEntity(AttackEntityEvent event) {
        if (!(event.getTarget() instanceof AbstractGolemEntity<?, ?> golem)
                || !protectedGolem(golem)) {
            return;
        }
        Player player = event.getEntity();
        ItemStack stack = player.getMainHandItem();
        if (shouldBlock(player, stack, "attack")) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingAttack(LivingAttackEvent event) {
        if (!(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)
                || !protectedGolem(golem)) {
            return;
        }
        ItemStack stack = heldBy(event.getSource().getEntity());
        if (stack.isEmpty()) {
            stack = heldBy(event.getSource().getDirectEntity());
        }
        if (stack.isEmpty() && event.getSource().getDirectEntity() instanceof Projectile projectile) {
            stack = heldBy(projectile.getOwner());
        }
        if (stack.isEmpty()) {
            return;
        }
        if (shouldBlock(null, stack, "hurt")) {
            event.setCanceled(true);
        }
    }

    private static boolean shouldBlock(Player player, ItemStack stack, String op) {
        CombatMode current = mode();
        if (current == CombatMode.OFF) {
            return false;
        }
        CombatAction action = CombatAction.forItem(stack, player, player);
        if (!action.combat() || action.supported()) {
            return false;
        }
        if (current == CombatMode.SHADOW) {
            log(player, "would_reject " + op, action);
            return false;
        }
        log(player, "rejected " + op, action);
        return true;
    }

    private static boolean protectedGolem(AbstractGolemEntity<?, ?> golem) {
        return golem.getOwnerUUID() != null;
    }

    private static ItemStack heldBy(Entity entity) {
        return entity instanceof LivingEntity living ? living.getMainHandItem() : ItemStack.EMPTY;
    }

    private static void log(Player player, String op, CombatAction action) {
        if (player != null) {
            int now = player.tickCount;
            Integer last = LAST_LOG.put(player.getUUID(), now);
            if (last != null && now - last < 20) {
                return;
            }
        } else {
            // 没有玩家上下文（LivingAttackEvent 只有攻击者实体）：用全局节流，避免刷屏。
            int now = (int) (System.nanoTime() / 50_000_000L);
            if (now - lastGlobalLogTick < 20) {
                return;
            }
            lastGlobalLogTick = now;
        }
        Golems_arsenal.LOGGER.info("[CombatAdmission] {} item={} {}",
                op, action.item().getDescriptionId(), action.describe());
    }
}
