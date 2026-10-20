package a_silly_cat.golems_arsenal.client;

import a_silly_cat.golems_arsenal.Golems_arsenal;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.xkmc.modulargolems.content.entity.metalgolem.MetalGolemEntity;
import dev.xkmc.modulargolems.content.entity.metalgolem.MetalGolemModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 三连击的刀光层：<b>把傀儡手上那件武器自己的模型，沿它扫过的轨迹重复画几份，并且越旧越透明</b>。
 * <p>
 * <b>为什么这么做</b>：模型和贴图交给原版 {@code ItemRenderer} 自己解析，所以"手上是什么武器就画什么" ——
 * 数据包让大型傀儡用再多武器都不用逐把建模、不用碰贴图。
 * <p>
 * <b>为什么挂在渲染层上就等于"跟随傀儡"</b>：这一层活在傀儡的局部坐标系里，傀儡的位置、朝向、
 * 以及服务端→客户端的插值位移，渲染器每帧都已经应用好了；支点用
 * {@code MetalGolemModel.transformToHand(MAINHAND)} 拿，正好落在握把上。所以不会出现
 * "傀儡跑开了、刀光留在原地"的世界锚定感。
 * <p>
 * <b>拖影怎么实现</b>（参考灾变 {@code AfterImageParticle} 的半透明重绘思路）：不透明复制只会糊成一坨，
 * 所以"当前这一份"照常实心渲染，后面的每一份都<b>换一个半透明渲染类型、并把顶点 alpha 按份数压下去</b>。
 * 做法是把 {@code MultiBufferSource}/{@code VertexConsumer} 各包一层：
 * <ul>
 *   <li>渲染类型从物品自己的（cutout/solid，忽略 alpha）换成 {@code entityTranslucentCull(方块图集)} ——
 *       物品 quad 本来就在方块图集上，所以换过去贴图依然正确；</li>
 *   <li>顶点 alpha 逐份递减，于是副本自然淡出成拖影。</li>
 * </ul>
 * 比 {@code RenderSystem.setShaderColor} 干净：实体渲染的 buffer 是<b>延迟批处理</b>的，
 * 改全局 shader 颜色会串到整批（灾变是在粒子阶段自己造 buffer 并立刻 flush 才避开的）；
 * 压顶点 alpha 不碰全局状态，也不打乱绘制顺序。
 * <p>
 * 参数全在 {@link GolemComboStages} 里，这个类只管画。
 */
public class GolemComboLayer extends RenderLayer<MetalGolemEntity, MetalGolemModel> {

    /**
     * 用哪个 {@link ItemDisplayContext} 渲染：它决定模型<b>自带</b>的 display 变换
     * （由 {@code ForgeHooksClient.handleCameraTransforms} 在 {@code ItemRenderer.render} 内部套用）。
     * <p>
     * 实际取值在 {@link GolemComboStages#RENDER_CONTEXT}，那里写清了"NONE（旧行为）"与
     * "THIRD_PERSON_RIGHT_HAND（和本家手里那把走同一条路）"的取舍。
     */
    private static final ItemDisplayContext CONTEXT = GolemComboStages.RENDER_CONTEXT;

    /** 副本数上限：数据包武器的模型复杂度我们控制不了，兜一个顶。 */
    private static final int MAX_COPIES = 8;

    /** 拖影用的渲染类型：半透明 + 背面剔除（和物品自己的 cutout 行为一致，只是允许 alpha）。 */
    private static final RenderType TRAIL_TYPE = RenderType.entityTranslucentCull(InventoryMenu.BLOCK_ATLAS);

