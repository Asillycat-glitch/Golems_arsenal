package a_silly_cat.golems_arsenal.client;

import a_silly_cat.golems_arsenal.base.entity.KeyBladeEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.util.Mth;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Quaternionf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Renderer for the key-blade entity family.
 * <ul>
 *   <li>Falling sword-rain blades (iron swords) render as upright swords with the tip pointing
 *   straight down, instead of the "dropped item on the ground" pose that makes them tilt.</li>
 *   <li>Every other mode (thrown / orbiting / rising key blade) reuses the vanilla item renderer,
 *   which spins the item model while it flies.</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public class KeyBladeRenderer extends EntityRenderer<KeyBladeEntity> {
    private final ItemRenderer itemRenderer;
    private final ItemEntityRenderer itemEntityRenderer;

    public KeyBladeRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.itemRenderer = ctx.getItemRenderer();
        this.itemEntityRenderer = new ItemEntityRenderer(ctx);
        this.shadowRadius = 0.15F;
    }

    /**
     * 飞镖 / 回旋形态（键刃本体）的姿态参数 —— 都是给"边看边调"用的。
     * <p>
     * 这个形态不再套模型里的 {@code ground} 显示变换：那个是给"掉在地上的物品"调朝向用的，
     * 一改物品形式就会把飞出去的形态一起带歪。现在这里自己摆姿态，下面这四个数就是全部旋钮。
     */
    /** 让刀身平躺的滚转角（度，绕 Z）。想换飞镖形态的角度先动这个。 */
    private static final float DART_ROLL_Z = 90.0F;
    /** 再绕 X 的俯仰修正（度）：刀身低头/抬头就调它。 */
    private static final float DART_PITCH_X = 0.0F;
    /** 自转方向：+1 / -1。觉得回旋方向反了就改成 -1。 */
    private static final float DART_SPIN_SIGN = 1.0F;
    /** 离实体中心的高度（格）；0.35 约等于以前原版那套的高度。 */
    private static final float DART_LIFT_Y = 0.35F;

    @Override
    public void render(KeyBladeEntity entity, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int light) {
        ItemStack stack = entity.getItem();
        if (!stack.is(Items.IRON_SWORD)) {
            renderDart(entity, stack, partialTick, pose, buffer, light);
            return;
        }
        // Vertical fall baseline - empirically tested in game: the sword tip points straight down
        // with a Z-axis rotation of 225°. X-axis 180° overshot to down-left, X-axis 135° was still
        // off; rotating around Z acts in the visible screen plane, so 225° is the correct value.
        // On top of the baseline the blade is tilted toward the server-synced flight direction
        // (yRot/xRot), so it leans into the homing target while falling.
        pose.pushPose();
        pose.translate(0.0F, 0.35F, 0.0F);
        double yawRad = Math.toRadians(entity.getYRot());
        double pitchRad = Math.toRadians(entity.getXRot());
        Vec3 dir = new Vec3(
                -Math.sin(yawRad) * Math.cos(pitchRad),
                -Math.sin(pitchRad),
                Math.cos(yawRad) * Math.cos(pitchRad));
        Vec3 down = new Vec3(0, -1, 0);
        if (dir.y >= -0.2) {
            dir = down; // rotation not synced yet: keep the sword straight down
        }
        Vec3 axisRaw = down.cross(dir);
        double sinAngle = axisRaw.length();
        double dot = Math.max(-1.0, Math.min(1.0, down.dot(dir)));
        Quaternionf align = new Quaternionf();
        if (sinAngle > 1.0E-6) {
            Vec3 axis = axisRaw.scale(1.0 / sinAngle);
            align.rotationAxis((float) Math.atan2(sinAngle, dot),
                    (float) axis.x, (float) axis.y, (float) axis.z);
        }
        pose.mulPose(align);
        pose.mulPose(Axis.ZP.rotationDegrees(225.0F));
        BakedModel model = itemRenderer.getModel(stack, entity.level(), null, entity.getId());
        itemRenderer.render(stack, ItemDisplayContext.NONE, false, pose, buffer, light,
                OverlayTexture.NO_OVERLAY, model);
        pose.popPose();
        super.render(entity, yaw, partialTick, pose, buffer, light);
    }

    /**
     * 飞镖 / 回旋形态：绕竖轴自转 + 自己定的姿态角，不走模型里的 {@code ground} 显示变换。
     */
    private void renderDart(KeyBladeEntity entity, ItemStack stack, float partialTick, PoseStack pose,
                            MultiBufferSource buffer, int light) {
        BakedModel model = this.itemRenderer.getModel(stack, entity.level(), null, entity.getId());
        float bob = Mth.sin((entity.getAge() + partialTick) / 10.0F + entity.bobOffs) * 0.1F + 0.1F;
        pose.pushPose();
        pose.translate(0.0F, DART_LIFT_Y + bob, 0.0F);
        pose.mulPose(Axis.YP.rotation(DART_SPIN_SIGN * entity.getSpin(partialTick)));
        pose.mulPose(Axis.ZP.rotationDegrees(DART_ROLL_Z));
        pose.mulPose(Axis.XP.rotationDegrees(DART_PITCH_X));
        this.itemRenderer.render(stack, ItemDisplayContext.NONE, false, pose, buffer, light,
                OverlayTexture.NO_OVERLAY, model);
        pose.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(KeyBladeEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
