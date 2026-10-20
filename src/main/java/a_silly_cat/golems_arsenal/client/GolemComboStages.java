package a_silly_cat.golems_arsenal.client;

import net.minecraft.world.item.ItemDisplayContext;

import java.util.List;

/**
 * 三连击的动作参数表 —— <b>调手感就只改这个文件</b>。
 * <p>
 * <b>一次近战攻击 = 整套三段</b>：起手后服务端每 {@code GolemComboHandler.LINK_TICKS} tick 开下一段，
 * 不等上一段演完，所以相邻两段会重叠（场上同时出现两三把刀，客户端也支持同时演多段）。
 * <p>
 * <b>武器基准姿态修正</b>见 {@link #HELD_WEAPON_FIX} / {@link #WEAPON_FIX_RULES}：
 * {@code STAGES} 是对着参考武器调的，换一类武器（有的模型在本家手里会被摆成"握持姿态"）
 * 可能对不上，按物品 id 规则点名修正。
 * <p>
 * <b>坐标与旋转语义</b>：<b>所有旋转都绕"傀儡本体"的固定轴</b>（渲染层只取手部的<i>位置</i>，
 * 不继承手部的旋转——否则姿态会跟着手臂摆放一起歪，终点会指到侧面去）。本体坐标系：
 * <b>+Y = 上</b>，<b>−Z = 傀儡正面朝的方向（前方）</b>，<b>±X = 左右</b>。
 * 打开 {@link #DEBUG_AXES} 会在手部位置画出三根轴（+X 红 / +Y 绿 / +Z 蓝），对着它调最快。
 * <p>
 * 旋转的合成顺序（外层 → 内层，全部绕本体固定轴）：
 * <pre>Y(动作) · Z(动作) · X(动作) · Y(base) · Z(base) · X(base)</pre>
 * 也就是：<b>先把刀按 {@code base} 摆好，再被动作整体挥出去</b>。
 * <p>
 * 单看一个角对"刀"做了什么（刀初始 = 刀身朝 +Y、刀面法线朝 +Z）：
 * <ul>
 *   <li>{@code x} 俯仰：<b>0° = 刀朝上</b>，−90° = 刀朝<b>正前</b>，−180° = 刀朝下</li>
 *   <li>{@code z} 的含意见"刀当前朝哪"：刀<b>朝上</b>时它把刀左右侧倾；刀<b>已经朝前</b>（x = −90）时，
 *       它变成<b>绕刀身自转</b> —— 决定刀面朝向（0° 刀面平躺 / ±90° 刀面竖立）</li>
 *   <li>{@code y} 的含意见"刀当前朝哪"：刀朝上时它只是自转；刀<b>已经朝前</b>时它才是
 *       <b>水平横扫</b>的方位角 —— 所以横扫必须先靠 base 里的 x 把刀放到前方</li>
 * </ul>
 * ② 的 base 全 0（刀朝上、竖直面内劈）是<b>拿实机截图验证过的基准</b>，另两段照它推。
 * <p>
 * <b>刀光怎么来的</b>：第 i 份副本画在 {@code progress - i * lagPerCopy} 处 ——
 * 也就是"这把刀<i>之前</i>经过的角度"，并且<b>越旧越透明</b>（半透明重绘，参考灾变
 * {@code AfterImageParticle} 的做法）。副本因此沿扫过的轨迹淡出成拖影，而不是糊成一坨。
 */
public final class GolemComboStages {

    /** 缓动：让挥砍有加速/减速，而不是匀速平移。 */
    public enum Ease {
        LINEAR,
        /** 快起慢收（挥出去的那一下）。 */
        OUT,
        /** 慢起快收（砸中的那一下）。 */
        IN;

        public double at(double p) {
            double t = Math.max(0.0, Math.min(1.0, p));
            return switch (this) {
                case LINEAR -> t;
                case OUT -> 1.0 - (1.0 - t) * (1.0 - t);
                case IN -> t * t;
            };
        }
    }

