package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.sanctuary.Projection;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 交互门（方法级，SEALED）：{@code Mob#interact} 直接返回 {@code FAIL}。
 *
 * <p>为什么不能只靠 {@code PlayerInteractEvent}：那条事件只在
 * {@code Player#interactOn} 这一条路上发。别的模组、命令、脚本只要直接调
 * {@code entity.interact(player, hand)} / {@code mobInteract} 就绕过去了——
 * 而 Modular Golems 的装配台/回收手杖走的正是方法。</p>
 *
 * <p>{@code Mob#interact} 在 1.20.1 是 {@code public final}，所有子类都逃不掉，
 * 所以挂在这里最省事；{@code interactAt}（对着某一点右键）另挂一个。</p>
 */
@Mixin(Mob.class)
public abstract class MobInteractGuardMixin {

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void ga$denyInteract(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        // 回收手杖的 interactLivingEntity 会走 Mob#interact -> RetrievalWandItem -> toItem -> discard()。
        // 先记一个回收窗口，RemovalGuard 才分得清"玩家回收"和"攻击方 discard"。
        if (player.getItemInHand(hand).getItem()
                instanceof dev.xkmc.modulargolems.content.item.wand.RetrievalWandItem) {
            a_silly_cat.golems_arsenal.guard.sanctuary.RemovalGuard
                    .notePlayerRecovery((net.minecraft.world.entity.Entity) (Object) this);
        }
        InteractionResult denied = Projection.denyInteraction((Mob) (Object) this, player, hand);
        if (denied != null) {
            cir.setReturnValue(denied);
        }
    }
}
