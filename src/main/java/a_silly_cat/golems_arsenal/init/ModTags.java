package a_silly_cat.golems_arsenal.init;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

public final class ModTags {
    public static final TagKey<Item> LARGE_GOLEM_WEAPONS =
            TagKey.create(Registries.ITEM, new ResourceLocation("modulargolems", "large_golem_weapons"));

    /** Meme-upgrade items: applying one on an anvil adds the matching enchantment to the item. */
    public static final TagKey<Item> MEME_UPGRADES =
            TagKey.create(Registries.ITEM, new ResourceLocation(Golems_arsenal.MODID, "meme_upgrades"));

    /** Black Monkey upgrades (stance + stance sub): each installed one boosts the Shen Tong Staff. */
    public static final TagKey<Item> BLACK_MONKEY_UPGRADES =
            TagKey.create(Registries.ITEM, new ResourceLocation(Golems_arsenal.MODID, "black_monkey_upgrades"));

    /**
     * Special-move upgrades (the key blade spin is the first example). Effects check this tag, so
     * datapacks can register additional special moves by tagging their upgrade items here.
     */
    public static final TagKey<Item> SPECIAL_MOVE_UPGRADES =
            TagKey.create(Registries.ITEM, new ResourceLocation(Golems_arsenal.MODID,
                    "special_move_upgrades"));

    /**
     * 合金能源链（灌注 / 精英 / 终极三档）。三件都不占额外槽位，各自往同一个
     * {@code golems_arsenal:golem_energy} 修饰符上加 2 / 4 / 8 点等级。
     *
     * <p><b>前置是"上一档"，不是某个核心件</b>：灌注需要基础的"基础电量升级"
     * （见 {@link #ENERGY_BASE_UPGRADES}），精英需要灌注，终极需要精英。
     * 校验入口是 {@code AlloyChains.rejectionForAny}，所以数据包可以往这里追加自己的档位。
     */
    public static final TagKey<Item> ENERGY_ALLOY_UPGRADES =
            TagKey.create(Registries.ITEM, new ResourceLocation(Golems_arsenal.MODID, "energy_alloy_upgrades"));

    /**
     * 基础电量升级（合金能源链**最低档**的前置）。
     *
     * <p>刻意用标签而不是直接引用 {@code ModItems} 的具体物品：合金物品注册在
     * {@code MekanismCompat} 里，若让校验逻辑反过来引用那边，会踩到"类初始化尚未结束
     * 又回头引用自己"的死结。标签把两边彻底解耦，同时让数据包可以换前置。
     */
    public static final TagKey<Item> ENERGY_BASE_UPGRADES =
            TagKey.create(Registries.ITEM, new ResourceLocation(Golems_arsenal.MODID, "energy_base_upgrades"));

    /**
     * 合金**引导**链（灌注 / 精英 / 终极三档）。三件都不占额外槽位，各自往
     * {@code golems_arsenal:orbital_guidance} 修饰符上加 2 / 4 / 8 点等级。
     *
     * <p><b>这条链没有核心件</b>：它是 MEK 的附属联动，最低档就是"灌注"本身
     * （不需要任何前置）。所以逐级前置是：灌注（无前置）→ 精英需要灌注 → 终极需要精英。
     */
    public static final TagKey<Item> GUIDANCE_ALLOY_UPGRADES =
            TagKey.create(Registries.ITEM, new ResourceLocation(Golems_arsenal.MODID, "guidance_alloy_upgrades"));

    /**
     * Weapon trigger tags live in the {@code modulargolems} namespace (mirroring Modular Golems'
     * own tag style), so they are treated as part of the dependency's data and datapacks can
     * extend them the same way they extend Modular Golems tags.
     */
    public static final TagKey<Item> WEAPON_MAIN =
            TagKey.create(Registries.ITEM, new ResourceLocation("modulargolems", "weapon_main"));
    /** Main weapon upgrade, larger bonus tier: forge hammer (ancient forge). */
    public static final TagKey<Item> WEAPON_FORGE_HAMMER =
            TagKey.create(Registries.ITEM, new ResourceLocation("modulargolems", "weapon_forge_hammer"));
    /** Main weapon upgrade: flame swords leave a magic flame cloud on hit. */
    public static final TagKey<Item> WEAPON_FLAME_SWORD =
            TagKey.create(Registries.ITEM, new ResourceLocation("modulargolems", "weapon_flame_sword"));
    /** Atypical weapon upgrade: golem spears splash area damage around the target. */
    public static final TagKey<Item> WEAPON_SPEAR =
            TagKey.create(Registries.ITEM, new ResourceLocation("modulargolems", "weapon_spear"));
    /** Atypical weapon upgrade: the sculk golem scythe deals bonus damage. */
    public static final TagKey<Item> WEAPON_SCYTHE =
            TagKey.create(Registries.ITEM, new ResourceLocation("modulargolems", "weapon_scythe"));
    /** Ranged weapon upgrade: bows whose arrows fly faster (velocity bonus). */
    public static final TagKey<Item> WEAPON_RANGED_BOW =
            TagKey.create(Registries.ITEM, new ResourceLocation("modulargolems", "weapon_ranged_bow"));
    /** Ranged weapon upgrade: cannons (sonic cannon) gain a magic damage bonus while held. */
    public static final TagKey<Item> WEAPON_RANGED_CANNON =
            TagKey.create(Registries.ITEM, new ResourceLocation("modulargolems", "weapon_ranged_cannon"));

    private ModTags() {
    }
}
