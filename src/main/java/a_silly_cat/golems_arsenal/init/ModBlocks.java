package a_silly_cat.golems_arsenal.init;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.tech.orbit.LaserConsoleBlock;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 本模组的方块。目前只有 MEK 联动的地面控制端：激光发射台。 */
public final class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, Golems_arsenal.MODID);

    /**
     * 激光发射台。模型来自 {@code 激光.bbmodel}，方块实体与界面见
     * {@code tech.orbit.LaserConsoleBlockEntity}。
     */
    public static final RegistryObject<LaserConsoleBlock> LASER_CONSOLE =
            BLOCKS.register("laser_console", LaserConsoleBlock::new);

    private ModBlocks() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }
}
