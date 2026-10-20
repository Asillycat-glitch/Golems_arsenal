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

    /**
     * END 层返还（0 ~ 0.8）：把目标减伤削掉的那部分按比例还回来。
     *
     * <p>把"多个 END 层返还来源"统一收进这一个属性：傀儡材质、升级件、以及数据包给武器注册的
     * 值（{@code data/<ns>/golems_arsenal_restitution/*.json}）都会汇总到它身上，
     * 由 {@code DamageRestitutionListener} 统一夹到 0.8 上限 —— 这样加内容时不必再改监听器，
     * 也不会出现"两件装备各 0.5、合计 1.0 直接无视所有减伤"的失控情况。</p>
     */
    public static final RegistryObject<Attribute> END_RESTITUTION = ATTRIBUTES.register("end_restitution",
            () -> new RangedAttribute("attribute.name.golems_arsenal.end_restitution", 0.0, 0.0, 0.8)
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
                event.add(type, END_RESTITUTION.get());
                for (String[] entry : OPTIONAL_GOLEM_ATTRIBUTES) {
                    Attribute attr = ForgeRegistries.ATTRIBUTES.getValue(
                            new ResourceLocation(entry[0], entry[1]));
                    if (attr != null && !event.has(type, attr)) {
                        event.add(type, attr);
                    }
                }
            }
        }
        // 玩家也要有 end_restitution：手套第 8 格的 END 层保底就是往这个属性上挂固定值。
        // 属性本身是 RangedAttribute(0, 0, 0.8)，所以玩家的天花板和傀儡一致，叠不穿。
        if (!event.has(EntityType.PLAYER, END_RESTITUTION.get())) {
            event.add(EntityType.PLAYER, END_RESTITUTION.get());
        }
    }

    private ModAttributes() {
    }
}
