package a_silly_cat.golems_arsenal.tech.mekanism;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * 天基激光的**落点**：光从天上竖直灌下来,打在落点那一格的顶面。
 *
 * <p><b>为什么不需要射线。</b>这里刻意不用 {@code ClipContext}。Mekanism 的激光是
 * **水平发射**的:它从机器朝某个方向打出去,距离由配置的 {@code laserRange} 决定(默认十几格),
 * 光路上有什么完全未知,所以它**必须**射线才能知道光停在哪——{@code TileEntityBasicLaser}
 * 里那一大套 {@code ClipContext} + {@code digging}/{@code diggingProgress} 都是为"未知距离的
 * 水平射线"服务的。
 *
 * <p>本模组的光束是**竖直**的,而且落点是**发起方自己挑的**(调试命令指定目标 / 引导升级标定敌人),
 * 也就是说光该打在哪、打多高,在光束生成那一刻就完全已知了。既然如此,再反过来射线去"询问"
 * 光会打在哪,既多余又难懂。所以这里只做一件事:**取目标脚下那一格的地表**。
 *
 * <p>光要往下钻的时候也不需要射线——见 {@code OrbitalBeamEntity}:它从落点那一格开始逐格往下挖,
 * 挖穿一格就把端点下移一格。那是**状态推进**,不是**射线探测**,一眼就能看懂。
 */
public final class OrbitalImpact {

    /**
     * 目标脚下的地表:光柱的落点(该方块的**顶面中心**)。
     *
     * <p>用 {@link Heightmap.Types#MOTION_BLOCKING}——取"会挡住移动的最高方块"。
     * 草丛、火把这类无碰撞箱的方块不在其中,所以落点是它们**下面**的地面;
     * 但它们**不会因此被忽略**:光束的挖掘是从落点那一格开始的,草就在那里,会被烧掉。
     */
    public static Vec3 impactPoint(Level level, Entity target) {
        int x = target.getBlockX();
        int z = target.getBlockZ();
        int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        // atBottomCenterOf = 该格底部中心;落点要的是"光打在方块顶面上",也就是这一格自身的底面
        // (y = groundY 处正是地面方块的上表面高度)。
        return Vec3.atBottomCenterOf(new BlockPos(x, groundY, z));
    }

    private OrbitalImpact() {
    }
}
