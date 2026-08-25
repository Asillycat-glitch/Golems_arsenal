package a_silly_cat.golems_arsenal.base.upgrade;

import a_silly_cat.golems_arsenal.Config;
import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Start-the-Train modifier, granted to the {@code create:railway} material through a datapack
 * override of the Create material config (no upgrade item). The railway material's own
 * "steam engine" modifier already applies two buffs to the golem itself: 汽鸣铁道
 * (modulargolems:mechanical_mobility, the "hand" buff) and 工业长路
 * (modulargolems:mechanical_force, the "body" buff).
 * <p>
 * Attack effects (attack scaling, handled in WeaponEventHandler) check the hand buff; defense
 * effects (damage share here, aggro magnet in WeaponEventHandler) check the body buff.
 */
public class GolemTrainModifier extends GolemModifier {
    public static final int MAX_LEVEL = 1;
    private static final String TRANSMITTED_TICK_KEY = "golem_train_transmitted_tick";
    private static MobEffect handBuffCache;
    private static MobEffect bodyBuffCache;

    public GolemTrainModifier() {
        super(StatFilterType.ATTACK, MAX_LEVEL);
    }

    @Override
    public Component getTooltip(int level) {
        return Component.translatable("upgrade.golems_arsenal.golem_train")
                .withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        return List.of(Component.translatable("upgrade.golems_arsenal.golem_train.desc")
                .withStyle(ChatFormatting.GREEN));
    }

    /**
     * Defense effect (body buff 工业长路): the train golem keeps 70% of a hit and passes 30%
     * to a random allied car that also carries the body buff.
     * Cars that received a share this tick are tagged so a second conductor cannot chain-share
     * the same hit further.
     */
    @Override
    public void onHurt(AbstractGolemEntity<?, ?> golem, LivingHurtEvent event, int level) {
        if (golem.level().isClientSide || !hasBodyBuff(golem)) {
            return;
        }
        float amount = event.getAmount();
        if (amount <= 0) {
            return;
        }
        CompoundTag data = golem.getPersistentData();
        if (data.getInt(TRANSMITTED_TICK_KEY) == golem.tickCount) {
            return;
        }
        AbstractGolemEntity<?, ?> car = randomBuffedCar(golem);
        if (car == null) {
            return;
        }
        float shared = amount * Config.TRAIN_SHARE_RATIO.get().floatValue();
        event.setAmount(amount - shared);
        car.getPersistentData().putInt(TRANSMITTED_TICK_KEY, car.tickCount);
        car.hurt(event.getSource(), shared);
    }

    /** A random allied golem (other than the conductor) that currently has the body buff. */
    public static AbstractGolemEntity<?, ?> randomBuffedCar(AbstractGolemEntity<?, ?> golem) {
        double radius = Config.TRAIN_BUFF_RADIUS.get();
        List<AbstractGolemEntity<?, ?>> list = nearbyGolems(golem, radius,
                e -> e.isAlive() && e != golem && sameOwner(golem, e)
                        && hasBodyBuff(e));
        return list.isEmpty() ? null : list.get(golem.getRandom().nextInt(list.size()));
    }

    /**
     * All {@link AbstractGolemEntity}s within {@code radius} of {@code center} that satisfy the
     * extra predicate. The unchecked cast isolates the generics noise of the raw class literal.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List<AbstractGolemEntity<?, ?>> nearbyGolems(AbstractGolemEntity<?, ?> center,
                                                               double radius, Predicate<AbstractGolemEntity<?, ?>> extra) {
        double r2 = radius * radius;
        return center.level().getEntitiesOfClass((Class<AbstractGolemEntity<?, ?>>) (Class<?>) AbstractGolemEntity.class,
                center.getBoundingBox().inflate(radius),
                e -> e.distanceToSqr(center) <= r2 && extra.test(e));
    }

    /** 汽鸣铁道: the hand buff (modulargolems:mechanical_mobility), checked by attack effects. */
    public static MobEffect handBuff() {
        if (handBuffCache == null) {
            handBuffCache = ForgeRegistries.MOB_EFFECTS.getValue(
                    new ResourceLocation("modulargolems", "mechanical_mobility"));
        }
        return handBuffCache;
    }

    /** 工业长路: the body buff (modulargolems:mechanical_force), checked by defense effects. */
    public static MobEffect bodyBuff() {
        if (bodyBuffCache == null) {
            bodyBuffCache = ForgeRegistries.MOB_EFFECTS.getValue(
                    new ResourceLocation("modulargolems", "mechanical_force"));
        }
        return bodyBuffCache;
    }

    public static boolean hasHandBuff(AbstractGolemEntity<?, ?> golem) {
        MobEffect effect = handBuff();
        return effect != null && golem.hasEffect(effect);
    }

    public static boolean hasBodyBuff(AbstractGolemEntity<?, ?> golem) {
        MobEffect effect = bodyBuff();
        return effect != null && golem.hasEffect(effect);
    }

    public static boolean sameOwner(AbstractGolemEntity<?, ?> a, AbstractGolemEntity<?, ?> b) {
        UUID au = a.getOwnerUUID();
        UUID bu = b.getOwnerUUID();
        return au == null ? bu == null : au.equals(bu);
    }
}
