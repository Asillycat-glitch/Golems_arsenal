package a_silly_cat.golems_arsenal.tech.upgrade;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.init.ModItems;
import a_silly_cat.golems_arsenal.init.GolemEffects;
import dev.xkmc.l2damagetracker.contents.attack.CreateSourceEvent;
import dev.xkmc.l2damagetracker.contents.damage.DefaultDamageState;
import dev.xkmc.modulargolems.content.item.upgrade.IUpgradeItem;
import dev.xkmc.modulargolems.content.core.GolemStatType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.AttributeGolemModifier;
import dev.xkmc.modulargolems.content.modifier.base.ModifierInstance;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.RegistryManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Level-based tech upgrade. Only grants universal attributes (armor, armor toughness, fixed HP regen);
 * all combat/survival effects are provided by the energy weapons, whose power scales with the
 * tech upgrade level installed on the golem.
 */
public class GolemEnergyTechModifier extends AttributeGolemModifier {
    public static final int MAX_LEVEL = 5;

    private static final ResourceKey<Registry<GolemStatType>> STAT_TYPES =
            ResourceKey.createRegistryKey(new ResourceLocation("modulargolems", "stat_type"));
    private static final TagKey<Item> TECH_UPGRADES_TAG =
            TagKey.create(Registries.ITEM, new ResourceLocation("golems_arsenal:tech_upgrades"));

    public GolemEnergyTechModifier() {
        super(MAX_LEVEL, buildEntries());
    }

    /** Per-level attribute bonuses, resolved lazily from Modular Golems' stat registry. */
    private static AttrEntry[] buildEntries() {
        List<AttrEntry> list = new ArrayList<>();
        list.add(new AttrEntry(() -> resolveStat("modulargolems", "armor"), () -> Config.TECH_ARMOR.get()));
        list.add(new AttrEntry(() -> resolveStat("modulargolems", "tough"), () -> Config.TECH_TOUGHNESS.get()));
        list.add(new AttrEntry(() -> resolveStat("modulargolems", "regen"), () -> Config.TECH_HP_REGEN.get()));
        return list.toArray(new AttrEntry[0]);
    }

    private static GolemStatType resolveStat(String namespace, String path) {
        ForgeRegistry<GolemStatType> registry = RegistryManager.ACTIVE.getRegistry(STAT_TYPES);
        return registry == null ? null : registry.getValue(new ResourceLocation(namespace, path));
    }

    @Override
    public Component getTooltip(int level) {
        return Component.translatable("upgrade.golems_arsenal.golem_energy_tech")
                .withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        List<MutableComponent> ans = new ArrayList<>(super.getDetail(level));
        ans.add(Component.translatable("upgrade.golems_arsenal.golem_energy_tech.desc_katana")
                .withStyle(ChatFormatting.GREEN));
        ans.add(Component.translatable("upgrade.golems_arsenal.golem_energy_tech.desc_hammer")
                .withStyle(ChatFormatting.GREEN));
        ans.add(Component.translatable("upgrade.golems_arsenal.golem_energy_tech.desc_bow")
                .withStyle(ChatFormatting.GREEN));
        return ans;
    }

    @Override
    public void modifySource(AbstractGolemEntity<?, ?> golem, CreateSourceEvent event, int value) {
        if (event.getResult() == null) return;
        MobEffectInstance charge = golem.getEffect(GolemEffects.CHARGE.get());
        if (charge == null) return;
        double chance = Math.min(1, Config.TECH_CHARGE_PIERCE_PER_LEVEL.get() * (charge.getAmplifier() + 1));
        if (chance > golem.getRandom().nextDouble()) {
            if (event.getResult().validState(DefaultDamageState.BYPASS_ARMOR)) {
                event.enable(DefaultDamageState.BYPASS_ARMOR);
            }
            if (event.getResult().validState(DefaultDamageState.BYPASS_MAGIC)) {
                event.enable(DefaultDamageState.BYPASS_MAGIC);
            }
        }
    }

    /**
     * Tech level (scaling number only): the sum of modifier levels of every installed upgrade in
     * {@code #golems_arsenal:tech_upgrades}. Used exclusively for percentage scaling
     * (attack damage, HP regen, projectile damage), never for unlocking weapon skills.
     */
    public static int levelOf(AbstractGolemEntity<?, ?> entity) {
        List<Item> upgrades = entity.getUpgrades();
        if (upgrades.isEmpty()) {
            // Fallback for golems without an upgrade list (e.g. migrated saves): count tech modifiers directly.
            return entity.getModifiers().entrySet().stream()
                    .filter(entry -> entry.getKey() instanceof GolemEnergyTechModifier)
                    .mapToInt(java.util.Map.Entry::getValue)
                    .sum();
        }
        int level = 0;
        for (Item item : upgrades) {
            if (!item.builtInRegistryHolder().is(TECH_UPGRADES_TAG)) {
                continue;
            }
            if (item instanceof IUpgradeItem upgrade) {
                for (ModifierInstance instance : upgrade.get()) {
                    level += instance.level();
                }
            }
        }
        return level;
    }

    /**
     * Whether the golem has an actual tech upgrade installed. This is the unlock flag for tech-gated
     * weapon skills (lightning chain, flat damage reduction, explosive arrows). It checks the tech
     * upgrade items themselves instead of the whole {@code tech_upgrades} tag, so items appended to
     * that tag by datapacks can never unlock the skills, and the basic energy storage upgrade does
     * not unlock them either.
     */
    public static boolean hasTechUpgrade(AbstractGolemEntity<?, ?> entity) {
        List<Item> upgrades = entity.getUpgrades();
        if (upgrades.isEmpty()) {
            return entity.getModifiers().keySet().stream()
                    .anyMatch(mod -> mod instanceof GolemEnergyTechModifier);
        }
        for (Item item : upgrades) {
            if (item == ModItems.GOLEM_ENERGY_TECH_UPGRADE.get()
                    || item == ModItems.LEGACY_GOLEM_ENERGY_HEAL_UPGRADE.get()) {
                return true;
            }
        }
        return false;
    }
}