    /** 一个姿态：三个欧拉角（度）。 */
    public record Pose(float y, float z, float x) {
    }

    /**
     * 连击副本用哪个<b>显示上下文</b>渲染 —— 这是"我们画的刀"和"傀儡手里那把刀"能不能对上的总开关。
     * <p>
     * 本家 {@code GolemEquipmentRenderer#renderArmWithItem} 的路径是：
     * <pre>
     *   ItemDisplayContext ctx = slot == MAINHAND ? THIRD_PERSON_RIGHT_HAND : THIRD_PERSON_LEFT_HAND;
     *   itemInHandRenderer.renderItem(entity, stack, ctx, leftHand, pose, buffer, light);
     * </pre>
     * 而 {@code ItemRenderer.render(...)} 内部会调 {@code ForgeHooksClient.handleCameraTransforms(pose, model, ctx, leftHand)}
     * —— <b>套的就是该上下文的 display 变换</b>（例如 {@code modulargolems:item/sword} 的
     * {@code thirdperson_righthand: rotation [0,-90,55], scale [1.7,1.7,0.85]}）。
     * <p>
     * <b>所以之前用 {@link ItemDisplayContext#NONE} 时，我们画的是"没有任何 display 变换的原始模型"</b>，
     * 而本家画的是"套了握持变换的那把" —— 贴图武器（`modulargolems:*_golem_sword` 系就是
     * {@code item/generated} 贴图）在这种差别下会整体旋转/缩放都不一样，靠"每把武器一个修正角"是补不平的。
     * <p>
     * <b>两个选项</b>：
     * <ul>
     *   <li>{@link ItemDisplayContext#NONE}（当前）：保持既有调参坐标不动，谁对不上就按
     *       {@link #WEAPON_FIX_RULES} 点名修正。</li>
     *   <li>{@link ItemDisplayContext#THIRD_PERSON_RIGHT_HAND}：<b>和本家手里那把走同一条路</b>，
     *       基准姿态天然一致，修正名单基本可以清空 —— 但 {@link #STAGES} 那套角度是旧坐标系调的，
     *       换过来相当于整体平移/旋转了一截，需要重调（对没有 rotation 的武器，只是缩放/位移变化）。</li>
     * </ul>
     */
    public static final ItemDisplayContext RENDER_CONTEXT = ItemDisplayContext.NONE;

    /**
     * 武器"基准姿态修正"：把某类武器<b>固有的</b>朝向摆到与调参用的参考武器一致。
     * <p>
     * 存在理由：{@code STAGES} 那套角度是对着<b>参考武器</b>调出来的，换一类武器就未必成立 ——
     * 典型是<b>薄片贴图武器</b>（{@code item/generated} / {@code handheld}，十几像素宽的平面）
     * 与<b>立体模型武器</b>（{@code elements} 搭的方块，横截面接近正方形）的差别：
     * 立体的对"绕刀身自转"完全不敏感，薄片的自转直接决定你看到刀面还是一条线。
     * <p>
     * <b>为什么用规则表而不是自动判定</b>：曾经按"模型朝向数 ≤ 2 就是贴图"自动套用，结果判反了
     * （参考武器被套上、真正要修的没被套上）。更根本的是判据本身就选错了 ——
     * 真正的差别不是"贴图还是立体"，而是<b>这个模型的 display 里带不带旋转</b>：
     * 本家按 {@code THIRD_PERSON_RIGHT_HAND} 画，会套上 {@code [0,−90,55]}；我们按 {@code NONE} 画，
     * 一套都不套。所以按物品 id 规则点名最实在（见 {@link #WEAPON_FIX_RULES}）。
     * <p>
     * 纠正顺序（最内层，先作用在模型自己身上）：<b>缩放 → 平移 → 旋转</b>。
     */
    public record WeaponFix(Pose angles, float offsetX, float offsetY, float offsetZ, float scale) {
    }

