package a_silly_cat.golems_arsenal.client;

import a_silly_cat.golems_arsenal.base.entity.PhantomBladeEntity;
import a_silly_cat.golems_arsenal.init.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 剑气渲染 —— 走**物品渲染管线**（和键刃一样）：模型是 {@code models/item/genmu_zero_blade.json}
 * （即 Blockbench 导出的那个），载体是隐藏物品 {@link ModItems#GENMU_ZERO_BLADE}，
 * 用 {@link ItemRenderer#render} 直接画出来。
 * <p>
 * 可调参数：
 * <ul>
 *   <li>大小：{@code PhantomBladeEntity.BASE_MODEL_SCALE}（基础倍率）×
 *       {@code PhantomBladeEntity.sizeFactor()}（体型加成，1.0~2.5 倍）</li>
 *   <li>朝向：服务端在 {@code PhantomBladeEntity.fire()} 按飞行方向 {@code setYRot()}，
 *       模型自己"脸朝哪边"用 {@link #MODEL_YAW_OFFSET} 修正</li>
 *   <li>阵型：{@code PhantomBladeEntity.Formation} 的三角阵 / 平铺，参数也在那个类顶部</li>
 *   <li>纵向居中：{@link #VERTICAL_CENTER_FIX}</li>
 *   <li>离身体的距离：{@code PhantomBladeEntity.fire()} 里 {@code from} 的 0.9 格偏移</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public class GenmuZeroRenderer extends EntityRenderer<PhantomBladeEntity> {

    /**
     * 纵向居中修正（模型空间，单位=格，<b>与缩放无关</b>）。
     * <p>
     * {@link ItemRenderer#render} 在真正画之前会无条件 {@code translate(-0.5, -0.5, -0.5)}，
     * 所以模型坐标 {@code v}（这个模型高 20/16 = 1.25 格）实际落在 {@code scale × (v - 0.5)}。
     * 要让剑气以实体位置为中心，这里补 {@code 0.5 - 模型高度的一半 = 0.5 - 0.625 = -0.125}。
     * <p>
     * 表现太靠下就往正数调，太靠上就往负数调。
     */
    private static final float VERTICAL_CENTER_FIX = -0.125F;

    /**
     * 模型朝向修正（度，绕竖轴）。
     * <p>
     * 默认约定：模型的 <b>+Z</b> 对齐飞行方向（+Y 是上、+X 是左右）。
     * <ul>
     *   <li>{@code 0} —— 按默认约定，模型正面朝飞行方向；</li>
     *   <li>{@code 180} —— 前后翻转（看着"反了"就改这个）；</li>
     *   <li>{@code 90 / 270} —— 侧转 90°，把模型"薄的那一边"朝前。</li>
     * </ul>
     * 想连续微调就直接写角度，比如 {@code 200}。
     */
    private static final float MODEL_YAW_OFFSET = 180.0F;

    public GenmuZeroRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0F;
    }

    @Override
    public void render(PhantomBladeEntity entity, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int light) {
        ItemStack stack = new ItemStack(ModItems.GENMU_ZERO_BLADE.get());
        ItemRenderer itemRenderer = Minecraft.getInstance().getItemRenderer();
        BakedModel model = itemRenderer.getModel(stack, entity.level(), null, entity.getId());
        float scale = Math.max(0.01F, entity.bladeWidth());
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-(entity.getYRot() + MODEL_YAW_OFFSET)));
        pose.translate(0.0F, VERTICAL_CENTER_FIX * scale, 0.0F);
        pose.scale(scale, scale, scale);
        itemRenderer.render(stack, ItemDisplayContext.NONE, false, pose, buffer, light,
                OverlayTexture.NO_OVERLAY, model);
        pose.popPose();
        super.render(entity, entityYaw, partialTick, pose, buffer, light);
    }

    @Override
    public ResourceLocation getTextureLocation(PhantomBladeEntity entity) {
        return PhantomBladeEntity.TEXTURE;
    }
}
