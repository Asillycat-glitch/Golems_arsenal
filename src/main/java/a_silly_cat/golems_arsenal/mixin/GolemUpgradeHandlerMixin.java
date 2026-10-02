package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.init.ModTags;
import dev.xkmc.modulargolems.content.item.upgrade.IUpgradeItem;
import dev.xkmc.modulargolems.content.item.upgrade.UpgradeItem;
import dev.xkmc.modulargolems.content.menu.table.GolemUpgradeItemHandler;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;

/**
 * Special moves are mutually exclusive: while any item tagged
 * {@code golems_arsenal:special_move_upgrades} is already installed on the holder, the golem
 * workbench rejects adding another one.
 * <p>
 * <b>关于方法名的解析</b>（别被"开发环境能跑"骗了）：开发环境用的是 official 名
 * （{@code appendUpgrade}），而生产环境（reobf 后的 jar）里方法名是 SRG 名，Mixin 靠
 * {@code golems_arsenal.refmap.json} 把 {@code @Inject}/{@code @Shadow} 里的名字翻译过去。
 * <p>
 * 本 mixin 目前<b>不需要</b> refmap 条目，是因为它注入的 {@code GolemUpgradeItemHandler.appendUpgrade}
 * 与影子字段 {@code upgrades} 都属于 Modular Golems 自己的类——mod 代码不会被重映射，名字两边一致。
 * 但同文件里的 {@link AbstractGolemEnergyMixin} 注入的是 <b>vanilla 方法</b>
 * （{@code addAdditionalSaveData} → {@code m_7380_}），那就非有 refmap 不可。
 * 所以：<b>以后凡是注入 vanilla 方法/字段，必须同步更新 {@code src/main/resources/golems_arsenal.refmap.json}</b>
 * ——该文件是手工维护的，Mixin 的注解处理器在本项目里并不生成它。
 */
@Mixin(GolemUpgradeItemHandler.class)
public abstract class GolemUpgradeHandlerMixin {

    @Shadow
    private ArrayList<IUpgradeItem> upgrades;

    @Inject(method = "appendUpgrade", at = @At("HEAD"), cancellable = true)
    private void golemsArsenal$rejectSecondSpecialMove(UpgradeItem incoming,
                                                       CallbackInfoReturnable<ItemStack> cir) {
        if (!isSpecialMove(incoming)) {
            return;
        }
        for (IUpgradeItem existing : upgrades) {
            if (existing != incoming && existing instanceof Item item && isSpecialMove(item)) {
                cir.setReturnValue(ItemStack.EMPTY);
                return;
            }
        }
    }

    private static boolean isSpecialMove(Item item) {
        return new ItemStack(item).is(ModTags.SPECIAL_MOVE_UPGRADES);
    }
}
