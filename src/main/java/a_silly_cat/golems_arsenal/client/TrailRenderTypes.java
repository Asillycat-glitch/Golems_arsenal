package a_silly_cat.golems_arsenal.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * 拖尾 / 能量带**通用的渲染类型**，供后续所有特效复用。
 * <p>
 * 设计依据（读 arcanevortex 的 {@code nebula_bow_trail} 得出）：它用的是
 * <b>半透明 + 三角形 + 测深度但不写深度 + 顶点色</b>，而不是加法混合。
 * 我之前用加法是判断错了 —— 加法会把颜色"烧"成白，参考模组的能量带是能看清渐变色的。
 * <p>
 * 为什么是 {@code TRIANGLES} 而不是 {@code QUADS}：锥形收尾时 quad 的两个三角形必须
 * 共享顶点，尖端收不到真正的 0，会出现"截头"；三角形可以每段独立收窄，尖端是尖的。
 * <p>
 * 各项状态的作用：
 * <ul>
 *   <li>{@code TRANSLUCENT_TRANSPARENCY} —— 常规 alpha 混合，保留颜色的明暗层次；</li>
 *   <li>{@code NO_CULL} —— 带子是单面的、且朝向相机在变，必须双面可见；</li>
 *   <li>{@code COLOR_WRITE}（不写深度）—— 多条带子交叠时不会互相遮挡；</li>
 *   <li>{@code NO_LIGHTMAP} / {@code NO_OVERLAY} —— 纯顶点色，不受环境光与受击闪光影响；</li>
 *   <li>{@code sortOnUpload = true} —— 半透明需要按距离排序才不漏画。</li>
 * </ul>
 * <p>
 * 继承 {@link RenderStateShard} 是必须的：{@code TRANSLUCENT_TRANSPARENCY} /
 * {@code NO_CULL} / {@code NO_LIGHTMAP} / {@code NO_OVERLAY} / {@code COLOR_WRITE}
 * 都是父类的 {@code protected static} 成员，从外部直接引用编译不过。
 */
public final class TrailRenderTypes extends RenderStateShard {

    /** 半透明三角形带：顶点色，通用拖尾。 */
    public static final RenderType TRANSLUCENT_TRIANGLES = RenderType.create(
            "golems_arsenal_trail_translucent",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.TRIANGLES,
            1536,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(new ShaderStateShard(GameRenderer::getPositionColorShader))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setLightmapState(NO_LIGHTMAP)
                    .setOverlayState(NO_OVERLAY)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false));

    /**
     * <b>加法混合</b>三角形：给"能量体/体积光"用。
     * <p>
     * 为什么需要它：半透明混合（{@link #TRANSLUCENT_TRIANGLES}）的物理含义是"透过一块有色玻璃看"，
     * 所以实心体叠出来像磨砂塑料。加法混合是"往画面里加光"，多面叠加会自然变亮、
     * 边缘越叠越白 —— 这才是能量体的观感。
     * <p>
     * 代价：加法会把颜色"烧"向白色，所以纯色加法会丢失色彩层次；
     * 想要颜色就压低单层 alpha、靠叠加出高光。
     */
    public static final RenderType ADDITIVE_TRIANGLES = RenderType.create(
            "golems_arsenal_aura_additive",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.TRIANGLES,
            1536,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(new ShaderStateShard(GameRenderer::getPositionColorShader))
                    .setTransparencyState(ADDITIVE_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setLightmapState(NO_LIGHTMAP)
                    .setOverlayState(NO_OVERLAY)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false));

    /** 占位构造函数；本类只作为常量容器，不会被实例化。 */
    private TrailRenderTypes() {
        super("golems_arsenal_trail_types", () -> {
        }, () -> {
        });
    }
}