    /**
     * <b>一条修正规则</b>：物品 id 命中 {@code pattern} 就套用 {@code fix}。
     * {@code pattern} 支持 {@code *} 通配（例如 {@code modulargolems:*_golem_spear}）。
     */
    public record WeaponFixRule(String pattern, WeaponFix fix) {
    }

    /**
     * "被握住"的那套姿态 —— 直接取本家三套武器父模型的 {@code thirdperson_righthand} 旋转，
     * 三者<b>完全相同</b>（我逐个数过它们的 json）：
     * <ul>
     *   <li>{@code modulargolems:item/sword} —— 剑系（含各附属的 {@code *_golem_sword}）</li>
     *   <li>{@code modulargolems:item/battle_axe} —— 斧系（切片斧 {@code golem_slicing_axe} 也走这个）</li>
     *   <li>{@code modulargolems:item/long_weapon} —— 长枪系（mgdp 的长枪也走这个）</li>
     * </ul>
     * 都是 {@code rotation [x=0, y=−90, z=55]}。vanilla 用 {@code Quaternionf.rotationXYZ(rx,ry,rz)}，
     * 按四元数乘积验算过它等于 {@code Rx·Ry·Rz}；x=0 时退化为 {@code Ry·Rz}，与本修正层的合成
     * （代码先 Y 后 Z 后 X，x=0 同样退化为 {@code Ry·Rz}）<b>逐位等价</b> —— 所以这两个数可以
     * 直接从模型 json 里照抄，不存在顺序反了的风险。
     * <p>
     * 注意：display 里还有 <b>{@code scale [1.7, 1.7, 0.85]}</b> 与各自的 translation（剑 10.0 /
     * 斧 5.0 / 枪 7.0），那几个是"在本家手里的尺寸与位置"，没有照抄 —— 连击的大小另有层级控制
     * （全局 {@link #SCALE_MULTIPLIER}、每段 {@code from/to.scale}、以及这里的 {@code scale}）。
     * <p>
     * 这里的 {@code scale = 1.4} 是<b>尺寸对齐</b>用的：那几族是 16 像素高的<b>薄片贴图</b>（{@code item/generated}，
     * 正好 1 格），而调参参照的键刃是 22 像素高（y 1→23，1.375 格）—— 22/16 ≈ 1.375，
     * 所以贴图系在连击里会显得比立体武器小一圈，乘 1.4 就齐了。
     * 想让某族更大/更小，就给它单独写一条规则、用不同的 {@code WeaponFix}（规则表支持每条不同值）。
     */
    public static final WeaponFix HELD_WEAPON_FIX =
            new WeaponFix(new Pose(-90F, 55F, 0F), 0.0F, 0.0F, 0.0F, 1.4F);

    /**
     * <b>武器修正规则表</b>（自上而下第一条命中生效）。
     * <p>
     * 关键点：<b>本仓库自己的键刃 / 神通棍不在表里</b> —— 它们的 display 没有 rotation（零基准），
     * 而 {@link #STAGES} 那套角度正是对着它们调的，所以它们不需要任何修正；
     * 表里只放"在本家手里会被摆成握持姿态"的那几族（它们的 display 带 {@code [0,−90,55]}）。
     * <p>
     * 每个物品第一次被渲染时日志会打一行"套用武器修正 / 不修正"，方便核对规则有没有命中。
     */
    public static final List<WeaponFixRule> WEAPON_FIX_RULES = List.of(
            // 本家：剑 / 斧 / 长枪三大族（每族一堆材质变体都命中）
            new WeaponFixRule("modulargolems:*_golem_sword", HELD_WEAPON_FIX),
            new WeaponFixRule("modulargolems:*_golem_axe", HELD_WEAPON_FIX),
            new WeaponFixRule("modulargolems:*_golem_spear", HELD_WEAPON_FIX),
            // 特例：切片斧的名字不叫 *_golem_axe
            new WeaponFixRule("modulargolems:golem_slicing_axe", HELD_WEAPON_FIX),
            // mgdp 的长枪（父模型同样是 long_weapon）
            new WeaponFixRule("mgdp:*_golem_spear", HELD_WEAPON_FIX),
            // J村傀儡：整个包（它里面的武器全部继承上面三套父模型之一，共 22 把）
            new WeaponFixRule("jerotes_village_golems:*", HELD_WEAPON_FIX));

