package a_silly_cat.golems_arsenal.init;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class GolemEffects {
    public static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, Golems_arsenal.MODID);

    /** Charge: grants proportional protection/resistance piercing to its holder's attacks. */
    public static final RegistryObject<MobEffect> CHARGE = EFFECTS.register("charge",
            ChargeEffect::new);

    private static final class ChargeEffect extends MobEffect {
        private ChargeEffect() {
            super(MobEffectCategory.BENEFICIAL, 0x55FFFF);
        }
    }

    private GolemEffects() {
    }

    public static void register(IEventBus modEventBus) {
        EFFECTS.register(modEventBus);
    }
}
