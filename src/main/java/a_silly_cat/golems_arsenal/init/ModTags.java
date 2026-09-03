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
