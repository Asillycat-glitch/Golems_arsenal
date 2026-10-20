package a_silly_cat.golems_arsenal.init;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 本模组的药水效果。
 *
 * <h2>设计立场：只做「改状态」，不做「改结果」</h2>
 * 这里的每一个效果都作用于目标身上的<b>属性</b>，因此都留痕、可读、可净化、可对冲 ——
 * 是一条普通 debuff，而不是穿透。这条立场来自 {@link #ARMOR_SHRED} 的注释里记的那次教训：
 * 早先版本顺手削过 {@code l2damagetracker:damage_reduction}（那是"改结果"），已被移除。
 * 例外只有一个，见 {@link #MAGIC_VULNERABILITY}：原版压根没有"魔法抗性"这个属性，
 * 想让魔法易伤真的成立，只能用 l2 那条乘区，这是被现实逼出来的选择。
 */
public final class GolemEffects {
    public static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, Golems_arsenal.MODID);

    /** Charge: grants proportional protection/resistance piercing to its holder's attacks. */
    public static final RegistryObject<MobEffect> CHARGE = EFFECTS.register("charge",
            ChargeEffect::new);

    /**
     * 破甲（armor shred）：削减目标的<b>原版护甲点</b> {@code Attributes.ARMOR}。
     * <p>
     * 用 ADDITION 负值，按效果 amplifier 分层（原版会把效果修饰符乘以 {@code amplifier + 1}，
     * 见 {@code MobEffect#getAttributeModifierValue}）。{@code generic.armor} 是
     * {@code RangedAttribute(0.0, 0.0, 30.0)} 并被钳制，所以上限只是"把护甲剥光"，不会变负。
     * <p>
     * 代价要清楚：{@code refine_meror_*} 三件套的 {@code damage_reduction}（×0.8³ = ×0.512）
     * <b>完全碰不到</b>。要测出那层到底削了多少，用 {@code DamageReductionProbe} 只读诊断。
     */
    public static final RegistryObject<MobEffect> ARMOR_SHRED = EFFECTS.register("armor_shred",
            ArmorShredEffect::new);

    /**
     * 攻击削弱（手套第 2 格）：削减目标的<b>攻击力</b> {@code Attributes.ATTACK_DAMAGE}。
     * <p>
     * 同样是 ADDITION 负值、按 amplifier 分层。这是最"正统"的一种削弱：目标身上的属性面板
     * 会如实变低，可被净化、可被对冲。
     */
    public static final RegistryObject<MobEffect> ATTACK_WEAKNESS = EFFECTS.register("attack_weakness",
            AttackWeaknessEffect::new);

    /**
     * 物理易伤（手套第 6 格）：削减目标的<b>护甲韧性</b> {@code Attributes.ARMOR_TOUGHNESS}。
     * <p>
     * 为什么不是直接加"受到物理伤害 %"：原版没有这个属性，而 {@code damage_reduction} 那条
     * 路按本模组的立场不该碰（见类注释）。护甲韧性是<b>物理减伤公式里的独立一项</b>
     * （{@code damage = damage * (1 - min(20, max(armor/5, armor - damage/(2+toughness/4)))/25)}），
     * 削它会让所有物理来源的伤害一起变高，且完全是标准属性减益：留痕、可净化、可对冲。
     * <p>
     * 代价同样要清楚：它对"无视护甲的伤害"完全无效（那种伤害本来就不看韧性）。
     */
    public static final RegistryObject<MobEffect> PHYSICAL_VULNERABILITY = EFFECTS.register(
            "physical_vulnerability", () -> new ToughnessShredEffect(0xC06A3A));

    /**
     * 魔法易伤（手套第 7 格）：走 {@code l2damagetracker:damage_reduction} 的负值。
     * <p>
     * 这是类注释里说的那个<b>唯一例外</b>：原版没有"魔法抗性"属性，而 {@code damage_reduction}
     * 正是 l2 管线里"受到伤害的乘区"那一项，也只有它能把"魔法伤害打得更疼"这件事表达出来。
     * 仍然做成标准属性修饰符（不是一次性改伤害数值），所以它同样留痕、可读、可净化。
     * <p>
     * 前置缺失（查不到那个属性）时不挂修饰符：效果仍然注册、仍然可见，只是没有数值，
     * 不会因为少一个前置就把整个注册表带崩。
     */
    public static final RegistryObject<MobEffect> MAGIC_VULNERABILITY = EFFECTS.register(
            "magic_vulnerability", () -> new DamageReductionShredEffect(0x7A5AD6));

    /** 每层削减的护甲韧性；韧性是 {@code RangedAttribute(0, 0, 20)}，所以这个量级足够明显。 */
    private static final double TOUGHNESS_PER_LEVEL = 2.0;

    private GolemEffects() {
    }

    public static void register(IEventBus modEventBus) {
        EFFECTS.register(modEventBus);
    }

    private static final class ChargeEffect extends MobEffect {
        private ChargeEffect() {
            super(MobEffectCategory.BENEFICIAL, 0x55FFFF);
        }
    }

    private static final class ArmorShredEffect extends MobEffect {
        /** 原版护甲点用的 UUID。 */
        private static final String ARMOR_UUID = "6e2c9f41-8b3a-4d75-a0c8-7f19d5b42e60";
        /**
         * 每层削减的护甲点。3 件 {@code refine_meror_*} 名义上 13+18+10=41 点，实际被钳到 30，
         * 所以 5 层 = -15 点正好剥掉一半。
         */
        private static final double ARMOR_PER_LEVEL = 3.0;

        private ArmorShredEffect() {
            super(MobEffectCategory.HARMFUL, 0x9B6BD6);
            // 静态字段，任何加载阶段都拿得到，不需要注册表查找。
            addAttributeModifier(Attributes.ARMOR, ARMOR_UUID, -ARMOR_PER_LEVEL,
                    AttributeModifier.Operation.ADDITION);
        }
    }

    private static final class AttackWeaknessEffect extends MobEffect {
        private static final String ATTACK_UUID = "3f7b1d92-5c48-4e0a-9b21-6d8e0f4a7c15";

        private AttackWeaknessEffect() {
            super(MobEffectCategory.HARMFUL, 0x8B4A4A);
            addAttributeModifier(Attributes.ATTACK_DAMAGE, ATTACK_UUID, -1.0,
                    AttributeModifier.Operation.ADDITION);
        }
    }

    private static final class ToughnessShredEffect extends MobEffect {
        private static final String TOUGHNESS_UUID = "b41c8e07-2d95-4a63-8f10-5e7c9a3b6d28";

        private ToughnessShredEffect(int colour) {
            super(MobEffectCategory.HARMFUL, colour);
            addAttributeModifier(Attributes.ARMOR_TOUGHNESS, TOUGHNESS_UUID, -TOUGHNESS_PER_LEVEL,
                    AttributeModifier.Operation.ADDITION);
        }
    }

    private static final class DamageReductionShredEffect extends MobEffect {
        /** 每层削减的「受到伤害」乘区。 */
        private static final double REDUCTION_PER_LEVEL = 0.2D;

        private DamageReductionShredEffect(int colour) {
            super(MobEffectCategory.HARMFUL, colour);
            Attribute reduction = damageReduction();
            if (reduction != null) {
                addAttributeModifier(reduction, "c92f5a68-7e34-4b01-9d5c-1a8f3e6b0d47",
                        -REDUCTION_PER_LEVEL, AttributeModifier.Operation.ADDITION);
            }
        }

        /**
         * 运行时查 {@code l2damagetracker:damage_reduction}。
         * <p>
         * 不用静态字段缓存：这个查询发生在效果<b>实例构造</b>时（注册表填充阶段），
         * 那时 Forge 的属性注册表已经建好了，所以查得到；但如果哪天前置缺失，
         * 返回 null 让修饰符干脆不挂，比抛异常安全。
         */
        private static Attribute damageReduction() {
            return ForgeRegistries.ATTRIBUTES.getValue(
                    new ResourceLocation("l2damagetracker", "damage_reduction"));
        }
    }
}
