package a_silly_cat.golems_arsenal.client;

import a_silly_cat.golems_arsenal.base.entity.PhantomBladeEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 幻梦零剑气的渲染：<b>几何月牙 + 可选的拖尾</b>，不贴任何 2D 贴图。
 * <p>
 * 现在的阶段是<b>把两者拆开单独调</b>：月牙负责"斩击的刃口形状"，拖尾负责"飞行留下的痕迹"。
 * 它们在几何上互相打架（月牙要求两端收尖 → 根部细；拖尾要求根部粗），
 * 所以先用开关分别验收，定下来再同时开。
 * <p>
 * <b>开关</b>：{@link #ENABLE_CRESCENT} / {@link #ENABLE_TRAIL}。
 * 当前只开月牙 —— 拖尾参数保留为已验证过的那一组（见下），随时可以开回来。
 */
@OnlyIn(Dist.CLIENT)
public class GenmuZeroRenderer extends EntityRenderer<PhantomBladeEntity> {

    // ==================== 阶段开关 ====================

    /** 是否画月牙（平面曲面，侧看是剪影）。先关掉，单独验收八面体。 */
    private static final boolean ENABLE_CRESCENT = false;

    // ==================== 管状月牙（真 3D 月牙剑气） ====================
    //
    // 沿竖直方向弯曲的路径扫出的管状体：侧面轮廓是月牙弧，任何角度都有体积。
    // 与"内凹锥"的区别：锥是单尖 + 直边 → 箭头；这个的前缘是弧、两个尖在上下两端 → 月牙。

    /** 竖直半跨度（格）—— 月牙有多高。 */
    private static final float TUBE_SPAN = 2.40F;
    /** 中段向前鼓出量（格）—— 月牙有多弯。 */
    private static final float TUBE_BOW = 1.70F;
    /** 截面沿飞行方向的半厚（刀身厚）。 */
    private static final float TUBE_THICK = 0.60F;
    /** 截面沿水平侧向的半厚（刀背薄）—— 远小于 THICK 才是刀锋截面。 */
    private static final float TUBE_THIN = 0.28F;
    /** 分段：沿路径 / 一圈截面。 */
    private static final int TUBE_ALONG_SEGS = 18;
    private static final int TUBE_AROUND_SEGS = 8;

    /** 轴向渐变：尖白、背绿（原作的荧光绿就落在背面那一圈）。 */
    private static final int TUBE_FRONT_RGB = 0xA8FFD4;
    private static final int TUBE_BACK_RGB = 0x1FCC6E;
    private static final float TUBE_FRONT_ALPHA = 0.62F;
    private static final float TUBE_BACK_ALPHA = 0.38F;

    private static final int TUBE_CORE_FRONT_RGB = 0xE8FFF4;
    private static final int TUBE_CORE_BACK_RGB = 0x6FE8A8;
    private static final float TUBE_CORE_FRONT_ALPHA = 0.09F;
    private static final float TUBE_CORE_BACK_ALPHA = 0.05F;
    private static final float TUBE_FRESNEL_POW = 1.40F;

    // ---- 外发光（加法混合） ----
    // 只负责"一圈光"，不承担形状。稍大一圈、alpha 极低 —— 多叠几层也不会盖住本体颜色。

    /** 相对本体的放大倍数。 */
    private static final float GLOW_SCALE = 1.35F;
    /** 外发光的颜色（更纯的薄荷绿，加法下会自然发亮）。 */
    private static final int GLOW_RGB = 0x8FFFD0;
    private static final int GLOW_BACK_RGB = 0x2FD97A;
    /** 外发光的不透明度：必须极低，因为它是加法且会叠多层。 */
    private static final float GLOW_ALPHA = 0.05F;
    private static final float GLOW_BACK_ALPHA = 0.03F;


    //
    // 形态：尖头朝敌 + 背面内凹的锥体。从任何角度看都有体积，而侧面轮廓是月牙弧。
    // 这是"平面月牙"（像纸片）和"实心八面体"（像宝石）之外的第三条路。

    /** 是否画内凹锥。 */
    private static final boolean ENABLE_SPIKE = true;

    /** 外壳：环的竖直半径 / 侧向半径（拉开差距才像刀锋）。 */
    private static final float SPIKE_RADIUS_UP = 1.80F;
    private static final float SPIKE_RADIUS_SIDE = 0.28F;
    /** 尖相对环平面的前伸距离。 */
    private static final float SPIKE_LENGTH = 2.60F;
    /** 背心前推量：越大背面凹得越深，侧面月牙弧越明显。 */
    private static final float SPIKE_BACK_DEPTH = 1.10F;
    /** 环在上下极点处往后压的量：这是"月牙弧"的直接来源。 */
    private static final float SPIKE_RIM_BOW = 0.90F;
    /** 环上点数：4 = 内凹四棱锥；8~12 = 更圆润的漏斗。 */
    private static final int SPIKE_RING_POINTS = 8;

    /** 内芯：更小更亮，构成"里面有一团光"。 */
    private static final float SPIKE_CORE_RADIUS_UP = 0.85F;
    private static final float SPIKE_CORE_RADIUS_SIDE = 0.14F;
    private static final float SPIKE_CORE_LENGTH = 2.10F;
    private static final float SPIKE_CORE_BACK_DEPTH = 0.70F;
    private static final float SPIKE_CORE_RIM_BOW = 0.45F;

    private static final float SPIKE_FRESNEL_POW = 1.50F;
    /**
     * 轴向颜色渐变：<b>尖=白、环=荧光绿</b>。
     * <p>
     * 这是"前面白色刃口、背后荧光绿"的实现 —— 靠顶点色沿轴插值，不需要额外几何。
     * 环（背面）那一圈绿就是原作里剑气拖在身后的那道荧光。
     */
    private static final int SPIKE_FRONT_RGB = 0xFFFFFF;
    private static final int SPIKE_BACK_RGB = 0x3BFF9E;
    private static final float SPIKE_FRONT_ALPHA = 0.55F;
    private static final float SPIKE_BACK_ALPHA = 0.35F;

    /** 内芯：更亮更白，后段留一点淡绿。 */
    private static final int SPIKE_CORE_FRONT_RGB = 0xFFFFFF;
    private static final int SPIKE_CORE_BACK_RGB = 0xBFFFD8;
    private static final float SPIKE_CORE_FRONT_ALPHA = 0.60F;
    private static final float SPIKE_CORE_BACK_ALPHA = 0.25F;


    //
    // 参考 arcanevortex 的棱形法阵核心：八面体 + 半透明 + 自转。
    // 这是唯一能真正摆脱"纸片感"的构造 —— 它是个实心多面体，不是一张面。

    /** 是否画八面体体积。 */
    private static final boolean ENABLE_AURA = false;

    /**
     * 外壳：赤道竖直半径 / 侧向半径 / 沿飞行方向的半长（格）。
     * <p>
     * <b>细长</b>才像剑：长度 3.6 时高度只给 0.95，比例 ≈ 3.8:1。
     * 之前 3.2 : 1.6 只有 2:1，所以出来是一块"扁菱形板"。
     */
    private static final float AURA_RADIUS_UP = 0.95F;
    private static final float AURA_RADIUS_SIDE = 0.32F;
    private static final float AURA_HALF_LENGTH = 3.60F;

    /** 内芯：更小、更亮，负责"里面有一团光"而不是"一块玻璃"。 */
    private static final float CORE_RADIUS_UP = 0.45F;
    private static final float CORE_RADIUS_SIDE = 0.16F;
    private static final float CORE_HALF_LENGTH = 2.90F;

    /**
     * 绕长轴的自转（度/tick）。
     * <p>
     * 刀锋<b>必须是 0</b> —— 剑身自转看起来像钻头。想做成"能量晶体"再调大。
     */
    private static final float AURA_SPIN_DEGREES_PER_TICK = 0.0F;
    /**
     * fresnel 陡度：正对镜头的面更透明、轮廓处的面更实。
     */
    private static final float AURA_FRESNEL_POW = 1.60F;

    /**
     * 颜色与不透明度。
     * <p>
     * <b>加法混合下 alpha 要压低</b>：多面叠加会自己变亮，单层给 0.45 就够；
     * 给太高会整片烧成纯白，反而没有层次。
     */
    private static final int AURA_RGB = 0xD8FFF0;
    private static final float AURA_ALPHA = 0.45F;
    private static final int CORE_RGB = 0xFFFFFF;
    private static final float CORE_ALPHA = 0.75F;

    /**
     * 是否画拖尾。
     * <p>
     * <b>已关闭</b>：宽 1.0 × 长 11 的 billboard 带子出来就是一块 22 格长的实心绿板
     * （截图里那个大绿矩形），比没有更糟。真要拖尾就改成"细而透"的几条，而不是一条宽的。
     */
    private static final boolean ENABLE_TRAIL = true;

    /**
     * 月牙绕竖直轴画几片、每片偏转多少度。
     * <p>
     * <b>这是用来解决"纸片感"的</b>：单层曲面从侧面看永远是剪影，因为平面本身没有厚度。
     * 同一个形状转几个角度叠起来，侧面就能看到交叠的层次，读起来才有体积。
     * <p>
     * 1 = 关闭（回到单层纸片）；3 片 × 25° 是比较稳的起点。
     */
    private static final int CRESCENT_SLICES = 1;
    private static final float CRESCENT_SLICE_ANGLE = 25.0F;

    // ==================== 月牙参数 ====================
    //
    // 用 TrailMesh.crescentOriented。三个轴由渲染时按相机算出（绕竖直轴朝向镜头的 billboard），
    // 因为**平面形状固定朝向，从侧面看就一定是一条边缘线** —— 参数救不了，只能换姿态。
    // 这是绝大多数游戏里"剑气/斩击波"的做法：始终面向镜头，保证任何角度都是完整月牙。

    /**
     * 两个尖之间的跨度（格）—— 也是"月牙有多高"。
     * <p>
     * <b>只有一套数值</b>：外晕和内芯用完全相同的几何，只差颜色与不透明度，
     * 所以不会再出现"外面一个大月牙、里面一个小月牙"。
     */
    private static final float CRESCENT_SPAN = 5.0F;

    /** 带子最厚处的半厚（格）。在水平面内，构成月牙的"臂宽"。 */
    private static final float CRESCENT_THICK = 0.60F;

    /** 鼓包在水平面内兜出的距离（格）。越大弧越弯；0 = 一条直带。 */
    private static final float CRESCENT_BOW = 1.50F;

    /** 分段数。段数太少中段会出棱角。 */
    private static final int CRESCENT_SEGMENTS = 18;

    /**
     * 内芯颜色：偏白。
     * <p>
     * "前方白色刃口、身后荧光绿"是对的目标，但绿色属于**拖尾**，月牙本身应该是白刃。
     */
    private static final int CORE_HEAD_RGB = 0xFFFFFF;
    private static final int CORE_TAIL_RGB = 0xE4FFF2;
    /** 外晕颜色：略偏绿的白，给刃口一圈冷光。 */
    private static final int GLOW_HEAD_RGB = 0xEEFFF6;
    private static final int GLOW_TAIL_RGB = 0x9FE8C6;

    /** 不透明度。五连发会同时画 5 个并重叠，外晕必须压低，否则叠加成一坨实色。 */
    private static final float CORE_HEAD_ALPHA = 0.55F;
    private static final float GLOW_HEAD_ALPHA = 0.25F;
    private static final float TAIL_ALPHA = 0.0F;

    /**
     * 剖面锐度：1 = 标准 sin 弧；<b>&gt;1 更瘦</b>（厚度集中中段，像刃）；&lt;1 更胖（像叶子）。
     */
    private static final float THICKNESS_POW = 1.70F;

    // ==================== 拖尾参数（纺锤形主尾 + 副尾） ====================
    //
    // 纺锤形 = 两端收尖、中段最宽。用 wake() 生成。

    /** 拖尾起点相对实体位置的前移量（格）：跟上月牙前鼓的身体，消除接缝。 */
    private static final float WAKE_FORWARD_OFFSET = 1.70F;

    /** 主尾长度（格）。 */
    private static final float WAKE_LENGTH = 6.0F;
    /** 沿管身的分段。 */
    private static final int WAKE_ALONG_SEGS = 16;

    /**
     * 起点截面相对月牙截面的比例。
     * <p>
     * <b>必须接近 1</b> —— 这是"无缝"的关键：拖尾起点的粗细要等于月牙最宽处，
     * 否则接缝处会出现台阶。略小于 1（0.9）让它在视觉上"从月牙里长出来"。
     */
    private static final float WAKE_ROOT_SCALE = 0.90F;
    private static final float WAKE_SIDE_ROOT_SCALE = 0.38F;

    /** 颜色与透明度：靠近月牙处亮、远端深绿透明。 */
    private static final int WAKE_NEAR_RGB = 0x8FFFC8;
    private static final int WAKE_FAR_RGB = 0x0E5C36;
    private static final float WAKE_NEAR_ALPHA = 0.38F;
    private static final float WAKE_FAR_ALPHA = 0.0F;

    /** 副尾：竖直偏移、长度 / 透明度比例。 */
    private static final float WAKE_SIDE_OFFSET = 1.20F;
    private static final float WAKE_SIDE_LENGTH_RATIO = 0.62F;
    private static final float WAKE_SIDE_ALPHA_RATIO = 0.55F;

    public GenmuZeroRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0F;
    }

    @Override
    public void render(PhantomBladeEntity entity, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int light) {
        // 与 EntityRenderDispatcher 保持同一套插值，否则会抖
        Vec3 origin = new Vec3(
                Mth.lerp(partialTick, entity.xOld, entity.getX()),
                Mth.lerp(partialTick, entity.yOld, entity.getY()),
                Mth.lerp(partialTick, entity.zOld, entity.getZ()));

        Vec3 motion = entity.getDeltaMovement();
        Vec3 dir = motion.lengthSqr() > 1.0E-8 ? motion.normalize() : entity.getLookAngle();
        if (dir.lengthSqr() < 1.0E-8) {
            super.render(entity, entityYaw, partialTick, pose, buffer, light);
            return;
        }

        Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        VertexConsumer vc = buffer.getBuffer(TrailRenderTypes.TRANSLUCENT_TRIANGLES);

        // 月牙的三个轴 —— 绕竖直轴朝向相机（常见剑气的做法）
        //
        // 为什么不能固定朝前：平面形状从侧面看就是一条边缘线，参数怎么调都救不回来。
        // 游戏里绝大多数"剑气/斩击波"都做成**始终面向镜头**的竖直月牙，任何角度都是完整形状。
        // 代价是"鼓包朝前"的方向感没了 —— 换成"鼓包在水平面内、垂直于镜头"，这是可读性换来的。
        Vec3 worldUp = new Vec3(0.0, 1.0, 0.0);
        Vec3 toEye = eye.subtract(origin);
        Vec3 toEyeH = new Vec3(toEye.x, 0.0, toEye.z);
        if (toEyeH.lengthSqr() < 1.0E-6) {
            toEyeH = new Vec3(0.0, 0.0, 1.0);                 // 正上/正下看时的兜底
        }
        toEyeH = toEyeH.normalize();

        Vec3 spanAxis = worldUp;                               // 两个尖朝上下
        Vec3 bowAxis = toEyeH.cross(worldUp);                  // 鼓包：水平面内、垂直于视线
        if (bowAxis.lengthSqr() < 1.0E-6) {
            bowAxis = new Vec3(1.0, 0.0, 0.0);
        }
        bowAxis = bowAxis.normalize();

        pose.pushPose();
        PoseStack.Pose p = pose.last();

        // 月牙走**加法混合**：幻梦零的剑气是发光的能量，不是半透明的塑料片。
        // 加法 = 往画面里加光，边缘叠加会自然变亮；半透明 = 透过一块有色玻璃看，观感完全不同。
        VertexConsumer auraVc = buffer.getBuffer(TrailRenderTypes.ADDITIVE_TRIANGLES);

        if (ENABLE_AURA) {
            // 刀身"高"用世界正上方、"厚"用水平侧向 → 竖立的扁刀锋，而不是圆宝石
            Vec3 apexUp = dir.scale(worldUp.dot(dir));
            Vec3 upPerp = worldUp.subtract(apexUp);
            if (upPerp.lengthSqr() < 1.0E-6) {
                upPerp = new Vec3(1.0, 0.0, 0.0);
            }
            upPerp = upPerp.normalize();
            Vec3 sidePerp = dir.cross(upPerp);
            if (sidePerp.lengthSqr() < 1.0E-6) {
                sidePerp = new Vec3(0.0, 0.0, 1.0);
            }
            sidePerp = sidePerp.normalize();

            // 加法混合缓冲：能量体是"加光"，不是"透光"
            float spin = (float) Math.toRadians(
                    (entity.tickCount + partialTick) * AURA_SPIN_DEGREES_PER_TICK);

            // 外壳
            AuraMesh.octahedron(auraVc, p, origin, dir, upPerp, sidePerp,
                    AURA_RADIUS_UP, AURA_RADIUS_SIDE, AURA_HALF_LENGTH,
                    spin, eye, AURA_RGB, AURA_ALPHA, AURA_FRESNEL_POW);

            // 内芯：叠一层更小更亮的，形成"里面有光"
            AuraMesh.octahedron(auraVc, p, origin, dir, upPerp, sidePerp,
                    CORE_RADIUS_UP, CORE_RADIUS_SIDE, CORE_HALF_LENGTH,
                    spin, eye, CORE_RGB, CORE_ALPHA, AURA_FRESNEL_POW);
        }

        if (ENABLE_TRAIL) {
            // 拖尾也用**管状体**，和月牙同一套截面 + 明暗 + 材质。
            //
            // 之前用扁平 ribbon（billboard），结果是一颗立体月牙后面拖着一片 2D 纸 ——
            // 那不是接缝问题，是两种几何天生不同类，怎么调位置和透明度都衔不上。
            //
            // 起点放在月牙"最宽处"附近，截面尺寸直接沿用月牙的 → 接缝处两边粗细一致。
            Vec3 upPerp = worldUp.subtract(dir.scale(worldUp.dot(dir)));
            if (upPerp.lengthSqr() < 1.0E-6) {
                upPerp = new Vec3(1.0, 0.0, 0.0);
            }
            upPerp = upPerp.normalize();
            Vec3 sidePerp = dir.cross(upPerp);
            if (sidePerp.lengthSqr() < 1.0E-6) {
                sidePerp = new Vec3(0.0, 0.0, 1.0);
            }
            sidePerp = sidePerp.normalize();

            Vec3 wakeOrigin = origin.add(dir.scale(WAKE_FORWARD_OFFSET));

            // 主尾
            AuraMesh.taperTube(vc, p, wakeOrigin, dir, upPerp, sidePerp,
                    TUBE_THICK * WAKE_ROOT_SCALE, TUBE_THIN * WAKE_ROOT_SCALE,
                    WAKE_LENGTH, WAKE_ALONG_SEGS, TUBE_AROUND_SEGS,
                    eye, WAKE_NEAR_RGB, WAKE_FAR_RGB,
                    WAKE_NEAR_ALPHA, WAKE_FAR_ALPHA, TUBE_FRESNEL_POW);

            // 副尾：上下各一条更细的，暗示月牙两个尖拖出的余势
            for (int s = -1; s <= 1; s += 2) {
                Vec3 sideOrigin = wakeOrigin.add(upPerp.scale(s * WAKE_SIDE_OFFSET));
                AuraMesh.taperTube(vc, p, sideOrigin, dir, upPerp, sidePerp,
                        TUBE_THICK * WAKE_SIDE_ROOT_SCALE, TUBE_THIN * WAKE_SIDE_ROOT_SCALE,
                        WAKE_LENGTH * WAKE_SIDE_LENGTH_RATIO,
                        WAKE_ALONG_SEGS, TUBE_AROUND_SEGS,
                        eye, WAKE_NEAR_RGB, WAKE_FAR_RGB,
                        WAKE_NEAR_ALPHA * WAKE_SIDE_ALPHA_RATIO, WAKE_FAR_ALPHA,
                        TUBE_FRESNEL_POW);
            }
        }

        if (ENABLE_SPIKE) {
            // 环的"长"轴取竖直（底面与地面垂直），"宽"轴取水平侧向 → 扁刀锋截面
            Vec3 upPerp = worldUp.subtract(dir.scale(worldUp.dot(dir)));
            if (upPerp.lengthSqr() < 1.0E-6) {
                upPerp = new Vec3(1.0, 0.0, 0.0);
            }
            upPerp = upPerp.normalize();
            Vec3 sidePerp = dir.cross(upPerp);
            if (sidePerp.lengthSqr() < 1.0E-6) {
                sidePerp = new Vec3(0.0, 0.0, 1.0);
            }
            sidePerp = sidePerp.normalize();

            // ① 本体：**半透明混合** —— 颜色准确，不会被加法烧白。
            //    p/vc 是半透明缓冲（TrailRenderTypes.TRANSLUCENT_TRIANGLES）。
            AuraMesh.crescentTube(vc, p, origin, upPerp, dir, sidePerp,
                    TUBE_SPAN, TUBE_BOW, TUBE_THICK, TUBE_THIN,
                    TUBE_ALONG_SEGS, TUBE_AROUND_SEGS,
                    eye, TUBE_FRONT_RGB, TUBE_BACK_RGB,
                    TUBE_FRONT_ALPHA, TUBE_BACK_ALPHA, TUBE_FRESNEL_POW);

            // ② 外发光：**加法混合**，稍大一圈、极低 alpha。
            //    它只负责"有一圈光"，不承担形状 —— 所以就算叠加也不会盖住本体的颜色。
            AuraMesh.crescentTube(auraVc, p, origin, upPerp, dir, sidePerp,
                    TUBE_SPAN * GLOW_SCALE, TUBE_BOW * GLOW_SCALE,
                    TUBE_THICK * GLOW_SCALE, TUBE_THIN * GLOW_SCALE,
                    TUBE_ALONG_SEGS, TUBE_AROUND_SEGS,
                    eye, GLOW_RGB, GLOW_BACK_RGB,
                    GLOW_ALPHA, GLOW_BACK_ALPHA, TUBE_FRESNEL_POW);
        }

        if (ENABLE_CRESCENT) {
            // 绕竖直轴画多片 —— 单层曲面侧看必然是剪影，叠几片才有体积感
            int slices = Math.max(1, CRESCENT_SLICES);
            float alphaScale = 1.0F / (float) Math.sqrt(slices);
            for (int s = 0; s < slices; s++) {
                float spread = slices == 1 ? 0.0F
                        : (s - (slices - 1) * 0.5F) * CRESCENT_SLICE_ANGLE;
                Vec3 sliceBow = rotateAroundY(bowAxis, spread);
                Vec3 sliceWidth = sliceBow;   // 厚度与鼓包同轴，保持在水平面内

                // 两层几何完全相同，只差颜色与不透明度 → 不会出现"外大内小"的两个月牙
                TrailMesh.crescentOriented(auraVc, p, origin,
                        spanAxis, sliceWidth, sliceBow,
                        CRESCENT_SPAN, CRESCENT_THICK, CRESCENT_BOW,
                        GLOW_HEAD_RGB, GLOW_TAIL_RGB,
                        GLOW_HEAD_ALPHA * alphaScale, TAIL_ALPHA,
                        CRESCENT_SEGMENTS, THICKNESS_POW);

                TrailMesh.crescentOriented(auraVc, p, origin,
                        spanAxis, sliceWidth, sliceBow,
                        CRESCENT_SPAN, CRESCENT_THICK, CRESCENT_BOW,
                        CORE_HEAD_RGB, CORE_TAIL_RGB,
                        CORE_HEAD_ALPHA * alphaScale, TAIL_ALPHA,
                        CRESCENT_SEGMENTS, THICKNESS_POW);
            }
        }

        pose.popPose();

        super.render(entity, entityYaw, partialTick, pose, buffer, light);
    }

    /** 绕世界竖直轴旋转一个向量（用于把月牙复制成几片、各偏一点角度）。 */
    private static Vec3 rotateAroundY(Vec3 v, float degrees) {
        if (degrees == 0.0F) {
            return v;
        }
        double r = Math.toRadians(degrees);
        double c = Math.cos(r);
        double s = Math.sin(r);
        return new Vec3(v.x * c - v.z * s, v.y, v.x * s + v.z * c);
    }

    @Override
    public ResourceLocation getTextureLocation(PhantomBladeEntity entity) {
        return PhantomBladeEntity.TEXTURE;
    }
}
