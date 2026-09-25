package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.base.event.KeySwordEventHandler;
import a_silly_cat.golems_arsenal.base.event.GolemPhoenixHandler;
import a_silly_cat.golems_arsenal.client.ClientFlipData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemRenderer;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders the lion-slash front flip: while a golem's flip timer is active, the whole rendered
 * model performs one full 360° forward somersault around its local X axis. All golem renderers
 * (metal / humanoid / dog) inherit {@code setupRotations} from {@link AbstractGolemRenderer},
 * so a single injection covers every type; Metal's own wobble is applied after ours.
 */
@OnlyIn(Dist.CLIENT)
@Mixin(AbstractGolemRenderer.class)
public abstract class AbstractGolemRendererFlipMixin {

    @Inject(method = "setupRotations", at = @At("TAIL"))
    private void golemsArsenal$lionSlashFlip(AbstractGolemEntity<?, ?> golem, PoseStack stack,
                                             float ageInTicks, float yRot, float partialTick,
                                             CallbackInfo ci) {
        ClientFlipData.FlipState state = ClientFlipData.get(golem.getId());
        if (state == null || Minecraft.getInstance().level == null) {
            return;
        }
        if (Double.isNaN(state.baseY)) {
            state.baseY = golem.getY();
        }
        int air = KeySwordEventHandler.lionSlashAirTicks(golem);
        float elapsed = Minecraft.getInstance().level.getGameTime() + partialTick - state.startTick;
        if (state.phoenix) {
            // Phoenix rush: one backflip during the rise (ending at the configured angle so the pose
            // already reads as "facing the target"), held through hover + dive, unwound while landing.
            int rise = GolemPhoenixHandler.RISE_TICKS;
            int hang = GolemPhoenixHandler.HANG_TICKS;
            int dive = GolemPhoenixHandler.DIVE_TICKS;
            int land = GolemPhoenixHandler.LAND_TICKS;
            float deg;
            if (elapsed <= rise) {
                double t = Math.max(0.0, Math.min(1.0, elapsed / (double) rise));
                deg = state.maxAngleDeg * (float) (1.0 - (1.0 - t) * (1.0 - t));
            } else if (elapsed <= rise + hang + dive) {
                deg = state.maxAngleDeg;
            } else if (elapsed <= rise + hang + dive + land) {
                double t = Math.max(0.0, Math.min(1.0,
                        (elapsed - rise - hang - dive) / (double) land));
                deg = state.maxAngleDeg * (float) (1.0 - t);
            } else {
                ClientFlipData.remove(golem.getId());
                return;
            }
            if (deg <= 0.001F) {
                return;
            }
            float pivot = golem.getBbHeight() * 0.5F;
            stack.translate(0.0F, pivot, 0.0F);
            // Positive X is the backflip (see the lion-slash comment below); flip the sign to taste.
            stack.mulPose(Axis.XP.rotationDegrees(deg));
            stack.translate(0.0F, -pivot, 0.0F);
            return;
        }
        if (elapsed <= 0 || elapsed > air + 10) {
            return;
        }
        // Reconstruct the flip phase from the rendered Y with a linear mapping: height 0 -> apex
        // maps to angle 0° -> 180° while rising, and apex -> 0 maps to 180° -> 360° while
        // falling. The visible somersault therefore follows the visible height exactly (immune to
        // position interpolation lag) without the asin singularity near the apex that made the
        // top of the flip stall or skip.
        double rel = golem.getY() - state.baseY;
        double apex = KeySwordEventHandler.LION_SLASH_HEIGHT_FACTOR
                * Math.max(1.0, golem.getBbHeight());
        double sy = Math.max(0.0, Math.min(1.0, rel / Math.max(0.01, apex)));
        state.maxY = Math.max(state.maxY, sy);
        if (rel <= 0.05 && elapsed > air * 0.5) {
            ClientFlipData.remove(golem.getId());
            return;
        }
        boolean rising = sy >= state.maxY - 0.01;
        double phase = rising ? sy * 0.5 : 1.0 - sy * 0.5;
        if (phase <= 0.001) {
            return;
        }
        if (phase >= 0.999) {
            ClientFlipData.remove(golem.getId());
            return;
        }
        // Rotate around the golem's body centre (half its rendered height) instead of the feet,
        // so the somersault reads as a body flip rather than rolling along the ground. Negative X
        // tips the head toward the facing first (positive X was a back flip).
        float pivot = golem.getBbHeight() * 0.5F;
        stack.translate(0.0F, pivot, 0.0F);
        stack.mulPose(Axis.XP.rotationDegrees(-360.0F * (float) phase));
        stack.translate(0.0F, -pivot, 0.0F);
    }
}
