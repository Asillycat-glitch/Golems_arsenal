package a_silly_cat.golems_arsenal.base.event;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.base.upgrade.GolemETankModifier;
import a_silly_cat.golems_arsenal.init.ModNetwork;
import a_silly_cat.golems_arsenal.network.ClientboundETankPacket;
import dev.xkmc.modulargolems.content.config.GolemMaterial;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

/**
 * E 罐的"喂材料"入口：玩家拿着这只傀儡的修复材料右键它。
 * <p>
 * 判定顺序（不含重铸）：
 * <ol>
 *   <li>血量 ≤ 75%（= 本家"值得回血"的界线，一份 25% 不会溢出）→ 直接补血，消耗 1 个材料；</li>
 *   <li>血量 &gt; 75% 且罐子没满 → 材料进罐，消耗 1 个材料；</li>
 *   <li>血量 &gt; 75% 且罐子满了 → 不取消事件，交回本家处理（也就是说这时才会走"重铸加护甲"）。</li>
 * </ol>
 * 事件在实体自己的 {@code mobInteract} 之前触发，所以这里取消掉就能抢先处理；只对"装了 E 罐
 * 升级 + 拿着匹配材料"的情况生效，其余情况原版行为完全不变。
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID)
public final class GolemETankHandler {

    /**
     * 玩家刚开始追踪这只傀儡时（刚进世界 / 走进范围）补发一次罐内存量。
     * <p>
     * 定期同步走的是"值变了才发"，服务端记着"已经发过 8 / 8 了"，但客户端刚连上时手上什么都没有，
     * 于是要等到份数变化才会显示——这就是偶尔看不到"E罐：x / y 份材料"的原因。
     */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getTarget() instanceof AbstractGolemEntity<?, ?> golem)
                || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        int level = GolemETankModifier.levelOf(golem);
        if (level <= 0) {
            return;
        }
        GolemETankModifier.sendTo(player, golem, level);
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        int level = GolemETankModifier.levelOf(golem);
        if (level <= 0) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) {
            return;
        }
        ResourceLocation material = GolemMaterial.getRepairMaterial(stack).orElse(null);
        if (material == null) {
            return;
        }
        boolean matches = false;
        for (GolemMaterial mat : golem.getMaterials()) {
            if (mat.id().equals(material)) {
                matches = true;
                break;
            }
        }
        if (!matches) {
            return;
        }
        // 沿用本家 repairWithItem() 的那条界线：≤75% 才值得直接吃材料补血，否则存罐。
        boolean heal = GolemETankModifier.canHealEffectively(golem);
        if (!heal && GolemETankModifier.stored(golem) >= GolemETankModifier.capacity(level)) {
            // 罐满：不拦，交给本家（重铸那条路）。
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        if (event.getLevel().isClientSide) {
            return;   // 客户端只负责取消掉原版交互，真正的结算在服务端
        }
        if (heal) {
            golem.repair(GolemETankModifier.healAmount(golem));
        } else {
            GolemETankModifier.storeOne(golem, level);
        }
        // 立刻把新的份数同步给客户端（信息面板上的"E罐：x / y 份材料"）。
        GolemETankModifier.syncIfChanged(golem, level);
        Player player = event.getEntity();
        if (player == null || !player.getAbilities().instabuild) {
            stack.shrink(1);
        }
    }

    private GolemETankHandler() {
    }
}
