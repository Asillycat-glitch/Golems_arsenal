package a_silly_cat.golems_arsenal.tech.upgrade;

import a_silly_cat.golems_arsenal.tech.energy.GolemEnergyProvider;
import a_silly_cat.golems_arsenal.tech.energy.GolemEnergyTiers;
import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.item.upgrade.IUpgradeItem;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import dev.xkmc.modulargolems.content.modifier.base.ModifierInstance;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;

import java.util.Collection;
import java.util.List;

/**
 * 电量升级（基础档）。它同时是整条合金能源链的**根**与前置：
 * <ul>
 *   <li>自身 max 1 级：装一件就是 1 级，多加不叠加。占一个升级槽。</li>
 *   <li>三件合金能源升级（{@code infused/elite/ultimate_alloy_energy_upgrade}）是它的附属，
 *       {@code consumesSlot() == false}，各自往**同一个**修饰符上加 2 / 4 / 8 点等级。</li>
 * </ul>
 *
 * <p><b>等级即"装了几档"。</b>Modular Golems 的 {@code GolemMaterial.collectModifiers} 会把同一
 * 修饰符的等级求和并封顶到 {@link #MAX_LEVEL}，所以：
 *
 * <pre>
 *   总等级 L = 1（基础）+ 2（灌注）+ 4（精英）+ 8（终极）      满配 L = 15
 *   档位     = 1 → BASIC，3 → ADVANCED，7 → ELITE，15 → ULTIMATE
 * </pre>
 *
 * <p><b>数值来源</b>：容量与带宽统一由 {@link GolemEnergyTiers} 按总等级解析。装了 Mekanism 时
 * 镜像对应的能量立方档：容量取立方存储的<b>一半</b>，带宽取立方的<b>完整输出</b>（按“全部装备
 * 合计的 FE/t”解释）；没有 Mekanism 时只有基础档，退回 config 里的 fallback 值。
 * 这样 MEK 那边的立方配置改了，傀儡这边自动跟随，不需要再维护一套 step 配置。
 *
 * <p>该修饰符本身**不注册**在 Modular Golems 的 Registrate 注册表里（和合金链同一个做法），
 * 自己提供 tooltip 文本，避免创造栏代码去解析一个不存在的注册 id。
 */
public class GolemEnergyModifier extends GolemModifier {

    /**
     * 等级上限 = 1 + 2 + 4 + 8。合金三件各贡献一个 2 的幂次，加起来正好是满配。
     * 改小会让合金装不上（{@code collectModifiers} 会封顶），改大则等于放开重复堆叠。
     */
    public static final int MAX_LEVEL = 15;

    public GolemEnergyModifier() {
        super(StatFilterType.HEALTH, MAX_LEVEL);
    }

    @Override
    public Component getTooltip(int level) {
        return Component.translatable("upgrade.golems_arsenal.golem_energy").withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        return List.of(Component.translatable("upgrade.golems_arsenal.golem_energy.desc")
                .withStyle(ChatFormatting.GREEN));
    }

    @Override
    public void onGolemSpawn(AbstractGolemEntity<?, ?> entity, int level) {
        update(entity, totalLevel(entity));
    }

    @Override
    public void onAiStep(AbstractGolemEntity<?, ?> entity, int level) {
        update(entity, totalLevel(entity));
    }

    private static void update(AbstractGolemEntity<?, ?> entity, int totalLevel) {
        entity.getCapability(GolemEnergyProvider.CAPABILITY).ifPresent(storage ->
                ((a_silly_cat.golems_arsenal.tech.energy.GolemEnergyStorage) storage)
                        .setCapacity(capacityForLevel(totalLevel)));
    }

    // ------------------------------------------------------------------
    // 等级读取
    // ------------------------------------------------------------------

    /**
     * 傀儡身上 {@code golem_energy} 修饰符的**总等级**（基础档 + 合金档之和，封顶 {@link #MAX_LEVEL}）。
     *
     * <p>直接读 {@code getModifiers()}：那是 Modular Golems 在
     * {@code GolemMaterial.collectModifiers} 里算好的"求和并封顶"结果，也正是引擎实际使用的值，
     * 所以不必自己再遍历一遍物品。
     *
     * <p><b>为什么不用 {@code entity.getUpgrades()}：</b>实体侧那个方法返回的是
     * {@code List<Item>}（原始物品），拿不到 {@code IUpgradeItem} 的修饰符实例；
     * 而收纳态 {@code GolemHolder.getUpgrades(stack)} 返回 {@code List<IUpgradeItem>}。
     * 两套 API 形状不同，所以实体走修饰符、物品走 {@link #levelOfUpgrades}，
     * 但两者最终都归到同一个 {@link #capacityForLevel}，容量在两种形态下必然一致。
     */
    public static int totalLevel(AbstractGolemEntity<?, ?> entity) {
        return Math.min(MAX_LEVEL, entity.getModifiers().entrySet().stream()
                .filter(entry -> entry.getKey() instanceof GolemEnergyModifier)
                .mapToInt(java.util.Map.Entry::getValue)
                .sum());
    }

    /** 收纳态（物品形态）的傀儡：从 holder 的升级列表读同一套总等级。 */
    public static int levelOfUpgrades(Collection<? extends IUpgradeItem> upgrades) {
        int level = 0;
        for (IUpgradeItem upgrade : upgrades) {
            for (ModifierInstance instance : upgrade.get()) {
                if (instance.mod() instanceof GolemEnergyModifier) {
                    level += instance.level();
                }
            }
        }
        return Math.min(level, MAX_LEVEL);
    }

    // ------------------------------------------------------------------
    // 容量
    // ------------------------------------------------------------------

    /**
     * 当前总等级对应的容量（FE）。未装基础电量升级时等级为 0，容量为 0
     * （保持原有"没升级就没电池"的行为）。
     */
    public static int capacityForLevel(int totalLevel) {
        return GolemEnergyTiers.forLevel(totalLevel).capacityFe();
    }

    /** 傀儡的当前容量上限（只读，给 HUD / 调试用）。 */
    public static int capacityOf(AbstractGolemEntity<?, ?> entity) {
        return capacityForLevel(totalLevel(entity));
    }
}
