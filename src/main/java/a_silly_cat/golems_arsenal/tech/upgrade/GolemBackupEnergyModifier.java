package a_silly_cat.golems_arsenal.tech.upgrade;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.init.ModNetwork;
import a_silly_cat.golems_arsenal.network.ClientboundShieldPacket;
import a_silly_cat.golems_arsenal.tech.energy.GolemEnergyProvider;
import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;

/**
 * Backup Energy upgrade (tech tier; consumes the golem's own FE storage).
 * <p>
 * Gives the golem an absorb shield like a Draconic Evolution shield / extra health bar: incoming
 * damage drains the shield first, then hits health. The shield recharges on a fixed interval
 * (default 2 seconds) by consuming FE from the golem; both the shield capacity and the
 * per-interval recharge amount scale with the upgrade level. Taking damage interrupts the
 * recharge for a short cooldown.
 */
public class GolemBackupEnergyModifier extends GolemModifier {
    public static final int MAX_LEVEL = 5;
    private static final String SHIELD_KEY = "golem_backup_energy_shield";
    private static final String NEXT_RECHARGE_KEY = "golem_backup_energy_next_recharge";
    private static final String LAST_HURT_KEY = "golem_backup_energy_last_hurt";

    public GolemBackupEnergyModifier() {
        super(StatFilterType.HEALTH, MAX_LEVEL);
    }

    @Override
    public Component getTooltip(int level) {
        return Component.translatable("upgrade.golems_arsenal.backup_energy")
                .withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        int intervalSec = Math.max(1, Config.BACKUP_ENERGY_RECHARGE_INTERVAL.get() / 20);
        int hurtCooldownSec = Math.max(1, Config.BACKUP_ENERGY_HURT_COOLDOWN.get() / 20);
        return List.of(Component.translatable("upgrade.golems_arsenal.backup_energy.desc",
                Math.round(maxShield(level)),
                intervalSec,
                Math.round(rechargePerInterval(level)),
                Config.BACKUP_ENERGY_FE_PER_POINT.get(),
                hurtCooldownSec)
                .withStyle(ChatFormatting.GREEN));
    }

    /** Shield capacity for the given total upgrade level. */
    public static float maxShield(int level) {
        return (float) (level * Config.BACKUP_ENERGY_SHIELD_PER_LEVEL.get());
    }

    /** Shield points restored every recharge interval for the given total upgrade level. */
    public static float rechargePerInterval(int level) {
        return (float) (level * Config.BACKUP_ENERGY_RECHARGE_PER_LEVEL.get());
    }

    @Override
    public void onGolemSpawn(AbstractGolemEntity<?, ?> golem, int level) {
        if (golem.level().isClientSide) {
            return;
        }
        CompoundTag data = golem.getPersistentData();
        if (!data.contains(SHIELD_KEY)) {
            data.putFloat(SHIELD_KEY, maxShield(level));
        }
        sync(golem, level);
    }

    /** Incoming damage drains the shield first; only the remainder reaches the golem. */
    @Override
    public void onHurt(AbstractGolemEntity<?, ?> golem, LivingHurtEvent event, int level) {
        if (golem.level().isClientSide) {
            return;
        }
        float amount = event.getAmount();
        if (amount <= 0) {
            return;
        }
        CompoundTag data = golem.getPersistentData();
        data.putLong(LAST_HURT_KEY, golem.level().getGameTime());
        float shield = data.getFloat(SHIELD_KEY);
        if (shield <= 0) {
            return;
        }
        float absorbed = Math.min(shield, amount);
        data.putFloat(SHIELD_KEY, shield - absorbed);
        event.setAmount(Math.max(0, amount - absorbed));
        sync(golem, level);
    }

    /**
     * Fixed-interval recharge: every {@code recharge_interval} ticks, restore up to
     * {@code recharge_per_interval} shield points, consuming {@code fe_per_point} FE per point.
     * Taking damage interrupts the recharge: after a hit, no shield points are restored until
     * {@code hurt_cooldown} ticks have passed.
     */
    @Override
    public void onAiStep(AbstractGolemEntity<?, ?> golem, int level) {
        if (golem.level().isClientSide) {
            return;
        }
        int interval = Config.BACKUP_ENERGY_RECHARGE_INTERVAL.get();
        if (interval <= 0) {
            return;
        }
        long now = golem.level().getGameTime();
        CompoundTag data = golem.getPersistentData();
        if (!data.contains(SHIELD_KEY)) {
            data.putFloat(SHIELD_KEY, maxShield(level));
            data.putLong(NEXT_RECHARGE_KEY, now);
        }
        if (now < data.getLong(NEXT_RECHARGE_KEY)) {
            return;
        }
        data.putLong(NEXT_RECHARGE_KEY, now + interval);
        if (now - data.getLong(LAST_HURT_KEY) < Config.BACKUP_ENERGY_HURT_COOLDOWN.get()) {
            sync(golem, level);
            return;
        }
        float max = maxShield(level);
        float shield = Math.min(data.getFloat(SHIELD_KEY), max);
        if (shield < max) {
            float target = Math.min(max, shield + rechargePerInterval(level));
            float need = target - shield;
            if (need > 0) {
                // "节约能源"升级在这里也生效（每级 -25%）。
                int costPer = a_silly_cat.golems_arsenal.base.upgrade.GolemEnergySaverModifier
                        .discount(golem, Config.BACKUP_ENERGY_FE_PER_POINT.get());
                long requiredFe = (long) Math.ceil(need * costPer);
                float restored = golem.getCapability(GolemEnergyProvider.CAPABILITY)
                        .map(storage -> {
                            int stored = storage.getEnergyStored();
                            if (stored <= 0) {
                                return 0f;
                            }
                            int budget = (int) Math.min(stored, Math.min(Integer.MAX_VALUE, requiredFe));
                            int paid = storage.extractEnergy(budget, false);
                            return (float) (paid / (double) costPer);
                        }).orElse(0f);
                if (restored > 0) {
                    data.putFloat(SHIELD_KEY, Math.min(max, shield + restored));
                }
            }
        }
        sync(golem, level);
    }

    /** Sends the current shield value to every client tracking the golem, for the info panel. */
    private static void sync(AbstractGolemEntity<?, ?> golem, int level) {
        float shield = golem.getPersistentData().getFloat(SHIELD_KEY);
        ModNetwork.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> golem),
                new ClientboundShieldPacket(golem.getId(), shield, maxShield(level)));
    }

    public static float shieldOf(AbstractGolemEntity<?, ?> golem) {
        return golem.getPersistentData().getFloat(SHIELD_KEY);
    }
}