    public GolemComboLayer(RenderLayerParent<MetalGolemEntity, MetalGolemModel> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffer, int packedLight, MetalGolemEntity golem,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        long now = mc.level.getGameTime();
        // 同一只傀儡可以同时演多段：服务端按"起手后每 LINK_TICKS 开一段"排，
        // 所以相邻段会重叠（场上同时出现两三把刀是预期效果）。
        List<ClientComboData.ComboState> states = ClientComboData.active(golem.getId(), now);
        if (states.isEmpty()) {
            return;
        }

        ItemStack stack = golem.getMainHandItem();
        if (stack.isEmpty()) {
            return; // 空手没有可挥的模型
        }

        BakedModel model = mc.getItemRenderer().getModel(stack, golem.level(), golem, 0);
        MetalGolemModel parentModel = this.getParentModel();
        int light = GolemComboStages.FULL_BRIGHT ? 0xF000F0 : packedLight;

        // 只用来取"手部位置"的草稿栈，省得每份副本都 new 一个
        PoseStack scratch = new PoseStack();

        if (GolemComboStages.DEBUG_AXES) {
            pose.pushPose();
            translateToHandOnly(parentModel, pose, scratch);
            renderDebugAxes(pose, buffer, 1.5F);
            pose.popPose();
        }

        for (ClientComboData.ComboState state : states) {
            GolemComboStages.Stage stage = GolemComboStages.stage(state.stageIndex);
            if (stage == null) {
                continue;
            }
            double elapsed = now + partialTick - state.startTick;
            if (elapsed < 0.0 || elapsed >= stage.ticks()) {
                continue;
            }
            renderStage(pose, buffer, light, stage, elapsed / stage.ticks(), stack, model,
                    parentModel, scratch, weaponFix(stack, model));
        }
    }

