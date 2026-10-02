package a_silly_cat.golems_arsenal.base.event;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.base.item.KeySwordItem;
import a_silly_cat.golems_arsenal.base.upgrade.GolemFlagModifier;
import a_silly_cat.golems_arsenal.base.upgrade.GolemUpgrades;
import a_silly_cat.golems_arsenal.tech.item.GolemEnergyKatanaItem;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * Charge special move (冲撞): the golem's melee hit becomes a forward charge whose landing spot sits
 * a little way <b>behind</b> the struck enemy; every enemy the golem sweeps past takes a normal
 * golem attack hit, so the whole damage pipeline applies (weapon upgrades, full onslaught, executor
 * artifact synergy, crits) and all on-hit effects still trigger.
 * <p>
 * The dash covers a fixed {@link #DASH_DISTANCE} blocks along the golem→target line, and its speed is
 * the golem's own movement speed plus {@link #DASH_SPEED_BONUS}, so a faster golem lunges faster and
 * the travel time comes out of distance ÷ speed. Overshooting past the target is intended: the extra
 * travel is what lets the golem keep its spacing instead of parking on top of the enemy. Momentum is
 * handed back when the lunge lands, so the golem does not stall on the spot afterwards.
 * <ul>
 *   <li>key blade in hand: three charges in a row, each re-aimed at the current target;</li>
 *   <li>energy katana in hand: the charge becomes a blink, landing {@link #BLINK_BEHIND_TARGET}
 *   blocks behind the target (the swept enemies still take the damage). A blink that kills always
 *   clears the charge cooldown, otherwise there is a small chance of it, so the golem can blink
 *   again on its very next hit instead of waiting out the cooldown;</li>
 *   <li>stellar apocalypse ({@code modulargolems:stellar_apocalypse}) in hand: we stand down
 *   completely - that weapon brings its own 10s dash, and ours would pin the golem inside melee
 *   range, which is exactly the condition its dash needs to <b>not</b> be met;</li>
 *   <li>any other weapon: a single charge.</li>
 * </ul>
 * The dash is stored on the golem's persistent data and advanced inside the golem's own tick (the
 * same approach the lion slash uses), so every position change is broadcast in the same tick and the
 * client renderer never lags a whole flight behind.
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID)
public final class GolemChargeHandler {

    private static final String CD_KEY = "GolemsArsenalChargeCooldown";
    private static final String ACTIVE_KEY = "GolemsArsenalChargeActive";
    private static final String START_KEY = "GolemsArsenalChargeStart";
    private static final String TICKS_KEY = "GolemsArsenalChargeTicks";
    private static final String LEFT_KEY = "GolemsArsenalChargeLeft";
    private static final String TOTAL_KEY = "GolemsArsenalChargeTotal";
    private static final String PAUSE_KEY = "GolemsArsenalChargePause";
    private static final String FROM_X_KEY = "GolemsArsenalChargeFromX";
    private static final String FROM_Y_KEY = "GolemsArsenalChargeFromY";
    private static final String FROM_Z_KEY = "GolemsArsenalChargeFromZ";
    private static final String TO_X_KEY = "GolemsArsenalChargeToX";
    private static final String TO_Y_KEY = "GolemsArsenalChargeToY";
    private static final String TO_Z_KEY = "GolemsArsenalChargeToZ";
    private static final String HITS_KEY = "GolemsArsenalChargeHits";

    /** 冲撞冷却（tick）：8 秒。固定值，不做配置。 */
    private static final int COOLDOWN_TICKS = 160;
    /** 索敌间隔（tick）：没在冲撞时，每隔这么久检查一次"攻击距离 + N 格"内有没有目标。 */
    private static final int TARGET_SCAN_INTERVAL = 10;
    /**
     * 索敌加成：目标在"傀儡攻击距离（{@code forge:entity_reach}）+ 该值"以内就会主动发起冲撞，
     * 不再必须打到才触发。固定 5 格。
     */
    private static final double TARGET_RANGE_BONUS = 5.0;
    /** 冲撞距离：固定 10 格，不再按目标距离 / 攻击距离 / 体型换算。 */
    private static final double DASH_DISTANCE = 10.0;
    /**
     * 冲撞速度（格/tick）= 傀儡自身的移动速度 + 该值。傀儡走得快，冲得就快；
     * 冲刺时长也由它和距离算出来，所以不会再"距离一长就突然飙速"。
     */
    private static final double DASH_SPEED_BONUS = 0.3;
    /** 闪现落点：目标身后这么远（格），也就是沿"傀儡→目标"方向走到"目标距离 + 该值"处。 */
    private static final double BLINK_BEHIND_TARGET = 5.0;
    /** Ticks of stand-still between the charges of a key-blade combo, so the three read as three. */
    private static final int PAUSE_TICKS = 10;
    /** Charges per use with the key blade in hand. */
    private static final int KEY_BLADE_CHARGES = 3;
    /** The golem never stops closer than this to where it started. */
    private static final double MIN_TRAVEL = 1.0;
    /**
     * 星辰之怒（{@code modulargolems:stellar_apocalypse}，傀儡装配 × 诡厄巫法：启示录 联动剑）。
     * 它自己带一条 10 秒冷却的冲刺（{@code ApollyonSword.onTick}），所以拿着它时我们让位、不抢动作。
     */
    private static final ResourceLocation STELLAR_APOCALYPSE =
            new ResourceLocation("modulargolems", "stellar_apocalypse");
    /**
     * Chance for a katana blink to clear its own cooldown (so the golem can blink again on its next
     * hit right away). Killing with the blink always refreshes it.
     */
    private static final double BLINK_REFRESH_CHANCE = 0.25;
    /** Extra hit radius around the golem while charging, on top of half its own width. */
    private static final double HIT_PADDING = 1.0;
    /** Charge damage as a fraction of the golem's attack damage. */
    private static final double DAMAGE_RATIO = 1.0;

    private GolemChargeHandler() {
    }

    /**
     * Charge trigger: any melee hit from a golem carrying this special move. Damage this mod applies
     * itself (key sword magic, lion slash, sword rain, stance/flame magic) never starts a charge, and
     * a charge already in progress blocks new triggers so the sweeping hits cannot chain into more
     * charges.
     */
    @SubscribeEvent
    public static void onGolemMeleeHit(LivingHurtEvent event) {
        if (event.getEntity().level().isClientSide) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        LivingEntity victim = event.getEntity();
        if (victim == golem
                || golem.isInRangedMode()
                || !GolemFlagModifier.hasUpgrade(golem, GolemUpgrades.CHARGE.get())
                || isOwnDamage(event.getSource())) {
            return;
        }
        CompoundTag tag = golem.getPersistentData();
        if (tag.getBoolean(ACTIVE_KEY)
                || golem.level().getGameTime() < tag.getLong(CD_KEY)) {
            return;
        }
        ItemStack stack = golem.getMainHandItem();
        if (yieldsToWeapon(stack)) {
            return; // 星辰之怒自带冲刺，这里让位，别抢它的动作
        }
        boolean blink = stack.getItem() instanceof GolemEnergyKatanaItem;
        boolean triple = !blink && stack.getItem() instanceof KeySwordItem;
        tag.putLong(CD_KEY, golem.level().getGameTime() + COOLDOWN_TICKS);
        golem.swing(InteractionHand.MAIN_HAND);
        startCharge(golem, victim, blink, triple);
    }

    /**
     * 主动索敌：不再要求"先打到"，只要目标进入"攻击距离 + {@link #TARGET_RANGE_BONUS} 格"就发起冲撞。
     * <p>
     * 优先用 AI 已经锁定的目标（{@code getTarget()}）；它不存在或超出范围时，再扫一圈附近的敌对目标
     * （{@code Enemy}，或者正在以这只傀儡为目标的生物），取最近的一个。远程 AI 不触发。
     */
    private static void tryAutoCharge(AbstractGolemEntity<?, ?> golem, CompoundTag tag) {
        if (golem.tickCount % TARGET_SCAN_INTERVAL != 0
                || golem.isInRangedMode()
                || !GolemFlagModifier.hasUpgrade(golem, GolemUpgrades.CHARGE.get())) {
            return;
        }
        long now = golem.level().getGameTime();
        if (now < tag.getLong(CD_KEY)) {
            return;
        }
        if (yieldsToWeapon(golem.getMainHandItem())) {
            return; // 星辰之怒自带冲刺，主动索敌也让位
        }
        double range = entityReach(golem) + TARGET_RANGE_BONUS;
        LivingEntity victim = null;
        LivingEntity target = golem.getTarget();
        if (isChargeTarget(golem, target, range)) {
            victim = target;
        } else {
            double best = Double.MAX_VALUE;
            AABB box = golem.getBoundingBox().inflate(range);
            for (LivingEntity cand : golem.level().getEntitiesOfClass(LivingEntity.class, box,
                    e -> isChargeTarget(golem, e, range))) {
                double d = golem.distanceToSqr(cand);
                if (d < best) {
                    best = d;
                    victim = cand;
                }
            }
        }
        if (victim == null) {
            return;
        }
        ItemStack stack = golem.getMainHandItem();
        boolean blink = stack.getItem() instanceof GolemEnergyKatanaItem;
        boolean triple = !blink && stack.getItem() instanceof KeySwordItem;
        tag.putLong(CD_KEY, now + COOLDOWN_TICKS);
        golem.swing(InteractionHand.MAIN_HAND);
        startCharge(golem, victim, blink, triple);
    }

    /** 能不能把它当作主动索敌的目标：活着、不是自己/友方、是敌对目标、且在范围内。 */
    private static boolean isChargeTarget(AbstractGolemEntity<?, ?> golem, LivingEntity cand,
                                          double range) {
        if (cand == null || cand == golem || !cand.isAlive() || cand.isAlliedTo(golem)) {
            return false;
        }
        boolean hostile = cand instanceof Enemy
                || (cand instanceof Mob mob && mob.getTarget() == golem);
        return hostile && golem.distanceToSqr(cand) <= range * range;
    }

    /**
     * Per-tick driver of an ongoing charge: interpolates the golem along the stored segment,
     * damages whatever it sweeps past and starts the next charge of a key-blade combo when the
     * current one lands.
     */
    @SubscribeEvent
    public static void onGolemTick(LivingEvent.LivingTickEvent event) {
        if (event.getEntity().level().isClientSide
                || !(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        CompoundTag tag = golem.getPersistentData();
        if (!tag.getBoolean(ACTIVE_KEY)) {
            // 没在冲撞：顺手做一次主动索敌（目标进入"攻击距离 + 5 格"就发起冲撞）。
            tryAutoCharge(golem, tag);
            return;
        }
        if (!golem.isAlive()) {
            clear(golem, tag);
            return;
        }
        long now = golem.level().getGameTime();
        int ticks = Math.max(1, tag.getInt(TICKS_KEY));
        double progress = (double) (now - tag.getLong(START_KEY)) / ticks;
        Vec3 from = new Vec3(tag.getDouble(FROM_X_KEY), tag.getDouble(FROM_Y_KEY),
                tag.getDouble(FROM_Z_KEY));
        Vec3 to = new Vec3(tag.getDouble(TO_X_KEY), tag.getDouble(TO_Y_KEY),
                tag.getDouble(TO_Z_KEY));
        if (tag.getBoolean(PAUSE_KEY)) {
            // Wind-up between the charges of a key-blade combo. The golem is pinned in place so the
            // movement AI cannot turn the pause into a slow drift, and it keeps facing its target so
            // the next charge reads as a separate lunge.
            golem.setNoGravity(true);
            golem.setPos(to.x, to.y, to.z);
            golem.setDeltaMovement(Vec3.ZERO);
            golem.fallDistance = 0;
            LivingEntity next = golem.getTarget();
            if (next != null && next.isAlive() && next != golem) {
                faceTowards(golem, next.position().subtract(golem.position()));
            }
            if (progress < 1.0) {
                return;
            }
            tag.putBoolean(PAUSE_KEY, false);
            Vec3 nextFrom = golem.position();
            Vec3 aim = next != null && next.isAlive() && next != golem
                    ? landingSpot(golem, next)
                    : freeLanding(golem, nextFrom, nextFrom.add(to.subtract(from)));
            beginDash(golem, tag, nextFrom, aim, now);
            return;
        }
        if (progress < 1.0) {
            Vec3 pos = from.lerp(to, ease(progress));
            golem.setNoGravity(true);
            golem.setPos(pos.x, pos.y, pos.z);
            golem.setDeltaMovement(Vec3.ZERO);
            golem.fallDistance = 0;
            faceTowards(golem, to.subtract(from));
            damageAround(golem, pos, tag);
            if (golem.level() instanceof ServerLevel server) {
                server.sendParticles(ParticleTypes.CLOUD, pos.x, pos.y + 0.15, pos.z,
                        6, golem.getBbWidth() * 0.4, 0.05, golem.getBbWidth() * 0.4, 0.02);
            }
            return;
        }
        // Landed: snap onto the landing spot and settle whatever stands there.
        golem.setPos(to.x, to.y, to.z);
        // 速度不清零：留一点向前的惯性，落地才不会"顿"一下，AI 接手时是连贯的。
        pushForward(golem, to.subtract(from));
        golem.fallDistance = 0;
        damageAround(golem, to, tag);
        int left = tag.getInt(LEFT_KEY);
        if (left > 0) {
            // Key blade combo: stop dead for a moment before the next charge, so the three charges
            // read as three separate lunges instead of one long turnable dash.
            tag.putInt(LEFT_KEY, left - 1);
            tag.putBoolean(PAUSE_KEY, true);
            tag.putLong(START_KEY, now);
            tag.putInt(TICKS_KEY, PAUSE_TICKS);
            if (golem.level() instanceof ServerLevel server) {
                server.sendParticles(ParticleTypes.CLOUD, to.x, to.y + 0.15, to.z, 12,
                        golem.getBbWidth() * 0.5, 0.1, golem.getBbWidth() * 0.5, 0.03);
            }
            return;
        }
        if (golem.level() instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.SWEEP_ATTACK, to.x, to.y + golem.getBbHeight() * 0.5,
                    to.z, 3, 0.4, 0.4, 0.4, 0.0);
        }
        clear(golem, tag);
    }

    /** Starts the whole move: blink (katana), triple charge (key blade) or a single charge. */
    private static void startCharge(AbstractGolemEntity<?, ?> golem, LivingEntity victim,
                                    boolean blink, boolean triple) {
        CompoundTag tag = golem.getPersistentData();
        Vec3 from = golem.position();
        // 闪现落在"目标身后 5 格"，普通冲撞是固定 10 格。
        Vec3 aim = blink ? blinkSpot(golem, victim) : landingSpot(golem, victim);
        tag.putBoolean(ACTIVE_KEY, true);
        if (blink) {
            // Katana form: one blink per swing, but a blink that kills (or gets lucky) clears its own
            // cooldown, so the golem can blink again on its very next hit instead of waiting.
            int kills = blinkTo(golem, from, aim, tag);
            boolean refreshed = kills > 0
                    || golem.getRandom().nextDouble() < BLINK_REFRESH_CHANCE;
            clear(golem, tag);
            if (refreshed) {
                tag.remove(CD_KEY);
            }
            return;
        }
        tag.putInt(LEFT_KEY, triple ? KEY_BLADE_CHARGES - 1 : 0);
        tag.putInt(TOTAL_KEY, triple ? KEY_BLADE_CHARGES : 1);
        beginDash(golem, tag, from, aim, golem.level().getGameTime());
    }

    /** Arms one dash segment: books the segment, clears the hit list and plays the lunge sound. */
    private static void beginDash(AbstractGolemEntity<?, ?> golem, CompoundTag tag, Vec3 from,
                                  Vec3 to, long now) {
        double length = from.distanceTo(to);
        // 时长由"距离 ÷ 冲撞速度"算出来；速度跟着傀儡自身的移动速度走（走得多快就冲得多快）。
        int ticks = (int) Math.max(1, Math.round(length / dashSpeed(golem)));
        tag.putLong(START_KEY, now);
        tag.putInt(TICKS_KEY, ticks);
        tag.putDouble(FROM_X_KEY, from.x);
        tag.putDouble(FROM_Y_KEY, from.y);
        tag.putDouble(FROM_Z_KEY, from.z);
        tag.putDouble(TO_X_KEY, to.x);
        tag.putDouble(TO_Y_KEY, to.y);
        tag.putDouble(TO_Z_KEY, to.z);
        tag.put(HITS_KEY, new CompoundTag());
        golem.setNoGravity(true);
        golem.setDeltaMovement(Vec3.ZERO);
        faceTowards(golem, to.subtract(from));
        if (golem.level() instanceof ServerLevel server) {
            server.playSound(null, from.x, from.y, from.z, SoundEvents.IRON_GOLEM_ATTACK,
                    SoundSource.HOSTILE, 1.0F, 1.0F);
        }
    }

    /**
     * Blink form (energy katana): teleport straight to the landing spot. The enemies that the
     * straight line crosses still take the hit, so the katana version is the same move without the
     * travel time.
     */
    private static int blinkTo(AbstractGolemEntity<?, ?> golem, Vec3 from, Vec3 to, CompoundTag tag) {
        // Fresh hit list: a chained blink is allowed to hit the same enemies again.
        tag.put(HITS_KEY, new CompoundTag());
        Vec3 eyeFrom = from.add(0, golem.getBbHeight() * 0.5, 0);
        Vec3 eyeTo = to.add(0, golem.getBbHeight() * 0.5, 0);
        if (golem.level() instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.REVERSE_PORTAL, eyeFrom.x, eyeFrom.y, eyeFrom.z,
                    40, 0.3, golem.getBbHeight() * 0.4, 0.3, 0.05);
            server.playSound(null, from.x, from.y, from.z, SoundEvents.ENDERMAN_TELEPORT,
                    SoundSource.HOSTILE, 1.0F, 1.0F);
        }
        golem.teleportTo(to.x, to.y, to.z);
        pushForward(golem, to.subtract(from));
        golem.fallDistance = 0;
        faceTowards(golem, to.subtract(from));
        int kills = damageAlong(golem, from, to, tag);
        if (golem.level() instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.PORTAL, eyeTo.x, eyeTo.y, eyeTo.z,
                    40, 0.3, golem.getBbHeight() * 0.4, 0.3, 0.05);
            server.playSound(null, to.x, to.y, to.z, SoundEvents.ENDERMAN_TELEPORT,
                    SoundSource.HOSTILE, 1.0F, 1.2F);
        }
        return kills;
    }

    /**
     * 冲撞落点：沿"傀儡→目标"的水平方向，从当前位置固定走 {@link #DASH_DISTANCE} 格。
     * 目标会被甩在身后（冲过头是有意的：留着距离方便拉扯）。
     */
    private static Vec3 landingSpot(AbstractGolemEntity<?, ?> golem, LivingEntity victim) {
        return spotAlong(golem, victim, DASH_DISTANCE);
    }

    /** 闪现落点：目标身后 {@link #BLINK_BEHIND_TARGET} 格，也就是走到"目标距离 + 该值"处。 */
    private static Vec3 blinkSpot(AbstractGolemEntity<?, ?> golem, LivingEntity victim) {
        Vec3 from = golem.position();
        double distance = Math.hypot(victim.getX() - from.x, victim.getZ() - from.z);
        return spotAlong(golem, victim, distance + BLINK_BEHIND_TARGET);
    }

    /** 沿"傀儡→目标"的水平方向走 {@code travel} 格；方向兜底用视线，落点撞墙会自动收回。 */
    private static Vec3 spotAlong(AbstractGolemEntity<?, ?> golem, LivingEntity victim, double travel) {
        Vec3 from = golem.position();
        double dx = victim.getX() - from.x;
        double dz = victim.getZ() - from.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        Vec3 look = golem.getLookAngle();
        double nx = distance > 1.0E-4 ? dx / distance : look.x;
        double nz = distance > 1.0E-4 ? dz / distance : look.z;
        // Stay on the golem's own height unless the enemy is roughly on the same level, so a charge
        // never drops the golem down a cliff or lifts it onto a roof.
        double y = Math.abs(victim.getY() - from.y) <= 2.0 ? victim.getY() : from.y;
        Vec3 wanted = new Vec3(from.x + nx * travel, y, from.z + nz * travel);
        return freeLanding(golem, from, wanted);
    }

    /** {@code forge:entity_reach} (the attribute the sword upgrade and other mods push up), or 0. */
    private static double entityReach(AbstractGolemEntity<?, ?> golem) {
        Attribute reach = ForgeMod.ENTITY_REACH.get();
        return reach == null ? 0.0 : Math.max(0.0, golem.getAttributeValue(reach));
    }

    /** 傀儡自己的移动速度（格/tick），兜个底免得落地后完全不动。 */
    private static double walkSpeed(AbstractGolemEntity<?, ?> golem) {
        return Math.max(0.05, golem.getAttributeValue(Attributes.MOVEMENT_SPEED));
    }

    /** 冲撞速度（格/tick）= 傀儡移动速度 + {@link #DASH_SPEED_BONUS}。 */
    private static double dashSpeed(AbstractGolemEntity<?, ?> golem) {
        return walkSpeed(golem) + DASH_SPEED_BONUS;
    }

    /**
     * 收招时把速度交还给傀儡：冲撞/闪现结束后留一点向前的惯性（按傀儡自己的移动速度），
     * 别把速度清零 —— 清零那一下就是"冲完顿住"的来源，AI 还得重新起步。
     */
    private static void pushForward(AbstractGolemEntity<?, ?> golem, Vec3 dir) {
        double hd = dir.horizontalDistance();
        if (hd <= 1.0E-4) {
            return;
        }
        golem.setDeltaMovement(new Vec3(dir.x / hd, 0, dir.z / hd).scale(walkSpeed(golem)));
        golem.hurtMarked = true; // 让服务端把这次速度变化同步给客户端
    }

    /**
     * 手里是不是星辰之怒（{@code modulargolems:stellar_apocalypse}，傀儡装配 × 诡厄巫法：启示录）。
     * <p>
     * 它自带一条 10 秒冷却的冲刺，拿着它时我们整套冲撞让位 —— 不只是"不抢动作"：我们的主动索敌会把
     * 傀儡按在近战距离内，它自己的冲刺条件是"目标在近战距离<b>之外</b>"，两边一起开着它就再也冲不出来。
     */
    private static boolean yieldsToWeapon(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return STELLAR_APOCALYPSE.equals(ForgeRegistries.ITEMS.getKey(stack.getItem()));
    }

    /** Pulls a landing spot back toward the golem until the golem's box fits there. */
    private static Vec3 freeLanding(AbstractGolemEntity<?, ?> golem, Vec3 from, Vec3 wanted) {
        Vec3 delta = wanted.subtract(from);
        double length = delta.length();
        if (length <= 1.0E-4) {
            return from;
        }
        Vec3 dir = delta.scale(1.0 / length);
        double allowed = length;
        Vec3 cursor = from.add(dir.scale(allowed));
        while (allowed > MIN_TRAVEL && !fits(golem, from, cursor)) {
            allowed = Math.max(MIN_TRAVEL, allowed - 0.25);
            cursor = from.add(dir.scale(allowed));
        }
        return cursor;
    }

    private static boolean fits(AbstractGolemEntity<?, ?> golem, Vec3 from, Vec3 at) {
        return golem.level().noCollision(golem, golem.getBoundingBox().move(at.subtract(from)));
    }

    /**
     * Damages everything the straight line from {@code from} to {@code to} crosses once.
     *
     * @return how many of those enemies died to this sweep
     */
    private static int damageAlong(AbstractGolemEntity<?, ?> golem, Vec3 from, Vec3 to,
                                   CompoundTag tag) {
        double length = from.distanceTo(to);
        int steps = Math.max(1, (int) Math.ceil(length / 0.75));
        int kills = 0;
        for (int i = 0; i <= steps; i++) {
            kills += damageAround(golem, from.lerp(to, (double) i / steps), tag);
        }
        return kills;
    }

    /**
     * One charge hit at the golem's current position. Uses the golem's own melee damage source on
     * purpose: that is what makes the sweep take every damage bonus and trigger the other on-hit
     * effects, exactly like a normal swing. Each enemy is only hit once per charge.
     */
    private static int damageAround(AbstractGolemEntity<?, ?> golem, Vec3 pos, CompoundTag tag) {
        double damage = DAMAGE_RATIO * golem.getAttributeValue(Attributes.ATTACK_DAMAGE);
        if (damage <= 0) {
            return 0;
        }
        double half = Math.max(0.7, golem.getBbWidth() * 0.5 + HIT_PADDING);
        AABB box = AABB.ofSize(pos.add(0, golem.getBbHeight() * 0.5, 0),
                half * 2, golem.getBbHeight() + 0.5, half * 2);
        CompoundTag hits = tag.getCompound(HITS_KEY);
        List<LivingEntity> targets = golem.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive() && e != golem && !e.isAlliedTo(golem)
                        && !hits.contains(e.getStringUUID()));
        int kills = 0;
        for (LivingEntity target : targets) {
            hits.putBoolean(target.getStringUUID(), true);
            target.hurt(golem.damageSources().mobAttack(golem), (float) damage);
            if (!target.isAlive()) {
                kills++;
            }
        }
        return kills;
    }

    private static void faceTowards(AbstractGolemEntity<?, ?> golem, Vec3 dir) {
        double hd = dir.horizontalDistance();
        if (hd <= 1.0E-4) {
            return;
        }
        float yaw = (float) (Math.atan2(-dir.x, dir.z) * 180.0 / Math.PI);
        golem.setYRot(yaw);
        golem.yHeadRot = yaw;
        golem.yBodyRot = yaw;
    }

    /** Ease-in-out so the charge reads as one lunge instead of a constant-speed slide. */
    private static double ease(double progress) {
        double t = Math.max(0.0, Math.min(1.0, progress));
        return t * t * (3.0 - 2.0 * t);
    }

    private static int tripleCharges(CompoundTag tag) {
        return tag.contains(TOTAL_KEY) ? tag.getInt(TOTAL_KEY) : 1;
    }

    /** Ends the charge session and clears every key it used. */
    private static void clear(AbstractGolemEntity<?, ?> golem, CompoundTag tag) {
        tag.remove(ACTIVE_KEY);
        tag.remove(START_KEY);
        tag.remove(TICKS_KEY);
        tag.remove(LEFT_KEY);
        tag.remove(TOTAL_KEY);
        tag.remove(PAUSE_KEY);
        tag.remove(FROM_X_KEY);
        tag.remove(FROM_Y_KEY);
        tag.remove(FROM_Z_KEY);
        tag.remove(TO_X_KEY);
        tag.remove(TO_Y_KEY);
        tag.remove(TO_Z_KEY);
        tag.remove(HITS_KEY);
        golem.setNoGravity(false);
        golem.fallDistance = 0;
    }

    /** True for damage this mod applies itself; those must never start a charge. */
    private static boolean isOwnDamage(DamageSource source) {
        String id = source.getMsgId();
        return "key_sword_magic".equals(id) || "lion_slash".equals(id)
                || "sword_rain".equals(id) || "stance_magic".equals(id)
                || "flame_magic".equals(id) || "genmu_zero".equals(id);
    }
}
