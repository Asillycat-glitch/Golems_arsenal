package a_silly_cat.golems_arsenal.tech.orbit;

import a_silly_cat.golems_arsenal.base.entity.RocketEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 火箭物品 —— 放下去生成 {@link RocketEntity}。
 *
 * <p>设计见 {@code docs/ORBITAL-LASER-DESIGN.md} §2：火箭物品是"载具的地面形态"，
 * 放置后成为实体，加燃料、发射、升空、入轨。
 *
 * <p><b>放置时会检查占地（5×5）</b>——用户定的"占地至少 5×5"，空间不足就放不下去。
 * 点火前还会再查一次整根柱体（那次由实体负责，因为高度依赖当时的位置）。
 *
 * <p><b>TODO 燃料</b>：用户要求"火箭物品也能装燃料"（在 UI 里预装）。
 * 这需要把 MEK 的气体能力挂到物品上，尚未实现——目前放置出来的火箭燃料是空的。
 */
public class RocketItem extends Item {

    public RocketItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos clicked = context.getClickedPos();

        // 火箭的脚要落在被点方块的**上表面**，也就是"这一格的 Y"。
        // 不能写 clicked.above()：那会让整个火箭高出一格，看起来就是腿扎进地里
        // （模型渲染时腿底已经精确对齐实体的 Y=0，见 RocketRenderer）。
        BlockPos target = clicked;

        // 占地检查查的是"地板上面那一格"——火箭本体占据的空间
        BlockPos footprint = clicked.above();
        if (!RocketEntity.footprintClear(level, footprint)) {
            Player placer = context.getPlayer();
            if (placer != null && !level.isClientSide) {
                placer.displayClientMessage(
                        Component.translatable("message.golems_arsenal.rocket_no_space")
                                .withStyle(ChatFormatting.RED), true);
            }
            return InteractionResult.FAIL;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel server)) {
            return InteractionResult.PASS;
        }

        Player placer = context.getPlayer();
        Vec3 pos = new Vec3(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        RocketEntity rocket = RocketEntity.place(server, pos);
        // setOwner 只接受非 null —— 发射器/命令放置时 getPlayer() 可能是 null（会是 NPE）
        if (placer != null) {
            rocket.setOwner(placer);
        }
        // TODO 把物品里预装的燃料转移到实体（等物品侧的 MEK 气体能力做好）

        if (placer == null || !placer.getAbilities().instabuild) {
            context.getItemInHand().shrink(1);
        }
        server.playSound(null, pos.x, pos.y, pos.z,
                SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.5F, 0.6F);
        return InteractionResult.CONSUME;
    }

    /** 物品提示：告诉玩家占地要求，免得放了半天放不下去。 */
    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.golems_arsenal.rocket.tip")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.golems_arsenal.rocket.tip_fuel")
                .withStyle(ChatFormatting.DARK_AQUA));
    }

    /** 这个物品能不能放在这一格（给 UI 或调试用）。 */
    public static boolean canPlaceAt(Level level, BlockState state, BlockPos pos) {
        return RocketEntity.footprintClear(level, pos);
    }
}