    /** 把一段动作的若干副本画出来（一份 = 这把刀在那一段的某一个时刻的姿态）。 */
    private void renderStage(PoseStack pose, MultiBufferSource buffer, int light,
                             GolemComboStages.Stage stage, double progress, ItemStack stack,
                             BakedModel model, MetalGolemModel parentModel, PoseStack scratch,
                             GolemComboStages.WeaponFix fix) {
        Minecraft mc = Minecraft.getInstance();
        int copies = Mth.clamp(stage.copies(), 1, MAX_COPIES);

        GolemComboStages.Keyframe from = stage.from();
        GolemComboStages.Keyframe to = stage.to();
        GolemComboStages.Pose base = stage.base();

        for (int i = 0; i < copies; i++) {
            boolean live = i == 0;
            if (live && !GolemComboStages.DRAW_LIVE_COPY) {
                continue;
            }
            // 第 i 份副本 = 这把刀"之前"走到过的位置，所以副本天然沿扫过的轨迹铺开成扇面
            double copyProgress = progress - i * (double) stage.lagPerCopy();
            if (copyProgress < 0.0) {
                if (GolemComboStages.TRAIL_SKIP_UNSWEPT) {
                    continue; // 这一份还没被"扫"出来
                }
                copyProgress = 0.0; // 夹在起手姿态上：整扇刀光跟着瞬移到起手位置
            }
            copyProgress = Math.min(copyProgress, 1.0);
            float t = (float) stage.ease().at(copyProgress);

            float yaw = Mth.lerp(t, from.y(), to.y());
            float roll = Mth.lerp(t, from.z(), to.z());
            float pitch = Mth.lerp(t, from.x(), to.x());
            float scale = Mth.lerp(t, from.scale(), to.scale())
                    * GolemComboStages.SCALE_MULTIPLIER;
            float reach = Mth.lerp(t, from.reach(), to.reach());

            pose.pushPose();
            // 支点：只取手部的"位置"（不继承手部旋转），这样所有角度都绕傀儡本体的固定轴
            Vec3 hand = translateToHandOnly(parentModel, pose, scratch);
            // 整段统一向外挪：方向 = 从中轴指向手部的水平方向（自动，左右手/镜像都不会挪反）
            if (GolemComboStages.OUTWARD_OFFSET != 0.0F) {
                Vec3 outward = new Vec3(hand.x, 0.0, hand.z);
                if (outward.lengthSqr() > 1.0E-6) {
                    outward = outward.normalize().scale(GolemComboStages.OUTWARD_OFFSET);
                    pose.translate(outward.x, 0.0, outward.z);
                }
            }
            // 旋转枢轴的位置 = 模型底端（刀尾/剑柄末端，差不多就是手的下方）+ PIVOT_LIFT。
            // 这里额外加 reach 是为了<b>抵消</b>下面那步 "沿刀身把武器推出去" 对枢轴的拖拽：
            // 推出去的量会顺带把枢轴一起顶到刀尾之下，加回来之后枢轴恒在刀尾，
            // reach 就只剩"武器整体往外伸"这一种表现。实机想要"绕刀尾转"就是靠这一行。
            float pivot = GolemComboStages.PIVOT_LIFT + reach;
            if (pivot != 0.0F) {
                pose.translate(0.0F, pivot, 0.0F);
            }
            // 外层：挥砍动作，绕手部固定轴（Y → Z → X）
            pose.mulPose(Axis.YP.rotationDegrees(yaw));
            pose.mulPose(Axis.ZP.rotationDegrees(roll));
            pose.mulPose(Axis.XP.rotationDegrees(pitch));
            // 内层：武器自身姿态 —— 先把刀摆成该有的样子，再被上面的动作整体挥出去
            pose.mulPose(Axis.YP.rotationDegrees(base.y()));
            pose.mulPose(Axis.ZP.rotationDegrees(base.z()));
            pose.mulPose(Axis.XP.rotationDegrees(base.x()));
            if (pivot != 0.0F) {
                pose.translate(0.0F, -pivot, 0.0F);
            }
            // 沿刀身（模型里刀身朝 +Y）推出去 —— 这就是"攻击距离"的表现
            pose.translate(0.0F, reach, 0.0F);
            pose.scale(scale, scale, scale);

            // 最内层：先把"武器本身固有的朝向/原点"摆正（2D 贴图武器与 3D 模型的差别在这一层补）
            if (fix != null) {
                pose.mulPose(Axis.YP.rotationDegrees(fix.angles().y()));
                pose.mulPose(Axis.ZP.rotationDegrees(fix.angles().z()));
                pose.mulPose(Axis.XP.rotationDegrees(fix.angles().x()));
                pose.translate(fix.offsetX(), fix.offsetY(), fix.offsetZ());
                if (fix.scale() != 1.0F) {
                    pose.scale(fix.scale(), fix.scale(), fix.scale());
                }
            }

            MultiBufferSource target = live ? buffer : new TrailBuffer(buffer, trailAlpha(i, copies));
            mc.getItemRenderer().render(stack, CONTEXT, false, pose, target, light,
                    OverlayTexture.NO_OVERLAY, model);
            pose.popPose();
        }
    }

    /** 某个物品该套哪份修正（懒解析 + 缓存；没有规则命中就是 {@code null}）。 */
    private static final Map<Item, Optional<GolemComboStages.WeaponFix>> FIX_CACHE = new ConcurrentHashMap<>();

    /** 已编译的规则表（懒加载）。 */
    private static List<ParsedRule> parsedRules;

    private record ParsedRule(Pattern pattern, GolemComboStages.WeaponFix fix) {
    }

    /** 已经打过日志的物品，避免刷屏。 */
    private static final Set<Item> REPORTED = ConcurrentHashMap.newKeySet();

