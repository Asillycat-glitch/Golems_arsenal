package a_silly_cat.golems_arsenal.init;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.base.entity.KeyBladeEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Golems_arsenal.MODID);

    /**
     * The flying key blade of the "Key Blade Spin" special move. It is an {@code ItemEntity}
     * subclass, so the vanilla item renderer draws the rotating key-sword model with no custom
     * client code beyond registering the entity type.
     */
    public static final RegistryObject<EntityType<KeyBladeEntity>> KEY_BLADE = ENTITY_TYPES.register(
            "key_blade",
            () -> EntityType.Builder.<KeyBladeEntity>of(KeyBladeEntity::new, MobCategory.MISC)
                    .sized(0.6f, 0.6f)
                    .clientTrackingRange(10)
                    .updateInterval(2)
                    .build("key_blade"));

    private ModEntities() {
    }

    public static void register(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
    }
}
