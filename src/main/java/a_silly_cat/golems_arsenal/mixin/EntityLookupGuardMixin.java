package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.guard.sanctuary.IndexGuard;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 索引门（查询层）：{@code EntityLookup#remove}。
 *
 * <p>{@code ClassInstanceMultiMap} 管的是"区块里还有没有它"，这一层管的是
 * {@code level.getEntity(uuid)} / {@code getEntity(id)} 还能不能查到。两条都拦，
 * 才不会出现"实体还在 tick，但所有按 UUID/ID 的查询都说它没了"。</p>
 */
@Mixin(EntityLookup.class)
public abstract class EntityLookupGuardMixin {

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void ga$guardLookupRemove(EntityAccess value, CallbackInfo ci) {
        if (IndexGuard.denyIndexRemoval(value, "EntityLookup.remove")) {
            ci.cancel();
        }
    }
}
