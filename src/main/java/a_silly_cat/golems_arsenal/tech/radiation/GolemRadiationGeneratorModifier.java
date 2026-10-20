package a_silly_cat.golems_arsenal.tech.radiation;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.base.upgrade.GolemUpgradeLevels;
import a_silly_cat.golems_arsenal.tech.energy.GolemEnergyProvider;
import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * 辐射发电机（3 级，占一个槽位）。**只发电，不做辐射源。**
 *
 * <p>傀儡站在辐射场里时，把环境剂量按 severity 换算成 FE 充进自己的电池：
 *
 * <pre>
 *   环境剂量 E   = IRadiationManager.INSTANCE.getRadiationLevel(golem)   // Sv/h，只读
 *   有效剂量     = E × (1 − 屏蔽值)                                      // 屏蔽来自辐射屏蔽升级
 *   severity     = clamp((log10(有效剂量) + 6) / 7, 0, max_severity)      // Mekanism 原公式
 *   发电 FE/t    = severity × fe_per_severity × (1 + 合金… 档位系数 × step)
 * </pre>
 *
 * <p><b>干净环境严格为 0</b>，靠两道闸：
 * <ol>
 *   <li>{@link Config#RADIATION_MIN_DOSE}：环境剂量低于它就一分钱不发。
 *       实测 Mekanism 的干净环境基线是 {@code 1.0e-7}（{@code DefaultRadiationEntity} 里的常量），
 *       而默认阈值 1.0e-4 比它高三个数量级，所以"野外打架"永远是 0。</li>
 *   <li>severity 公式本身在低剂量下趋近 0。</li>
 * </ol>
 *
 * <p><b>为什么不写世界。</b>原设计的"傀儡变成辐射源"会用 {@code radiate(Coord4D, …)} 往世界
 * 里种辐射源，而 Mekanism 的源每秒只衰减 ×0.9995、且没有清除剂——傀儡是跟着玩家满地图走的实体，
 * 它走过的每条路都会留下长达十小时以上的污染，多人服里后续玩家会不知情地暴毙。
 * 本实现只调 {@code getRadiationLevel}（只读），物理上不可能污染任何地方。
 *
 * <p><b>屏蔽升级已删除。</b>"把辐射当燃料消耗掉"本身就替代了屏蔽的意图——傀儡不是挡住辐射，
 * 而是把周围那点污染烧掉换电。所以本类不再有任何 {@code (1 − 屏蔽值)} 的折损。
 */
public class GolemRadiationGeneratorModifier extends GolemModifier {

    /** 等级上限：三档的系数是 1 / 2 / 4，满档 = 7（编译期常量，同时夹住同档重复装）。 */
    public static final int MAX_LEVEL = 7;

    /** 修饰符 id（{@code GolemUpgradeLevels} 用它读等级）。 */
    public static final String ID = "golems_arsenal:radiation_generator";

    /** 结算节拍：20 tick，和 W 罐 / 备用能源一致。 */
    private static final int INTERVAL = 20;

    /** 各档的"档位系数 − 1"：1 级 → 1，2 级 → 2，4 级 → 4。 */
    private static final double[] WEIGHTED_BONUS = {0.0, 1.0, 2.0, 4.0};

    /** 小数部分的进位暂存（FE 是整数，但每 tick 的发电量常常不足 1）。 */
    private static final String PENDING_KEY = "GolemsArsenalRadiationPending";

    /**
     * 傀儡实际吸收的环境剂量占总量的比例（1%）。
     *
     * <p>它决定了"站在辐射区里会不会慢慢被照伤"：<b>必须远小于 1</b>，否则傀儡这个会跟着玩家
     * 满地图走的实体会在自家废料库旁边持续掉血；但也<b>不能是 0</b>，否则屏蔽升级就完全不影响
     * 傀儡自身，退化成纯发电油门。1% 配合每 20 tick 一次的节拍，让傀儡在熔毁级辐射场里的
     * 剂量累积慢到几乎无害，同时保留"屏蔽确实在挡东西"的语义。
     */
    private static final double ABSORBED_FRACTION = 0.01;

    public GolemRadiationGeneratorModifier() {
        super(StatFilterType.HEALTH, MAX_LEVEL);
    }

    @Override
    public Component getTooltip(int level) {
        return Component.translatable("upgrade.golems_arsenal.radiation_generator")
                .withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        return List.of(Component.translatable("upgrade.golems_arsenal.radiation_generator.desc",
                        Math.round(generationOf(level, Config.RADIATION_MAX_SEVERITY.get())),
                        Config.RADIATION_MIN_DOSE.get())
                .withStyle(ChatFormatting.GREEN));
    }

    // ------------------------------------------------------------------
    // 曲线
    // ------------------------------------------------------------------

    private static double weightedBonus(int level) {
        if (level <= 0) {
            return 0.0;
        }
        return WEIGHTED_BONUS[Math.min(level, WEIGHTED_BONUS.length - 1)];
    }

    /** 给定等级与 severity 的发电量（FE/t）。severity 已在调用方夹过上限。 */
    public static double generationOf(int level, double severity) {
        if (level <= 0 || severity <= 0.0) {
            return 0.0;
        }
        double multiplier = 1.0 + weightedBonus(level) * Config.RADIATION_STEP.get();
        return severity * Config.RADIATION_FE_PER_SEVERITY.get() * multiplier;
    }

    /**
     * Mekanism 的 severity 公式。dose 单位 Sv/h；{@code 5.0e-5}（约 50 µSv/h）恰好在 0.1 附近，
     * 也就是社区实测的"开始掉血"线——两个独立来源互相印证，见设计文档 §4.3。
     */
    public static double severityOf(double dose) {
        if (dose <= 0.0) {
            return 0.0;
        }
        double severity = (Math.log10(dose) + 6.0) / 7.0;
        return Math.max(0.0, Math.min(Config.RADIATION_MAX_SEVERITY.get(), severity));
    }

    // ------------------------------------------------------------------
    // 傀儡侧
    // ------------------------------------------------------------------

    /** 环境剂量（Sv/h）。低于配置阈值一律返回 0，让"干净环境"在源头就是零。 */
    public static double environmentalDose(AbstractGolemEntity<?, ?> golem) {
        double dose = radiationLevel(golem);
        return dose < Config.RADIATION_MIN_DOSE.get() ? 0.0 : dose;
    }

    // ------------------------------------------------------------------
    // 与 Mekanism 的接触面：全部走反射
    //
    // 本类的注册是**无条件**的（修饰符注册不依赖 Mekanism），所以它的常量池里不该出现任何
    // mekanism.* 的类——一旦出现，任何在不装 MEK 的环境里执行到这些方法的路径都会
    // NoClassDefFoundError。调用点目前都被门控住了，但把可选模组 API 直接写进常量池是隐性债。
    // 反射把这条依赖变成"查不到就当作没有辐射"，代价只是每个静态字段一次解析。
    //
    // 对照：真正只在装了 MEK 时才会被加载的类（RadiationEntityProvider、以及
    // GolemRadiationAttachHandler 里的引用点）不需要这么做，直接用更清晰。
    // ------------------------------------------------------------------

    private static final Class<?> RADIATION_MANAGER_CLASS = resolveClass("mekanism.api.radiation.IRadiationManager");
    private static final Field MANAGER_FIELD = resolveField(RADIATION_MANAGER_CLASS, "INSTANCE");
    private static final Method RADIATION_LEVEL_METHOD =
            resolveMethod(RADIATION_MANAGER_CLASS, "getRadiationLevel", Entity.class);
    private static final Method RADIATE_METHOD =
            resolveMethod(RADIATION_ENTITY_CLASS(), "radiate", double.class);
    private static final Field RADIATION_ENTITY_CAPABILITY = resolveField(CAPABILITIES_CLASS(), "RADIATION_ENTITY");

    private static double radiationLevel(AbstractGolemEntity<?, ?> golem) {
        if (MANAGER_FIELD == null || RADIATION_LEVEL_METHOD == null) {
            return 0.0;
        }
        try {
            Object value = RADIATION_LEVEL_METHOD.invoke(MANAGER_FIELD.get(null), golem);
            return value instanceof Double d ? d : 0.0;
        } catch (ReflectiveOperationException e) {
            return 0.0;
        }
    }

    /**
     * 把一份剂量灌进傀儡的 {@code IRadiationEntity} 能力。
     *
     * <p>{@code getCapability} 本身是 Forge 的泛型方法，取不到 {@code Capability} 对象就没法调用，
     * 所以这里先反射拿 {@code Capabilities.RADIATION_ENTITY} 这个静态字段，再反射调
     * {@code golem.getCapability(cap, null)}（Forge 的 {@code Entity#getCapability} 允许 side 为 null）。
     */
    private static void pushDose(AbstractGolemEntity<?, ?> golem, double absorbed) {
        if (RADIATION_ENTITY_CAPABILITY == null || RADIATE_METHOD == null) {
            return;
        }
        try {
            Object capability = RADIATION_ENTITY_CAPABILITY.get(null);
            Method getCapability = golem.getClass().getMethod("getCapability", Capability.class, Direction.class);
            Object optional = getCapability.invoke(golem, capability, null);
            if (optional instanceof LazyOptional<?> lazy) {
                lazy.resolve().ifPresent(handler -> {
                    try {
                        RADIATE_METHOD.invoke(handler, absorbed);
                    } catch (ReflectiveOperationException ignored) {
                        // 单次灌入失败无所谓，下个节拍会再试。
                    }
                });
            }
        } catch (ReflectiveOperationException ignored) {
            // 同上：静默失败，不刷屏。
        }
    }

    private static Class<?> CAPABILITIES_CLASS() {
        return resolveClass("mekanism.common.capabilities.Capabilities");
    }

    private static Class<?> RADIATION_ENTITY_CLASS() {
        return resolveClass("mekanism.api.radiation.capability.IRadiationEntity");
    }

    private static Class<?> resolveClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private static Field resolveField(Class<?> owner, String name) {
        if (owner == null) {
            return null;
        }
        try {
            Field f = owner.getField(name);
            return Modifier.isStatic(f.getModifiers()) ? f : null;
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static Method resolveMethod(Class<?> owner, String name, Class<?>... params) {
        if (owner == null) {
            return null;
        }
        try {
            return owner.getMethod(name, params);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /**
     * 傀儡当前的发电量（FE/t）。
     *
     * <p><b>屏蔽升级已删除</b>：原先这里会乘 {@code (1 − 屏蔽值)}，现在直接按环境剂量算。
     * 设计上"消耗辐射"本身就替代了屏蔽的意图——傀儡把辐射当燃料，而不是挡住它。
     */
    public static double generationRate(AbstractGolemEntity<?, ?> golem) {
        int level = GolemUpgradeLevels.levelOf(golem, ID);
        if (level <= 0) {
            return 0.0;
        }
        double dose = environmentalDose(golem);
        if (dose <= 0.0) {
            return 0.0;
        }
        return generationOf(level, severityOf(dose));
    }

    @Override
    public void onGolemSpawn(AbstractGolemEntity<?, ?> golem, int level) {
        if (!golem.level().isClientSide && !golem.getPersistentData().contains(PENDING_KEY)) {
            golem.getPersistentData().putDouble(PENDING_KEY, 0.0);
        }
    }

    @Override
    public void onAiStep(AbstractGolemEntity<?, ?> golem, int level) {
        if (golem.level().isClientSide || golem.tickCount % INTERVAL != 0) {
            return;
        }
        irradiate(golem);
        double perTick = generationRate(golem);
        if (perTick <= 0.0) {
            return;
        }
        CompoundTag data = golem.getPersistentData();
        double pending = data.getDouble(PENDING_KEY) + perTick * INTERVAL;
        int whole = (int) Math.floor(pending);
        data.putDouble(PENDING_KEY, pending - whole);
        if (whole <= 0) {
            return;
        }
        // 直接充进傀儡的 FE 池。下一次 chargeEquipment 就会把它分给武器。
        golem.getCapability(GolemEnergyProvider.CAPABILITY).ifPresent(storage ->
                storage.receiveEnergy(whole, false));
    }

    /**
     * 把**按比例削减过的**环境剂量灌回傀儡的 {@code IRadiationEntity}。
     *
     * <p>这个能力的用途不是"让傀儡受伤"，而是**记录傀儡身上累积了多少辐射**——
     * 将来"持续照射强化傀儡"那条设计要靠它当燃料槽读（见
     * {@code docs/MEK-ENERGY-RADIATION-DESIGN.md}）。所以灌入量由
     * {@link #ABSORBED_FRACTION} 压到 1%，实测平衡点约 5e-6 Sv/h，远低于 5e-5 的掉血线。
     *
     * <p>屏蔽升级删除后，这里不再乘 {@code (1 − 屏蔽值)}——傀儡把辐射当燃料，不再"挡"它。
     */
    private static void irradiate(AbstractGolemEntity<?, ?> golem) {
        double dose = environmentalDose(golem);
        if (dose <= 0.0) {
            return;
        }
        double absorbed = dose * ABSORBED_FRACTION;
        if (absorbed <= 0.0) {
            return;
        }
        pushDose(golem, absorbed);
    }
}
