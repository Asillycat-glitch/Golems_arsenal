package a_silly_cat.golems_arsenal.tech.upgrade;

import a_silly_cat.golems_arsenal.base.upgrade.GolemWeaponUpgradeItem;
import dev.xkmc.modulargolems.content.item.upgrade.IUpgradeItem;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.function.Supplier;

/**
 * **合金链**上的升级物品（能源链与引导链共用这一个类）。
 *
 * <p><b>不占槽位</b>：{@link #consumesSlot()} 返回 false，照 Modular Golems 自己的
 * {@code AddSlotTemplate} 写法。合金件是链上"前一档"的附属——玩家只为入口件付一个槽，
 * 之后每拿到一档就往上叠，槽位占用不再增加。三档也因此不需要互斥。
 *
 * <p><b>等级写在物品上</b>：构造时传入本档贡献的等级（2 / 4 / 8），与
 * {@code command_upgrade_1/2/3} 同一套机制（本家升级物品的等级是烘在物品上的，不是读 NBT）。
 *
 * <p><b>前置 = 上一档</b>，由两个标签表达：
 * <ul>
 *   <li>{@code alloyTag}：本档所属的合金标签（用来认出"这件属于哪条链"）</li>
 *   <li>{@code requiresTag}：本档要求的前置标签；{@code null} 表示**无前置**（链的入口档）</li>
 * </ul>
 * 于是"灌注（无前置）→ 精英（要灌注）→ 终极（要精英）"这种逐级解锁可以用同一套代码表达，
 * 不需要任何"核心件"。能源链的入口档则把 {@code requiresTag} 指向基础电量升级，
 * 保持它原有的"核心升级"语义。
 *
 * <p>另外仍然做**同档去重**：等级是"档位系数之和"，装两件灌注就是 2+2=4，会冒充精英档。
 */
public class AlloyChainUpgradeItem extends GolemWeaponUpgradeItem {

    private final TagKey<Item> alloyTag;
    /** 前置标签；{@code null} = 这是链的入口档，不需要前置。 */
    private final TagKey<Item> requiresTag;

    public AlloyChainUpgradeItem(Item.Properties properties, int level,
                                 Supplier<GolemModifier> modifier,
                                 TagKey<Item> alloyTag, TagKey<Item> requiresTag) {
        super(properties, level, modifier);
        this.alloyTag = alloyTag;
        this.requiresTag = requiresTag;
    }

    /** 合金件都不吃槽位。 */
    @Override
    public boolean consumesSlot() {
        return false;
    }

    /**
     * 跨链校验：{@code incoming} 属于哪条已知合金链，就按那条链的规则校验。
     * 返回 {@code null} 表示放行（含"不属于任何合金链"的情况，不拦别人的东西）。
     */
    public static Rejection rejectionForAny(Collection<? extends IUpgradeItem> installed,
                                            IUpgradeItem incoming,
                                            Collection<Chain> chains) {
        for (Chain chain : chains) {
            for (Tier tier : chain.tiers()) {
                if (!isTagged(incoming, tier.alloyTag())) {
                    continue;
                }
                if (tier.requiresTag() != null && !hasTagged(installed, tier.requiresTag())) {
                    return Rejection.MISSING_PREREQUISITE;
                }
                for (IUpgradeItem existing : installed) {
                    if (existing == incoming) {
                        return Rejection.DUPLICATE_TIER;
                    }
                }
                return null;
            }
        }
        return null;
    }

    /** 该升级是否带某个标签（按物品标签判定，数据包可扩展）。 */
    public static boolean isTagged(IUpgradeItem upgrade, TagKey<Item> tag) {
        return upgrade instanceof Item item && new ItemStack(item).is(tag);
    }

    /** 已安装列表里有没有带这个标签的物品。 */
    public static boolean hasTagged(Collection<? extends IUpgradeItem> installed, TagKey<Item> tag) {
        for (IUpgradeItem upgrade : installed) {
            if (isTagged(upgrade, tag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 链上的一档：本档标签 + 本档要求的前置标签（{@code null} = 无前置，即入口档）。
     */
    public record Tier(TagKey<Item> alloyTag, TagKey<Item> requiresTag) {
    }

    /**
     * 一条合金链：一串按顺序排列的档位。
     * 用记录把"这条链有哪些档、各要什么前置"绑在一起，让 {@code AlloyChains.ALL} 成为唯一真相来源。
     */
    public record Chain(String name, java.util.List<Tier> tiers) {
    }

    /** 拒绝理由。文案由调用方决定（升级界面 / 锻造台各自有提示渠道）。 */
    public enum Rejection {
        /** 缺少前置：还没装前一档（或能源链的核心件）。 */
        MISSING_PREREQUISITE,
        /** 同一档已经装过了。 */
        DUPLICATE_TIER
    }
}
