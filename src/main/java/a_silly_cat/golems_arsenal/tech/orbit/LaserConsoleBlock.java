package a_silly_cat.golems_arsenal.tech.orbit;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 激光发射台方块（模型来自 {@code 激光.bbmodel}）。
 *
 * <p>它本身不发射任何东西，只是天基激光的**地面控制端**：右键打开配置界面，
 * 启用 + 绑定在轨平台后，傀儡的引导升级才会呼叫激光炮。详细规则见
 * {@link LaserConsoleBlockEntity}。
 *
 * <p>模型高 18.56 px（1.16 格），比一个方块略高，所以判定盒也用这个高度；
 * 它比整格窄（0.92 格），看起来像一台立在地上的设备。
 */
public class LaserConsoleBlock extends HorizontalDirectionalBlock implements EntityBlock {

    /** 启用且已绑定平台时点亮：既做视觉提示，也给光照一个同步位。 */
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    /** 判定盒：模型 0.92 × 1.16 × 0.92 格，底部对齐方块底面。 */
    private static final VoxelShape SHAPE =
            Shapes.box(1.0 / 16.0, 0.0, 1.0 / 16.0,
                    15.0 / 16.0, 18.56 / 16.0, 15.0 / 16.0);

    public LaserConsoleBlock() {
        super(Properties.of()
                .mapColor(MapColor.METAL)
                .strength(3.0F, 6.0F)
                .sound(SoundType.METAL)
                .noOcclusion()
                .lightLevel(state -> state.getValue(LIT) ? 10 : 0));
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(LIT, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LIT);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(LIT, false);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                        CollisionContext context) {
        return SHAPE;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof LaserConsoleBlockEntity console) {
            console.openScreen(serverPlayer);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide
                && level.getBlockEntity(pos) instanceof LaserConsoleBlockEntity console
                && placer instanceof Player player) {
            console.setOwner(player);
        }
    }

    @Override
    public MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof MenuProvider provider ? provider : null;
    }

    /**
     * 方块被拆掉 / 被替换时删除全局记录。
     *
     * <p><b>不能</b>放在方块实体的 {@code setRemoved()} 里：区块卸载也会走到那里，
     * 那样发射台一离开加载范围就从世界里“消失”，与“全局生效”矛盾。
     */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock()) && level instanceof net.minecraft.server.level.ServerLevel server) {
            LaserConsoleNetwork.get(server).removeAt(server.dimension().location().toString(), pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LaserConsoleBlockEntity(pos, state);
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (BlockEntityTicker<T>) (lvl, pos, st, be) -> {
            if (be instanceof LaserConsoleBlockEntity console) {
                console.serverTick();
            }
        };
    }
}
