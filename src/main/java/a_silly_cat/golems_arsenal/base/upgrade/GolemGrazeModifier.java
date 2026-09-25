package a_silly_cat.golems_arsenal.base.upgrade;

import a_silly_cat.golems_arsenal.Config;
import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.Projectile;

import java.util.List;
import java.util.UUID;

/**
 * Graze upgrade: when a projectile (arrows, TACZ bullets - TACZ's EntityKineticBullet extends
 * Projectile - fireballs, tridents...) passes within {@code graze_radius} without hitting the
 * golem, it briefly gains movement speed (skipped when the golem is already fast enough), attack
 * speed and a short invulnerability window. Only projectiles still in flight count - resting or
 * embedded arrows (velocity below {@code graze_min_projectile_speed}) are ignored. Grants are
 * throttled by a per-golem cooldown.
 */
public class GolemGrazeModifier extends GolemModifier {
    public static final int MAX_LEVEL = 1;

    private static final UUID MOVE_UUID =
            UUID.nameUUIDFromBytes("golems_arsenal:graze_move".getBytes());
    private static final UUID ATTACK_UUID =
            UUID.nameUUIDFromBytes("golems_arsenal:graze_attack".getBytes());
    private static final String BUFF_UNTIL_KEY = "golem_graze_buff_until";
    private static final String LAST_GRANT_KEY = "golem_graze_last_grant";

    public GolemGrazeModifier() {
        super(StatFilterType.HEALTH, MAX_LEVEL);
    }

    @Override
    public Component getTooltip(int level) {
        return Component.translatable("upgrade.golems_arsenal.graze")
                .withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        return List.of(Component.translatable("upgrade.golems_arsenal.graze.desc")
                .withStyle(ChatFormatting.GREEN));
    }

    @Override
    public void onAiStep(AbstractGolemEntity<?, ?> golem, int level) {
        if (golem.level().isClientSide) {
            return;
        }
        long now = golem.level().getGameTime();
        CompoundTag data = golem.getPersistentData();
        if (now >= data.getLong(BUFF_UNTIL_KEY)) {
            clearBuff(golem);
        }
        if (golem.tickCount % Config.GRAZE_SCAN_INTERVAL.get() != 0) {
            return;
        }
        long lastGrant = data.getLong(LAST_GRANT_KEY);
        if (now < lastGrant + Config.GRAZE_COOLDOWN.get()) {
            return;
        }
        double radius = Config.GRAZE_RADIUS.get();
        double minSpeed = Config.GRAZE_MIN_PROJECTILE_SPEED.get();
        List<Projectile> near = golem.level().getEntitiesOfClass(Projectile.class,
                golem.getBoundingBox().inflate(radius),
                p -> p.isAlive() && p.getOwner() != golem
                        && p.getDeltaMovement().lengthSqr() >= minSpeed * minSpeed);
        if (near.isEmpty()) {
            return;
        }
        AttributeInstance move = golem.getAttribute(Attributes.MOVEMENT_SPEED);
        boolean moveGranted = false;
        if (move != null
                && golem.getAttributeValue(Attributes.MOVEMENT_SPEED) < Config.GRAZE_MOVE_SPEED_THRESHOLD.get()) {
            setAdd(move, MOVE_UUID, "golems_arsenal_graze_move", Config.GRAZE_MOVE_SPEED.get());
            moveGranted = true;
        }
        AttributeInstance attack = golem.getAttribute(Attributes.ATTACK_SPEED);
        if (attack != null) {
            setAdd(attack, ATTACK_UUID, "golems_arsenal_graze_attack", Config.GRAZE_ATTACK_SPEED.get());
        }
        golem.invulnerableTime = Math.max(golem.invulnerableTime,
                (int) (Config.GRAZE_IFRAME_SECONDS.get() * 20));
        data.putLong(BUFF_UNTIL_KEY, now + Config.GRAZE_BUFF_DURATION.get());
        data.putLong(LAST_GRANT_KEY, now);
    }

    private static void setAdd(AttributeInstance instance, UUID uuid, String name, double amount) {
        AttributeModifier current = instance.getModifier(uuid);
        if (current != null && current.getAmount() == amount) {
            return;
        }
        instance.removeModifier(uuid);
        instance.addTransientModifier(new AttributeModifier(uuid, name, amount,
                AttributeModifier.Operation.ADDITION));
    }

    /** Removes the active graze buff (used when the upgrade is removed or the buff expires). */
    public static void clearBuff(AbstractGolemEntity<?, ?> golem) {
        AttributeInstance move = golem.getAttribute(Attributes.MOVEMENT_SPEED);
        if (move != null) {
            move.removeModifier(MOVE_UUID);
        }
        AttributeInstance attack = golem.getAttribute(Attributes.ATTACK_SPEED);
        if (attack != null) {
            attack.removeModifier(ATTACK_UUID);
        }
        CompoundTag data = golem.getPersistentData();
        data.remove(BUFF_UNTIL_KEY);
        data.remove(LAST_GRANT_KEY);
    }
}
