package a_silly_cat.golems_arsenal.init;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.base.entity.KeyBladeEntity;
import a_silly_cat.golems_arsenal.base.entity.LightPillarEntity;
import a_silly_cat.golems_arsenal.base.entity.PhantomBladeEntity;
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

    /** 幻梦零甩出的剑气：纯显示 + 穿透伤害的平面实体。 */
    public static final RegistryObject<EntityType<PhantomBladeEntity>> PHANTOM_BLADE =
            ENTITY_TYPES.register(
                    "phantom_blade",
                    () -> EntityType.Builder.<PhantomBladeEntity>of(PhantomBladeEntity::new,
                                    MobCategory.MISC)
                            .sized(0.8f, 1.2f)
                            .clientTrackingRange(10)
                            .updateInterval(2)
                            .build("phantom_blade"));

    /** 光柱护体：立在原地自转、对半径内敌人造成一次伤害的柱子。 */
    public static final RegistryObject<EntityType<LightPillarEntity>> LIGHT_PILLAR =
            ENTITY_TYPES.register(
                    "light_pillar",
                    () -> EntityType.Builder.<LightPillarEntity>of(LightPillarEntity::new,
                                    MobCategory.MISC)
                            .sized(1.0f, 3.6f)
                            .clientTrackingRange(10)
                            .updateInterval(2)
                            .build("light_pillar"));

    private ModEntities() {
    }

    public static void register(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
    }
}
