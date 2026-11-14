package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.sanctuary.SanctuaryScopes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 读档作用域：只包真正的加载入口 {@code Entity#load}。
 *
 * <p>2026-11-06 修：原来包在 {@code LivingEntity#readAdditionalSaveData} 上，
 * 但那个方法是 <b>public</b> 的——任何模组都可以构造一个 {@code CompoundTag}（写
 * {@code Health=0}）然后直接调它，把"读档"当成"合法写血"。</p>
 *
 * <p>ArcaneVortex 的 {@code HealthModify.tagModify} 就是这么干的：
 * {@code entity.saveWithoutId(tag)} → {@code tag.putFloat("Health", 0)} → {@code entity.load(tag)}。
 * 现在作用域开在 {@code Entity#load}：只有从真正的加载入口进来的写才算读档，
 * 外部直接调 {@code readAdditionalSaveData} 不再被放行。</p>
 */
@Mixin(Entity.class)
public abstract class EntityLoadScopeMixin {

    @Inject(method = "load", at = @At("HEAD"))
    private void ga$enterLoad(CompoundTag tag, CallbackInfo ci) {
        // 生成时洗身份：物品/NBT 里的 UUID 如果已经在假死名单里，换成新名字再读。
        // 放在 enterLoad 之前，这样后续读到的是洗过的 UUID。
        a_silly_cat.golems_arsenal.guard.sanctuary.IdentityGuard
                .scrubTag((Entity) (Object) this, tag);
        SanctuaryScopes.enterLoad();
    }

    @Inject(method = "load", at = @At("RETURN"))
    private void ga$exitLoad(CompoundTag tag, CallbackInfo ci) {
        SanctuaryScopes.exitLoad();
    }
}
