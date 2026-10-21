package a_silly_cat.golems_arsenal.client;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.base.entity.RocketEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * 火箭渲染器：用 Blockbench 导出的 {@link RocketModel} 绘制。
 *
 * <p><b>垂直落位是怎么解决的</b>（这条坑我踩了四轮，记下来免得重犯）：
 * 火箭要"站在地上"，就必须知道模型自己认为脚在哪。而 {@code ModelPart} 把
 * cube 列表设成 private、也没有任何包围盒访问器，所以从外面问不出来。
 *
 * <p>正解是 {@code ModelPart.visit(PoseStack.Pose, ModelPart.Visitor)}：它会把每一个
 * {@code ModelPart.Cube} 连同<b>累积好的姿态</b>交出来，而 {@code Cube} 的六个 float 字段
 * 正是它在局部空间的最小/最大坐标（minX..maxZ）。于是可以：
 * <ol>
 *   <li>遍历所有 cube，把 8 个角点用该 cube 的 Pose 矩阵变换到根空间</li>
 *   <li>取 Y 的极值 → 得到模型在<b>烘焙空间</b>里的真实上下端</li>
 *   <li>这两个值之后套用与绘制完全相同的变换，就能解出"脚要落在地面"所需的平移量</li>
 * </ol>
 *
 * <p>之前我三次手工推导这套坐标（Blockbench 的 +Y 向上、Minecraft 实体模型的 +Y 向下、
 * 中间还夹一个 Z 轴 180 度翻转），每次结论都不同，其中一次算出"鼻锥尖在 +216 格"。
 * 测出来的值不会有这个问题。
 */
@OnlyIn(Dist.CLIENT)
public class RocketRenderer extends EntityRenderer<RocketEntity> {

    /** 实体贴图：512×512，从 {@code 火箭实体模型.bbmodel} 里提取。 */
    private static final ResourceLocation TEXTURE =
            new ResourceLocation("golems_arsenal", "textures/entity/rocket.png");

    /** 最后一次渲染用的垂直平移量（格），用于诊断输出。 */
    private float lastOffset;

    private final RocketModel<RocketEntity> model;
    /** 模型在烘焙空间里的 Y 范围（像素）。首次渲染时测出来。 */
    private static float[] measuredYRange;

    /**
     * 渲染器还没跑过时的兜底锚点（格）。
     *
     * <p>实测值是 {@code 45/16 = 2.8125}；服务端可能在渲染器初始化之前就要算喷口位置
     * （例如火箭一放下就开始冒烟），所以给一个常量兜底，避免粒子位置跳一下。
     */
    private static final float FALLBACK_ANCHOR = 45.0F / 16.0F;

    /**
     * 模型落位的**实测校准量**（格）。
     *
     * <p>推导出来的锚点（{@code measuredHi / 16 = 2.8125}）在纸面上是对的，但实际渲染下
     * 对不上——用户开着 F3+B 逐次实测，最终确定用这个值。按要求**写进代码**，
     * 不再作为配置项暴露（配置项之后会做一次统合简化）。
     *
     * <p><b>符号</b>：这个 translate 发生在"绕 Z 翻 180°"之后，所以正值=往上抬、负值=往下沉。
     * 当前值 {@code -5.0} 是用户实测选定的（我先写了 +5，实测下来不对）。
     */
    private static final float MODEL_ANCHOR_CALIBRATION = -5.0F;

    /**
     * 模型渲染时相对实体原点的垂直平移量（格）。
     *
     * <p><b>这是唯一来源</b>：渲染器用它落位，{@code RocketEntity} 用它算喷口的世界坐标。
     * 两边必须是同一个数，否则粒子会和喷口错开——这个坑踩过：模型和判定盒差 5 格那次，
     * 就是因为落位和喷口各算各的。
     */
    public static float verticalOffset() {
        float anchor = (measuredYRange == null) ? FALLBACK_ANCHOR : measuredYRange[1] / 16.0F;
        return anchor + MODEL_ANCHOR_CALIBRATION;
    }

    public RocketRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0F;   // 火箭不适合有影子
        this.model = new RocketModel<RocketEntity>(ctx.bakeLayer(RocketModel.LAYER_LOCATION));
    }

    /**
     * 测出模型在烘焙空间里的 Y 范围（像素）。
     *
     * <p>把每个 cube 的 8 个角点，用它自己的 Pose 矩阵变换到根空间，再取 Y 极值。
     * 只看 Y——水平范围对本渲染器没用，而 {@link com.mojang.math.Axis#YP} 旋转不影响 Y。
     */
    private float[] measureYRange() {
        final float[] lo = {Float.MAX_VALUE};
        final float[] hi = {-Float.MAX_VALUE};
        this.model.root().visit(new PoseStack(), (pose, name, index, cube) -> {
            Matrix4f m = pose.pose();
            for (int i = 0; i < 8; i++) {
                float x = (i & 1) == 0 ? cube.minX : cube.maxX;
                float y = (i & 2) == 0 ? cube.minY : cube.maxY;
                float z = (i & 4) == 0 ? cube.minZ : cube.maxZ;
                Vector4f v = new Vector4f(x, y, z, 1.0F);
                m.transform(v);
                lo[0] = Math.min(lo[0], v.y());
                hi[0] = Math.max(hi[0], v.y());
            }
        });
        return new float[]{lo[0], hi[0]};
    }

    @Override
    public void render(RocketEntity entity, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int light) {
        if (measuredYRange == null) {
            measuredYRange = measureYRange();
            float lo = measuredYRange[0];
            float hi = measuredYRange[1];
            Golems_arsenal.LOGGER.info(
                    "Rocket model measured: Y {} .. {} px (height {} px = {} blocks). "
                            + "Vertical offset in use: {} blocks (anchor {} + calibration {}).",
                    lo, hi, hi - lo, (hi - lo) / 16.0F,
                    verticalOffset(), hi / 16.0F, MODEL_ANCHOR_CALIBRATION);
        }

        pose.pushPose();
        // **绕 Z 翻 180 度**：Blockbench 的 +Y 是"向上"，Minecraft 实体模型的 +Y 是"向下"。
        // 导出代码按前者写的，不翻的话火箭是倒的（鼻锥朝下）。绕 Z 而非绕 X，因为绕 Z
        // 只翻转 Y、不会顺带镜像 Z 轴。
        pose.mulPose(Axis.ZP.rotationDegrees(180.0F));
        // 垂直落位：见 verticalOffset()。锚点由实测包围盒算出，再加一个实测校准量。
        this.lastOffset = verticalOffset();
        pose.translate(0.0F, this.lastOffset, 0.0F);
        // 朝向：绕 Y 转 180 - 偏航
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - entityYaw));

        VertexConsumer vc = buffer.getBuffer(RenderType.entitySolid(TEXTURE));
        // 全亮但不要纯白：光影包 bloom 下压一点亮度，避免火箭变成白色光团。
        float shade = 0.55F + 0.45F * ShaderEffects.brightness();
        this.model.renderToBuffer(pose, vc, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                shade, shade, shade, 1.0F);

        pose.popPose();
        super.render(entity, entityYaw, partialTick, pose, buffer, light);
    }

    @Override
    public ResourceLocation getTextureLocation(RocketEntity entity) {
        return TEXTURE;
    }
}
