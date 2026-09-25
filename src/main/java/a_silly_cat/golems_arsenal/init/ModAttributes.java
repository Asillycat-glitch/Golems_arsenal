package a_silly_cat.golems_arsenal.init;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraftforge.event.entity.EntityAttributeModificationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModAttributes {
    public static final DeferredRegister<Attribute> ATTRIBUTES =
            DeferredRegister.create(ForgeRegistries.ATTRIBUTES, Golems_arsenal.MODID);

    /**
     * Arrow launch velocity of the golem. Vanilla arrow damage on impact scales with velocity
     * (damage = velocity length x base damage), so raising this attribute raises both speed and
     * damage without touching base damage directly.
     */
    public static final RegistryObject<Attribute> ARROW_VELOCITY = ATTRIBUTES.register("arrow_velocity",
            () -> new RangedAttribute("attribute.name.golems_arsenal.arrow_velocity", 1.0, 0.1, 10.0)
                    .setSyncable(true));

    private static final String[] GOLEM_TYPES = {"metal_golem", "humanoid_golem", "dog_golem"};
    /**
     * Crit attributes from optional libraries. L2DamageTracker only registers its crit attributes
     * on players, so they are added to the golem entity types here (soft dependency: skipped when
     * the providing mod is absent). Cataclysm's additional crit damage is already registered on
     * every entity type by that mod.
     */
    private static final String[][] OPTIONAL_GOLEM_ATTRIBUTES = {
            {"l2damagetracker", "crit_rate"},
            {"l2damagetracker", "crit_damage"},
            {"cataclysm", "additional_critical_damage"},
    };

    public static void register(IEventBus modEventBus) {
        ATTRIBUTES.register(modEventBus);
    }

    /** Adds the attribute to all Modular Golems entity types so modifiers can be applied to them. */
    public static void modifyAttributes(EntityAttributeModificationEvent event) {
        for (String id : GOLEM_TYPES) {
            EntityType<? extends LivingEntity> type = (EntityType<? extends LivingEntity>)
                    ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("modulargolems", id));
            if (type != null) {
                event.add(type, ARROW_VELOCITY.get());
                for (String[] entry : OPTIONAL_GOLEM_ATTRIBUTES) {
                    Attribute attr = ForgeRegistries.ATTRIBUTES.getValue(
                            new ResourceLocation(entry[0], entry[1]));
                    if (attr != null && !event.has(type, attr)) {
                        event.add(type, attr);
                    }
                }
            }
        }
    }

    private ModAttributes() {
    }
}