    /**
     * 这把武器要不要套修正、套哪一份：按 {@link GolemComboStages#WEAPON_FIX_RULES} 自上而下匹配物品 id
     * （支持 {@code *} 通配）。
     * <p>
     * <b>不看模型长什么样</b> —— 曾经按"模型朝向数 ≤ 2 就是贴图"自动判定，结果判反了；真正的判据是
     * "这个模型的 display 带不带旋转"（本家按 {@code THIRD_PERSON_RIGHT_HAND} 会套上、我们按
     * {@code NONE} 不套），而那只能靠 id 规则表达。
     * <p>
     * 每个物品第一次被渲染时打一行日志（命中哪条规则 / 不修正 + 模型朝向数），
     * 这样"规则有没有命中""id 有没有写错"直接看日志就知道。
     */
    private static GolemComboStages.WeaponFix weaponFix(ItemStack stack, BakedModel model) {
        Optional<GolemComboStages.WeaponFix> cached = FIX_CACHE.computeIfAbsent(stack.getItem(),
                GolemComboLayer::resolveWeaponFix);
        if (REPORTED.add(stack.getItem())) {
            Golems_arsenal.LOGGER.info("[combo] {}：{}（模型朝向数={}）",
                    BuiltInRegistries.ITEM.getKey(stack.getItem()),
                    cached.map(fix -> "套用武器修正 " + describe(fix)).orElse("不修正"),
                    countQuadDirections(model));
        }
        return cached.orElse(null);
    }

    private static Optional<GolemComboStages.WeaponFix> resolveWeaponFix(Item item) {
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        for (ParsedRule rule : parsedRules()) {
            if (rule.pattern().matcher(id).matches()) {
                return Optional.of(rule.fix());
            }
        }
        return Optional.empty();
    }

    private static List<ParsedRule> parsedRules() {
        if (parsedRules == null) {
            List<ParsedRule> list = new ArrayList<>();
            for (GolemComboStages.WeaponFixRule rule : GolemComboStages.WEAPON_FIX_RULES) {
                // \Q...\E 把整段按字面处理，只有 * 换成 .*
                list.add(new ParsedRule(Pattern.compile("\\Q" + rule.pattern().replace("*", "\\E.*\\Q") + "\\E"),
                        rule.fix()));
            }
            parsedRules = list;
        }
        return parsedRules;
    }

    private static String describe(GolemComboStages.WeaponFix fix) {
        return String.format("y=%s z=%s x=%s scale=%s", fix.angles().y(), fix.angles().z(),
                fix.angles().x(), fix.scale());
    }

    /** 模型里出现过几种四边形朝向（纯诊断用）。 */
    private static int countQuadDirections(BakedModel model) {
        Set<Direction> directions = EnumSet.noneOf(Direction.class);
        RandomSource random = RandomSource.create(42L);
        for (Direction direction : Direction.values()) {
            for (BakedQuad quad : model.getQuads(null, direction, random)) {
                if (quad.getDirection() != null) {
                    directions.add(quad.getDirection());
                }
            }
        }
        for (BakedQuad quad : model.getQuads(null, null, random)) {
            if (quad.getDirection() != null) {
                directions.add(quad.getDirection());
            }
        }
        return directions.size();
    }

    /** 第 i 份拖影的透明度：从 {@code TRAIL_ALPHA_START} 均匀压到 {@code TRAIL_ALPHA_END}。 */
    private static float trailAlpha(int index, int copies) {
        if (copies <= 2) {
            return GolemComboStages.TRAIL_ALPHA_START;
        }
        float t = (index - 1) / (float) (copies - 2);
        return Mth.lerp(t, GolemComboStages.TRAIL_ALPHA_START, GolemComboStages.TRAIL_ALPHA_END);
    }

    /**
     * <b>只取手部的位置，丢掉手部的旋转</b>，并把手部位置返回给调用方（供"向外挪"算方向用）。
     * <p>
     * 本家的 {@code transformToHand} 给的是"手部完整变换"：平移 + 手臂当前的旋转。
     * 直接用它当基准的话，<b>所有角度都会绕"手的坐标系"转</b> —— 手臂怎么摆，刀的朝向就跟着怎么歪：
     * 零旋转时刀是横躺的、横扫扫到终点会指向侧面、上挑会指到地上去（实机截图确认过）。
     * 这里只把平移分量拿出来，于是后面所有旋转都落在<b>傀儡本体的固定轴</b>上：
     * +Y 上、−Z 前、±X 左右。
     */
    private static Vec3 translateToHandOnly(MetalGolemModel model, PoseStack pose, PoseStack scratch) {
        scratch.setIdentity();
        model.transformToHand(EquipmentSlot.MAINHAND, scratch);
        Matrix4f matrix = scratch.last().pose();
        pose.translate(matrix.m30(), matrix.m31(), matrix.m32());
        return new Vec3(matrix.m30(), matrix.m31(), matrix.m32());
    }