    /** 关键帧 = 姿态 + 攻击距离（沿刀身推出去多少格）+ 缩放。 */
    public record Keyframe(float y, float z, float x, float reach, float scale) {
    }

    /**
     * 一段动作 = 一次完整的挥砍。
     *
     * @param name       仅用于调试/日志
     * @param ticks      这一段持续多少 tick（越小越快）
     * @param ease       段内缓动
     * @param from       起手关键帧：进度 0 时就<b>直接瞬移</b>到这里
     * @param to         收手关键帧
     * @param base       武器自身姿态（整段不变）：<b>"武器自己的角度"就是这个</b>
     * @param copies     这一段画几份副本（含"当前这一份"）
     * @param lagPerCopy 每份拖影往前回溯多少进度（占整段的百分比）
     */
    public record Stage(String name, int ticks, Ease ease, Keyframe from, Keyframe to, Pose base,
                        int copies, float lagPerCopy) {
    }

    // ------------------------------------------------------------------ 可调常量

    /**
     * 全局放大倍数：在每段自己的 scale 之上再乘一次。
     * <p>
     * 现在实际画出来是 <b>2.75 ~ 3.75 倍</b>。嫌不够就只拧这一个数。
     */
    public static final float SCALE_MULTIPLIER = 2.5F;

    /**
     * <b>旋转枢轴的位置</b>（格），基准是模型的底端 —— 也就是刀尾 / 剑柄末端，差不多就在手的下方。
     * <ul>
     *   <li><b>0 = 绕刀尾转</b>（默认，实机想要的就是这个）；</li>
     *   <li><b>正值 = 枢轴沿刀身往上挪</b>（挪到刀身中段，刀尖划的弧变小、更像抡）；</li>
     *   <li><b>负值 = 枢轴挪到刀尾之下</b>（整把刀像被吊着甩）。</li>
     * </ul>
     * 注意它已经和每段的 {@code reach} <b>解耦</b>：渲染层会自动把 {@code reach} 对枢轴的拖拽抵消掉，
     * 所以这个数是"绝对位置"，不会被不同段的 reach 拽跑；{@code reach} 只表现为"武器整体往外伸"。
     */
    public static final float PIVOT_LIFT = 0.0F;

    /**
     * <b>整段演出统一"向外"挪多少格</b>（格，1 格 = 1 方块）—— 用来把武器手柄从傀儡身体里挪出来。
     * <p>
     * 方向是<b>自动</b>算的：取"从本体中轴指向手部"的水平方向，所以不管镜像、左右手、哪个模型，
     * 都一定是往外推，不会反着挪进身体里。0 = 不挪。
     * <p>
     * 它是最外层（先于所有旋转生效），所以三段共用同一个外移量；如果发现某一段单独还想再多挪，
     * 那就不是这个常量的事，而是那一段的 {@code base} 摆位问题。
     */
    public static final float OUTWARD_OFFSET = 0.15F;


    /** 拖影起点透明度（0 = 全透明，1 = 不透明）。最新的那一份永远是实心的。 */
    public static final float TRAIL_ALPHA_START = 0.50F;

    /** 拖影末端透明度。调低 = 尾巴收得更快、更干净。 */
    public static final float TRAIL_ALPHA_END = 0.06F;

    /**
     * 还没被"扫出来"的那几份副本怎么处理：
     * <ul>
     *   <li>{@code false}（当前）= 夹在起手姿态上：进度 0 时整扇刀光一起瞬移到起手位置；</li>
     *   <li>{@code true} = 干脆不画：刀扫到哪里，拖影才长到哪里（起手时只有一份）。</li>
     * </ul>
     */
    public static final boolean TRAIL_SKIP_UNSWEPT = false;

