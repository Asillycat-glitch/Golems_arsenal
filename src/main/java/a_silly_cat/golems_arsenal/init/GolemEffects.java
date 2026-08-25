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

    /**
     * Hidden legacy alias for the old train-buff id, kept so saved data from builds that still
     * used a custom {@code golem_train} effect resolves instead of producing unidentified
     * mappings. The train mechanic now keys on the Create mechanical buffs (工业长路/汽鸣铁道).
     */
    public static final RegistryObject<MobEffect> TRAIN_LEGACY = EFFECTS.register("golem_train",
            TrainEffect::new);

    private static final class ChargeEffect extends MobEffect {
        private ChargeEffect() {
            super(MobEffectCategory.BENEFICIAL, 0x55FFFF);
        }
    }

    private static final class TrainEffect extends MobEffect {
        private TrainEffect() {
            super(MobEffectCategory.BENEFICIAL, 0xFFB300);
        }
    }

    private GolemEffects() {
    }

    public static void register(IEventBus modEventBus) {
        EFFECTS.register(modEventBus);
    }
}
