package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.ProtectedEntityPolicy;
import a_silly_cat.golems_arsenal.guard.sanctuary.Anchor;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端死亡动画的最终门：受保护实体每 tick 把 deathTime/dead 清零。
 *
 * <p>toaru 的 GungnirLivingEntityMixin 在 isAlive/isDeadOrDying 上用了
 * priority=Integer.MAX_VALUE，读侧可能先返回“已死”；renderer 又直接读
 * deathTime。这里在 tick 尾部把死亡动画状态按受保护 UUID 拉回，
 * 不依赖 isAlive 的注入顺序。</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityClientDeathGuardMixin {

    @Shadow
    protected int deathTime;

    @Shadow
    protected boolean dead;

    @Inject(method = "tick", at = @At("TAIL"))
    private void ga$clearClientDeathAnimation(CallbackInfo ci) {
        if (!((Object) this instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        if (!golem.level().isClientSide) {
            return;
        }
        if (!ProtectedEntityPolicy.protectsClient(golem)
                && !Anchor.isProtectedEntityId(golem.getId())
                && !Anchor.isProtectedUuid(golem.getUUID())) {
            return;
        }
        if (this.deathTime != 0) {
            this.deathTime = 0;
        }
        if (this.dead) {
            this.dead = false;
        }
    }
}
