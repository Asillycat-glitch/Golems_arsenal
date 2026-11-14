package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.guard.sanctuary.Sanctuary;
import a_silly_cat.golems_arsenal.guard.sanctuary.SanctuaryMode;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 匠战妖域 · witherzilla 的清除物品——它是<b>删磁盘上的实体区域文件</b>。
 *
 * <p>读出来的实现：{@code MegaRemoverItem.deleteEntitiesFolder(ServerLevel)} /
 * {@code deleteAllEntitiesFolders(MinecraftServer)}，直接删 {@code entities/} 目录下的文件。
 * 这已经越过世界索引，属于文件层擦除；任何 Java 层的实体修复都救不回来，
 * 所以这里在 ENFORCE 下直接整体拦掉这两个方法（“先全部挡住”）。</p>
 */
@Mixin(targets = "com.wzz.witherzilla.item.MegaRemoverItem", remap = false)
public abstract class WitherzillaRemoverGuardMixin {

    @Inject(method = "deleteEntitiesFolder(Lnet/minecraft/server/level/ServerLevel;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void ga$deleteFolder(ServerLevel level, CallbackInfo ci) {
        if (Sanctuary.mode() == SanctuaryMode.ENFORCE) {
            Golems_arsenal.LOGGER.warn("[Sanctuary] blocked site=witherzilla.deleteEntitiesFolder level={}",
                    level.dimension().location());
            ci.cancel();
        }
    }

    @Inject(method = "deleteAllEntitiesFolders(Lnet/minecraft/server/MinecraftServer;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void ga$deleteAll(MinecraftServer server, CallbackInfo ci) {
        if (Sanctuary.mode() == SanctuaryMode.ENFORCE) {
            Golems_arsenal.LOGGER.warn("[Sanctuary] blocked site=witherzilla.deleteAllEntitiesFolders");
            ci.cancel();
        }
    }
}
