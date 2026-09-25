package a_silly_cat.golems_arsenal.client;

import a_silly_cat.golems_arsenal.base.entity.LightPillarEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/**
 * 光柱渲染 —— 用**原版信标光束的贴图**画一根四棱柱，内外两层 + UV 向上滚动，
 * 视觉就是信标那根光柱（本家信标靴也是这个观感）。
 * <p>
 * 光柱实体本身的位置是钉死的（伤害判定跟着那个位置走），**绕着傀儡转**是这里算出来的：
 * 用实体的 yRot（出生角度）反推出傀儡中心，再按 {@code tickCount} 平滑地转到当前角度。
 * 普通实体每 tick 的位置同步是硬跳，服务端挪它会一顿一顿的。
 * <p>
 * 可调参数：
 * <ul>
 *   <li>{@link #ORBIT_DEGREES_PER_TICK} —— 整根光柱绕傀儡公转的速度（改 0 就不转）</li>
 *   <li>{@link #SPIN_DEGREES_PER_TICK} —— 光柱自己的自转（四棱柱转面）</li>
 *   <li>{@link #INNER_SCALE} / {@link #UV_SCROLL_SPEED} —— 内芯粗细、贴图向上滚动速度</li>
 *   <li>宽 / 高 / 公转半径 —— {@link LightPillarEntity} 里的 {@code PILLAR_WIDTH}、{@code PILLAR_HEIGHT}
 *       和生成时的 {@code ringRadius}</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public class BeaconPillarRenderer extends EntityRenderer<LightPillarEntity> {

    /** 原版信标光束贴图。 */
    private static final ResourceLocation BEAM =
            new ResourceLocation("minecraft", "textures/entity/beacon_beam.png");
    /** 内芯相对外圈的比例（信标也是内外两层）。 */
    private static final float INNER_SCALE = 0.4F;
    /** 贴图向上滚动的速度（格/tick）。 */
    private static final float UV_SCROLL_SPEED = 0.2F;
    /** 整根光柱绕着傀儡公转的速度（度/tick）；0 = 不公转。 */
    private static final float ORBIT_DEGREES_PER_TICK = 4.0F;
    /** 光柱自身绕竖轴自转的速度（度/tick）。 */
    private static final float SPIN_DEGREES_PER_TICK = 7.5F;
    /** 信标光束是全亮的（原版也是写死 15728880），不受环境光影响。 */
    private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;

    public BeaconPillarRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0F;
    }

    @Override
    public void render(LightPillarEntity entity, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int light) {
        float height = entity.pillarHeight();
        float radius = entity.pillarWidth() * 0.5F;
        float ticks = entity.tickCount + partialTick;
        float scroll = ticks * UV_SCROLL_SPEED;
        double orbit = entity.orbitRadius();
        double nowRad = Math.toRadians(entity.getYRot() + ORBIT_DEGREES_PER_TICK * ticks);

        // 目标位置 = 傀儡的渲染位置（傀儡自己就在平滑插值）+ 当前公转角度那一圈
        double targetX = entity.getX();
        double targetY = entity.getY();
        double targetZ = entity.getZ();
        Entity owner = entity.level() instanceof ClientLevel clientLevel
                ? clientLevel.getEntity(entity.ownerEntityId()) : null;
        if (owner != null) {
            Vec3 p = owner.getPosition(partialTick);
            targetX = p.x + Math.cos(nowRad) * orbit;
            targetY = p.y;
            targetZ = p.z + Math.sin(nowRad) * orbit;
        }
        // pose 里已经平移到"实体自己那个会硬跳的位置"了（普通实体没有插值），
        // 所以减掉那个基准、直接补到目标位置，跟随时才不会 20Hz 一顿一顿。
        double baseX = Mth.lerp(partialTick, entity.xOld, entity.getX());
        double baseY = Mth.lerp(partialTick, entity.yOld, entity.getY());
        double baseZ = Mth.lerp(partialTick, entity.zOld, entity.getZ());
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucentEmissive(BEAM));
        pose.pushPose();
        pose.translate(targetX - baseX, targetY - baseY, targetZ - baseZ);
        pose.mulPose(Axis.YP.rotationDegrees(-SPIN_DEGREES_PER_TICK * ticks));
        tube(pose, vc, radius, height, 0.0F, scroll, 1.0F, FULL_BRIGHT);
        tube(pose, vc, radius * INNER_SCALE, height, 0.0F, scroll * 1.35F, 1.0F, FULL_BRIGHT);
        pose.popPose();
        super.render(entity, entityYaw, partialTick, pose, buffer, light);
    }

    /** 画一圈四棱柱（每个面正反各一遍，保证任何角度都看得到）。 */
    private static void tube(PoseStack pose, VertexConsumer vc, float radius, float height,
                             float inset, float vOffset, float alpha, int light) {
        Matrix4f matrix = pose.last().pose();
        float v0 = inset + vOffset;
        float v1 = v0 + height * 0.5F;
        int color = (int) (Math.max(0.0F, Math.min(1.0F, alpha)) * 255.0F);
        for (int i = 0; i < 4; i++) {
            float a0 = (float) (Math.PI / 4.0 + i * Math.PI / 2.0);
            float a1 = a0 + (float) (Math.PI / 2.0);
            float x0 = (float) Math.cos(a0) * radius;
            float z0 = (float) Math.sin(a0) * radius;
            float x1 = (float) Math.cos(a1) * radius;
            float z1 = (float) Math.sin(a1) * radius;
            face(vc, matrix, x0, z0, x1, z1, height, v0, v1, color, light);
            face(vc, matrix, x1, z1, x0, z0, height, v0, v1, color, light);
        }
    }

    private static void face(VertexConsumer vc, Matrix4f m, float x0, float z0, float x1, float z1,
                             float height, float v0, float v1, int color, int light) {
        vc.vertex(m, x0, 0.0F, z0).color(255, 255, 255, color).uv(0.0F, v0)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0.0F, 0.0F, 1.0F).endVertex();
        vc.vertex(m, x1, 0.0F, z1).color(255, 255, 255, color).uv(1.0F, v0)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0.0F, 0.0F, 1.0F).endVertex();
        vc.vertex(m, x1, height, z1).color(255, 255, 255, color).uv(1.0F, v1)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0.0F, 0.0F, 1.0F).endVertex();
        vc.vertex(m, x0, height, z0).color(255, 255, 255, color).uv(0.0F, v1)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0.0F, 0.0F, 1.0F).endVertex();
    }

    @Override
    public ResourceLocation getTextureLocation(LightPillarEntity entity) {
        return BEAM;
    }
}