    /** 要不要画"当前这一份"（实心那把刀）。关掉就只剩拖影。 */
    public static final boolean DRAW_LIVE_COPY = true;

    /** 渲染时用不用"全亮"（不受世界光照影响）。刀光类效果一般开着更亮眼。 */
    public static final boolean FULL_BRIGHT = false;

    /**
     * 【调试】在<b>手部位置</b>画出傀儡本体坐标系的三根轴，用来手工对角度：
     * <b>+X 红</b>（左右）、<b>+Y 绿</b>（上）、<b>+Z 蓝</b>（傀儡<i>背后</i>，所以前方是蓝轴的反方向）。
     * 对完角度记得关掉。
     */
    public static final boolean DEBUG_AXES = false;

    /**
     * 三段动作。<b>顺序是 ③ → ① → ②</b>（横扫 → 上挑 → 下劈）。
     * <p>
     * <b>"整体抬高/放低"就是调 {@code base.x}</b>：它把整段动作绕傀儡的左右轴旋转，不改动作幅度。
     * 俯仰的读数：<b>0° = 刀朝上，−90° = 刀朝前，−180° = 刀朝下，+90° = 刀朝后</b>。
     * （所以"刀往地上扎"= 俯仰接近 −135° ~ −180°；把它往正的方向调 = 抬起来。）
     * <p>
     * 竖直面内的动作（① 上挑、② 下劈）用 {@code base.y} 决定"刀面朝哪一侧"，
     * 不要用 {@code base.z} —— 欧拉角耦合下它的含义会随 {@code base.x} 变化，只有 ③ 那种
     * "先把刀放平到前方"的用法里，{@code z} 才是绕刀身自转。
     */
    public static final Stage[] STAGES = {
            // ③ 横扫：base 先 x=−90 把刀放平到正前方，再用 z=90 绕刀身自转把刀面竖立
            new Stage("③ 横扫 右下→左上", 7, Ease.OUT,
                    new Keyframe(90F, 0F, 0F, 0.15F, 1.10F),
                    new Keyframe(-90F, 0F, 0F, 0.55F, 1.45F),
                    new Pose(180F, 90F, 90F),
                    6, 0.12F),
            // ① 上挑：base.x 抬到 −45（整体上移），动作 = 总俯仰 −95° → 0°，
            //    即"从前方偏下挑到头顶"，不会往地上扎
            new Stage("① 上挑", 7, Ease.OUT,
                    new Keyframe(-40F, 0F, 0F, 0.20F, 1.15F),
                    new Keyframe(40F, 0F, -135F, 0.60F, 1.50F),
                    new Pose(0F, -20F, 0F),
                    6, 0.12F),
            // ② 下劈：base.y 把刀面转到侧向，动作 = 总俯仰 +45° → −70°
            //    即"高举在身后 → 劈到正前方偏上"，全程在水平面之上
            new Stage("② 下劈", 4, Ease.IN,
                    new Keyframe(0F, 0F, 10F, 0.50F, 1.50F),
                    new Keyframe(0F, 0F, 90F, 0.35F, 1.30F),
                    new Pose(180F, 0F, -180F),
                    5, 0.10F),
    };

    // ------------------------------------------------------------------ 派生量

    public static int stageCount() {
        return STAGES.length;
    }

    /** 取第 index 段；越界返回 null。 */
    public static Stage stage(int index) {
        return index >= 0 && index < STAGES.length ? STAGES[index] : null;
    }

    /** 最长的一段有多久（清理残留状态时要用）。 */
    public static int maxTicks() {
        int max = 0;
        for (Stage s : STAGES) {
            max = Math.max(max, s.ticks());
        }
        return max;
    }

    private GolemComboStages() {
    }
}