    /** 【调试】画出本体坐标系：+X 红、+Y 绿、+Z 蓝（Z 正方向是傀儡背后）。 */
    private static void renderDebugAxes(PoseStack pose, MultiBufferSource buffer, float length) {
        PoseStack.Pose entry = pose.last();
        VertexConsumer consumer = buffer.getBuffer(RenderType.lines());
        axis(consumer, entry, length, 0.0F, 0.0F, 1.0F, 0.25F, 0.25F, 0.0F, 1.0F, 0.0F);
        axis(consumer, entry, 0.0F, length, 0.0F, 0.25F, 1.0F, 0.25F, 0.0F, 1.0F, 0.0F);
        axis(consumer, entry, 0.0F, 0.0F, length, 0.3F, 0.4F, 1.0F, 0.0F, 1.0F, 0.0F);
    }

    private static void axis(VertexConsumer consumer, PoseStack.Pose entry, float x, float y, float z,
                             float r, float g, float b, float nx, float ny, float nz) {
        consumer.vertex(entry.pose(), 0.0F, 0.0F, 0.0F)
                .color(r, g, b, 1.0F).normal(entry.normal(), nx, ny, nz).endVertex();
        consumer.vertex(entry.pose(), x, y, z)
                .color(r, g, b, 1.0F).normal(entry.normal(), nx, ny, nz).endVertex();
    }

    /**
     * 把物品自己的渲染类型换成半透明版本。
     * <p>
     * 物品 quad 都在方块图集上（{@code InventoryMenu.BLOCK_ATLAS}），所以整体换过去贴图是对的；
     * 唯一的例外是<b>附魔闪光</b>：它用的是独立的 glint 贴图与渲染类型，直接换过去会拿方块图集采样、
     * 变成乱条纹。好在那些 glint 渲染类型都是 {@code RenderType} 里的缓存常量，可以用 {@code ==} 精确认出来，
     * 原样放行（顶点 alpha 仍然会被压，所以幽灵副本上的闪光也跟着淡）。
     */
    private record TrailBuffer(MultiBufferSource delegate, float alpha) implements MultiBufferSource {

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            if (isGlint(type)) {
                return new AlphaConsumer(delegate.getBuffer(type), alpha);
            }
            return new AlphaConsumer(delegate.getBuffer(TRAIL_TYPE), alpha);
        }

        private static boolean isGlint(RenderType type) {
            return type == RenderType.glint()
                    || type == RenderType.glintDirect()
                    || type == RenderType.glintTranslucent()
                    || type == RenderType.entityGlint()
                    || type == RenderType.entityGlintDirect()
                    || type == RenderType.armorGlint()
                    || type == RenderType.armorEntityGlint();
        }
    }

    /** 顶点 alpha 缩放器：原样转发，只把 alpha 乘上系数。 */
    private record AlphaConsumer(VertexConsumer delegate, float alpha) implements VertexConsumer {

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            delegate.vertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            delegate.color(r, g, b, (int) (a * alpha));
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            delegate.uv(u, v);
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            delegate.overlayCoords(u, v);
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            delegate.uv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            delegate.normal(x, y, z);
            return this;
        }

        @Override
        public void endVertex() {
            delegate.endVertex();
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
            delegate.defaultColor(r, g, b, (int) (a * alpha));
        }

        @Override
        public void unsetDefaultColor() {
            delegate.unsetDefaultColor();
        }
    }
}
