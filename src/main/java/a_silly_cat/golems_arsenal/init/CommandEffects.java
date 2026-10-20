package a_silly_cat.golems_arsenal.init;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 指令升级的九个「指令效果」。
 *
 * <h2>它们是增益/减益，不是一次性伤害</h2>
 * 触发时效果<b>挂到实体身上</b>并带时长，而不是当场结算一次伤害。
 * 具体挂给谁由效果的性质决定：
 * <ul>
 *   <li><b>增强傀儡自己</b>（暴击率、END 保底、增伤、追击、贯穿）→ 挂傀儡；</li>
 *   <li><b>削弱目标</b>（攻击削弱、破盾、物理易伤、抗性穿透）→ 挂被命中的目标。</li>
 * </ul>
 * 前一类是 {@link MobEffectCategory#BENEFICIAL}，后一类是 {@link MobEffectCategory#HARMFUL}，
 * 这样客户端会自动按增益/减益上色，玩家一眼能分清。
 *
 * <h2>强度写死在这里，时长跟等级走</h2>
 * 九个效果的强度是<b>设计值</b>，不是玩家该调的旋钮 —— 把它们做成配置项会让配置文件长出
 * 十七行只影响内部平衡的东西，而真正需要调的（等级上限、冷却、重抽间隔）反而被淹没。
 * 所以强度是下面的 {@code STR_*} 常量，每次调平衡改代码。
 * <p>
 * 时长则<b>跟随升级等级</b>，见 {@link #durationTicks(int, int)}：等级越高，
 * 获得的效果维持得越久。这样「等级」这一个数字同时决定两件事 ——
 * 一次能拥有几个、以及每个能维持多久。
 *
 * <h2>哪些能做成纯属性修饰符，哪些不能</h2>
 * 属性盘面（已核实）：{@code l2damagetracker} 提供 {@code crit_rate} / {@code crit_damage} /
 * {@code damage_reduction} / {@code damage_absorption}，本模组有 {@code end_restitution}；
 * 原版有 {@code ARMOR} / {@code ARMOR_TOUGHNESS} / {@code ATTACK_DAMAGE}。
 * <p>
 * <b>没有</b>「造成伤害乘区」这个属性，所以「增幅」和「贯穿」做不成纯属性效果 ——
 * 它们靠 {@link #AMPLIFY} / {@link #PIERCE} 两个标记，由 {@code GolemCommandModifier}
 * 在伤害事件里读取后处理。「追击」同理，它是一段延迟伤害。
 *
 * <h2>不改原版属性表</h2>
 * 本类只注册效果，不碰 {@code EntityAttributeModificationEvent}。
 * 需要的属性都已经由 {@link ModAttributes} 挂在傀儡实体类型上；拿不到时
 * {@link #safeModifier} 会跳过，效果仍然注册、仍然可见，只是没有数值。
 */
public final class CommandEffects {

    public static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, Golems_arsenal.MODID);
    // ---------------------------------------------------------------- 池子编号
    public static final int IDX_CRIT = 1;
    public static final int IDX_WEAKEN = 2;
    public static final int IDX_AMPLIFY = 3;
    public static final int IDX_SHRED = 4;
    public static final int IDX_PHYS_VULN = 5;
    public static final int IDX_MAGIC_VULN = 6;
    public static final int IDX_END = 7;
    public static final int IDX_EXTRA = 8;
    public static final int IDX_PIERCE = 9;

    /** 池子大小，同时也是等级上限。 */
    public static final int POOL_SIZE = 9;

    // ---------------------------------------------------------------- 时长

    /** 1 级时的基础时长（tick）。 */
    public static final int DURATION_BASE = 100;

    /** 每多一级加多少时长（tick）。 */
    public static final int DURATION_PER_LEVEL = 60;

    /**
     * 时长随等级增长。
     * <p>
     * 1 级 = {@value #DURATION_BASE} tick（5 秒），之后每级 +{@value #DURATION_PER_LEVEL} tick，
     * 所以 5 级 = 340 tick（17 秒）、9 级 = 580 tick（29 秒）。
     * 上限存在的意义只是别让它无限涨。
     */
    public static int durationTicks(int poolIndex, int level) {
        int lv = Math.max(1, level);
        return DURATION_BASE + DURATION_PER_LEVEL * (lv - 1);
    }

    // ---------------------------------------------------------------- 强度（设计值）

    /** 1 锐击：加多少暴击率。 */
    public static final double STR_CRIT_RATE = 0.25;
    /** 2 力竭：削目标多少攻击力。 */
    public static final double STR_WEAKEN_ATTACK = 2.0;
    /** 3 增幅：增伤比例。 */
    public static final double STR_AMPLIFY_BONUS = 0.2;
    /** 4 破盾：削目标多少护甲点（原版护甲上限 30）。 */
    public static final double STR_SHRED_ARMOR = 4.0;
    /** 5 物理易伤：削目标多少护甲韧性（上限 20）。 */
    public static final double STR_PHYS_VULN_TOUGHNESS = 2.0;
    /** 6 抗性穿透：削目标多少 {@code damage_reduction}（1.0 = 全剥）。 */
    public static final double STR_MAGIC_VULN_REDUCTION = 0.2;
    /** 7 余烬保底：加多少 {@code end_restitution}（总上限 0.8）。 */
    public static final double STR_END_RESTITUTION = 0.3;
    /** 8 追击：追击段占主伤害的比例。 */
    public static final double STR_EXTRA_RATIO = 0.3;

    // ---------------------------------------------------------------- 挂傀儡（增益）

    /**
     * 1 暴击：提升 {@code l2damagetracker:crit_rate}，由 {@code GolemCritHandler} 照旧判定与乘算。
     * <p>
     * 用 {@code ADDITION} 而不是 {@code MULTIPLY_TOTAL}：{@code crit_rate} 的基值是 0，
     * 乘任何数都还是 0；而且它被钳在 0..1，加法语义正好是"增加几个百分点的暴击率"。
     */
    public static final RegistryObject<MobEffect> CRIT = EFFECTS.register("command_crit",
            () -> new SimpleEffect(MobEffectCategory.BENEFICIAL, 0xFFB020,
                    attr("l2damagetracker", "crit_rate"),
                    "a1f0c834-7d52-4b6e-9c18-2e5a7b0d3f61",
                    STR_CRIT_RATE, AttributeModifier.Operation.ADDITION));

    /** 7 END 保底：提升 {@code end_restitution}，缩小目标减伤能砍掉的比例。 */
    public static final RegistryObject<MobEffect> END_FLOOR = EFFECTS.register("command_end_floor",
            () -> new SimpleEffect(MobEffectCategory.BENEFICIAL, 0x7FE0C0,
                    ModAttributes.END_RESTITUTION.get(),
                    "b2c1d945-8e63-4c7f-ad29-3f6b8c1e4a72",
                    STR_END_RESTITUTION, AttributeModifier.Operation.ADDITION));

    /**
     * 3 增伤：傀儡的攻击伤害按比例提高。
     * <p>
     * <b>没有属性可用</b>，所以这个效果本身不带修饰符 —— 它由
     * {@code GolemCommandModifier} 在命中时检查「傀儡身上有没有这个效果」然后乘算。
     */
    public static final RegistryObject<MobEffect> AMPLIFY = EFFECTS.register("command_amplify",
            () -> new SimpleEffect(MobEffectCategory.BENEFICIAL, 0xFF7043, null, null, 0.0,
                    AttributeModifier.Operation.ADDITION));

    /**
     * 9 穿透抗性：无视目标的「受到伤害」乘区。
     * <p>
     * 同样没有属性可用（`damage_reduction` 那条路是<b>目标</b>的减伤，不是攻击者的穿透），
     * 所以由 {@code GolemCommandModifier} 在命中时读取后，把目标那一层的减伤按比例跳过。
     */
    public static final RegistryObject<MobEffect> PIERCE = EFFECTS.register("command_pierce",
            () -> new SimpleEffect(MobEffectCategory.BENEFICIAL, 0x9C6BFF, null, null, 0.0,
                    AttributeModifier.Operation.ADDITION));

    /**
     * 8 追击：延迟一 tick 的追加伤害段（用 {@code command_extra} 伤害类型，无视无敌帧）。
     * <p>
     * 不是属性，靠标记 + 伤害类型实现。
     */
    public static final RegistryObject<MobEffect> EXTRA_HIT = EFFECTS.register("command_extra_hit",
            () -> new SimpleEffect(MobEffectCategory.BENEFICIAL, 0xE0E0E0, null, null, 0.0,
                    AttributeModifier.Operation.ADDITION));

    // ---------------------------------------------------------------- 挂目标（减益）

    /** 2 攻击削弱：削目标的攻击力。分层，可被力量药水对冲。 */
    public static final RegistryObject<MobEffect> ATTACK_WEAKNESS = EFFECTS.register("command_attack_weakness",
            () -> new SimpleEffect(MobEffectCategory.HARMFUL, 0x8B4A4A,
                    Attributes.ATTACK_DAMAGE, "c3d2ea56-9f74-4d80-be3a-4a7c9d2f5b83",
                    -STR_WEAKEN_ATTACK, AttributeModifier.Operation.ADDITION));

    /** 4 破盾：削目标护甲点。 */
    public static final RegistryObject<MobEffect> SHIELD_BREAK = EFFECTS.register("command_shield_break",
            () -> new SimpleEffect(MobEffectCategory.HARMFUL, 0x9B6BD6,
                    Attributes.ARMOR, "d4e3fb67-a085-4e91-cf4b-5b8dae306c94",
                    -STR_SHRED_ARMOR, AttributeModifier.Operation.ADDITION));

    /** 5 物理易伤：削目标护甲韧性（物理减伤公式里的独立一项）。 */
    public static final RegistryObject<MobEffect> PHYS_VULN = EFFECTS.register("command_physical_vulnerability",
            () -> new SimpleEffect(MobEffectCategory.HARMFUL, 0xC06A3A,
                    Attributes.ARMOR_TOUGHNESS, "e5f40c78-b196-4fa2-d05c-6c9ebf417da5",
                    -STR_PHYS_VULN_TOUGHNESS, AttributeModifier.Operation.ADDITION));

    /** 6 抗性穿透：削目标的「受到伤害」乘区，所以对所有走 l2 管线的伤害都有效。 */
    public static final RegistryObject<MobEffect> MAGIC_VULN = EFFECTS.register("command_magic_vulnerability",
            () -> new SimpleEffect(MobEffectCategory.HARMFUL, 0x7A5AD6,
                    attr("l2damagetracker", "damage_reduction"),
                    "f6051d89-c2a7-40b3-e16d-7dafc0528eb6",
                    -STR_MAGIC_VULN_REDUCTION, AttributeModifier.Operation.ADDITION));

    private CommandEffects() {
    }

    public static void register(IEventBus modEventBus) {
        EFFECTS.register(modEventBus);
    }

    /** 池子编号 → 效果。编号越界返回 {@code null}。 */
    public static MobEffect byIndex(int index) {
        return switch (index) {
            case IDX_CRIT -> CRIT.get();
            case IDX_WEAKEN -> ATTACK_WEAKNESS.get();
            case IDX_AMPLIFY -> AMPLIFY.get();
            case IDX_SHRED -> SHIELD_BREAK.get();
            case IDX_PHYS_VULN -> PHYS_VULN.get();
            case IDX_MAGIC_VULN -> MAGIC_VULN.get();
            case IDX_END -> END_FLOOR.get();
            case IDX_EXTRA -> EXTRA_HIT.get();
            case IDX_PIERCE -> PIERCE.get();
            default -> null;
        };
    }

    /** 这个编号的效果是不是挂给目标的（减益）；否则挂给傀儡。 */
    public static boolean targetsEnemy(int index) {
        return index == IDX_WEAKEN || index == IDX_SHRED
                || index == IDX_PHYS_VULN || index == IDX_MAGIC_VULN;
    }

    /**
     * 造一个属性修饰符，拿不到属性时返回 {@code null}。
     * <p>
     * 前置缺失（比如别的整合包没带 l2damagetracker）时让修饰符干脆不挂，
     * 比在建效果<b>实例</b>时抛异常安全 —— 那会把整个效果注册表带崩。
     */
    private static AttributeModifier safeModifier(Attribute attribute, String uuid, double amount,
                                                 AttributeModifier.Operation operation) {
        if (attribute == null) {
            return null;
        }
        return new AttributeModifier(java.util.UUID.fromString(uuid), "command_effect", amount, operation);
    }

    private static Attribute attr(String namespace, String path) {
        return ForgeRegistries.ATTRIBUTES.getValue(new ResourceLocation(namespace, path));
    }

    /**
     * 通用效果实现：它本身只负责「带一个属性修饰符」和「配色」。
     * <p>
     * 不带属性的那几个（增伤 / 穿透 / 追击）传 {@code attribute = null}，
     * 它们由 {@code GolemCommandModifier} 读标记来处理。
     */
    private static final class SimpleEffect extends MobEffect {
        private SimpleEffect(MobEffectCategory category, int colour,
                             Attribute attribute, String uuid, double amount,
                             AttributeModifier.Operation operation) {
            super(category, colour);
            AttributeModifier modifier = safeModifier(attribute, uuid, amount, operation);
            if (modifier != null) {
                addAttributeModifier(attribute, modifier.getId().toString(), amount, operation);
            }
        }
    }
}
