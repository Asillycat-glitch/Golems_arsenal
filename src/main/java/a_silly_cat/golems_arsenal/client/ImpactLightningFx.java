package a_silly_cat.golems_arsenal.client;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.base.entity.OrbitalImpactEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/**
 * 落点的**金色闪电**（纯视觉，不造成伤害）。
 *
 * <p>参考 `mgdp` 的「五雷天罚」：它的打击感不是来自一个白球，而是
 * **瞬间从天上劈下来的视觉闪电** + 爆炸 + 火花。这里用客户端几何画 6 道
 * 带分叉感的金色闪电，持续约 12 tick，配合落点的火花和短促震屏。
 *
 * <p>闪电是加法混合的短线段，不写深度、不参与碰撞；颜色保持我们的暖金，
 * 所以引导光柱不需要改色。
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID, value = Dist.CLIENT)
public final class ImpactLightningFx {

    private static final int DURATION = 12;
    private static final int BOLTS = 6;
    private static final int SEGMENTS = 14;
    private static final float TOP_HEIGHT = 52.0F;
    private static final float WIDTH = 0.11F;

    private static final Map<Integer, Bolt> BOLTS_BY_IMPACT = new HashMap<>();

    private ImpactLightningFx() {
    }

    private record Bolt(Vec3 center, long start, long seed, float radius) {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            BOLTS_BY_IMPACT.clear();
            return;
        }
        long now = mc.level.getGameTime();
        BOLTS_BY_IMPACT.entrySet().removeIf(entry -> now - entry.getValue().start >= DURATION);
        if (mc.player == null) {
            return;
        }
        for (OrbitalImpactEntity impact : mc.level.getEntitiesOfClass(
                OrbitalImpactEntity.class, mc.player.getBoundingBox().inflate(160.0D))) {
            if (!impact.isAlive()) {
                continue;
            }
            BOLTS_BY_IMPACT.putIfAbsent(impact.getId(), new Bolt(
                    impact.position(), now, impact.getId() * 0x9E3779B97F4A7C15L,
                    Math.max(2.0F, impact.radius())));
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES
                || BOLTS_BY_IMPACT.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        long now = mc.level.getGameTime();
        float partial = event.getPartialTick();
        Vec3 camera = event.getCamera().getPosition();
        Matrix4f matrix = event.getPoseStack().last().pose();

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(TrailRenderTypes.ADDITIVE_TRIANGLES);
        boolean any = false;

        for (Bolt bolt : BOLTS_BY_IMPACT.values()) {
            float p = (now - bolt.start + partial) / (float) DURATION;
            if (p <= 0.0F || p >= 1.0F) {
                continue;
            }
            float envelope = (float) Math.pow(Math.sin(Math.PI * p), 0.40D);
            if (envelope <= 0.01F) {
                continue;
            }
            float flicker = 0.65F + 0.35F * (float) Math.sin(now * 1.7D + bolt.seed * 0.001D);
            float alpha = envelope * flicker;

            for (int i = 0; i < BOLTS; i++) {
                drawBolt(vc, matrix, camera, bolt, i, now, alpha);
                any = true;
            }
        }
        if (any) {
            buffers.endBatch();
        }
    }

    private static void drawBolt(VertexConsumer vc, Matrix4f matrix, Vec3 camera,
                                 Bolt bolt, int index, long now, float alpha) {
        long seed = bolt.seed + index * 0x9E3779B97F4A7C15L;
        double angle = hash(seed) * Math.PI * 2.0D;
        double distance = bolt.radius * (0.15D + 0.75D * hash(seed ^ 0x51ED270B));
        double offsetX = Math.cos(angle) * distance;
        double offsetZ = Math.sin(angle) * distance;

        Vec3 previous = null;
        for (int s = 0; s <= SEGMENTS; s++) {
            float t = s / (float) SEGMENTS;
            float y = (float) (bolt.center.y + TOP_HEIGHT * (1.0F - t));
            double converge = 1.0D - t;
            double sway = Math.sin(t * 9.0D + hash(seed ^ 0x2545F491) * 8.0D + now * 0.45D)
                    * 1.8D * converge;
            double x = bolt.center.x + offsetX * converge + sway;
            double z = bolt.center.z + offsetZ * converge
                    + Math.cos(t * 11.0D + hash(seed ^ 0x6C8E9CF5) * 8.0D + now * 0.31D) * 1.4D * converge;
            Vec3 current = new Vec3(x, y, z);
            if (previous != null) {
                streak(vc, matrix, camera, previous, current, WIDTH, alpha, index);
            }
            previous = current;
        }
    }

    private static void streak(VertexConsumer vc, Matrix4f matrix, Vec3 camera,
                               Vec3 from, Vec3 to, float width, float alpha, int index) {
        Vec3 segment = to.subtract(from);
        if (segment.lengthSqr() < 1.0E-7D) {
            return;
        }
        Vec3 view = camera.subtract(from);
        if (view.lengthSqr() < 1.0E-7D) {
            return;
        }
        Vec3 side = segment.cross(view).normalize().scale(width);
        if (side.lengthSqr() < 1.0E-9D) {
            return;
        }
        Vec3 a = from.subtract(side);
        Vec3 b = from.add(side);
        Vec3 c = to.add(side);
        Vec3 d = to.subtract(side);

        // 金白芯：奇数道略白，偶数道更金，叠在一起有层次
        float r = 1.00F;
        float g = index % 2 == 0 ? 0.86F : 0.95F;
        float bl = index % 2 == 0 ? 0.42F : 0.72F;
        float cr = r * alpha;
        float cg = g * alpha;
        float cb = bl * alpha;

        vertex(vc, matrix, camera, a, cr, cg, cb);
        vertex(vc, matrix, camera, b, cr, cg, cb);
        vertex(vc, matrix, camera, c, cr, cg, cb);
        vertex(vc, matrix, camera, a, cr, cg, cb);
        vertex(vc, matrix, camera, c, cr, cg, cb);
        vertex(vc, matrix, camera, d, cr, cg, cb);
    }

    private static void vertex(VertexConsumer vc, Matrix4f matrix, Vec3 camera, Vec3 p,
                               float r, float g, float b) {
        vc.vertex(matrix,
                        (float) (p.x - camera.x),
                        (float) (p.y - camera.y),
                        (float) (p.z - camera.z))
                .color(r, g, b, 1.0F)
                .endVertex();
    }

    private static double hash(long value) {
        value ^= value >>> 33;
        value *= 0xFF51AFD7ED558CCDL;
        value ^= value >>> 33;
        value *= 0xC4CEB9FE1A85EC53L;
        value ^= value >>> 33;
        return (value >>> 11) / (double) (1L << 53);
    }
}
