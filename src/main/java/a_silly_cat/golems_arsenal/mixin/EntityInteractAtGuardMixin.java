package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.sanctuary.Projection;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 交互门（方法级，SEALED）：{@code Entity#interactAt} 直接返回 {@code FAIL}。
 *
 * <p>和 {@link MobInteractGuardMixin} 配成一对：一个管"右键实体"，一个管"右键实体上的某个点"。
 * 两条都是方法级，不依赖事件总线。</p>
 */
@Mixin(Entity.class)
public abstract class EntityInteractAtGuardMixin {

    @Inject(method = "interactAt", at = @At("HEAD"), cancellable = true)
    private void ga$denyInteractAt(Player player, Vec3 pos, InteractionHand hand,
                                   CallbackInfoReturnable<InteractionResult> cir) {
        if (Projection.denyInteraction((Entity) (Object) this)) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
