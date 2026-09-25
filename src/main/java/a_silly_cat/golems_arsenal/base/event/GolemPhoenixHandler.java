package a_silly_cat.golems_arsenal.base.event;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.base.upgrade.GolemFlagModifier;
import a_silly_cat.golems_arsenal.base.upgrade.GolemUpgrades;
import a_silly_cat.golems_arsenal.init.ModNetwork;
import a_silly_cat.golems_arsenal.network.ClientboundFlipPacket;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * 凤凰天驱（Tales 系列 鳳凰天駆 的致敬版）：傀儡近战命中后，先向后上方缓慢升空（后空翻），顶点略作悬停，
 * 再朝敌人方向快速下坠（下坠过程不旋转），落地爆出火焰与爆炸伤害，收势时头指向敌人。
 * <ul>
 *   <li>上升段：只有 {@code 0.1 × 物理伤害} 的火焰伤害，且**无视无敌帧**；</li>
 *   <li>落地段：{@code 物理伤害 × 1.5 × (1.2 × L2火焰加成 + L2爆炸加成)}，范围伤害并点燃；</li>
 *   <li>下坠落点沿用冲撞那套"敌人处在路径上"的思路，这里是 1.5 倍距离（敌人在 2/3 处）；</li>
 *   <li>整段演出 2 秒（20 / 5 / 8 / 7 tick），期间位置由服务端每 tick 固定、且**打不出伤害**
 *   （否则会看到傀儡在天上一边飞一边揍人的奇观），客户端只负责姿态。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID)
public final class GolemPhoenixHandler {

    // ---- 时间轴（tick）。总时长 = 40 tick ≈ 2 秒。旋转与停顿仍保持 4:1 的比例 ----
    public static final int RISE_TICKS = 12;
    public static final int HANG_TICKS = 3;
    public static final int DIVE_TICKS = 5;
    public static final int LAND_TICKS = 5;
    /** 后空翻结束角度（度）。待定值：180~270 之间都可以，先取 240。 */
    public static final float FLIP_DEGREES = 240.0F;

    private static final int PHASE_RISE = 0;
    private static final int PHASE_HANG = 1;
    private static final int PHASE_DIVE = 2;
    private static final int PHASE_LAND = 3;
    /** 秘奥义（隐藏彩蛋）：落地之后的追冲与 5 连爆。 */
    private static final int PHASE_MYSTIC_DASH = 4;
    private static final int PHASE_MYSTIC_BLAST = 5;

    // ---- 秘奥义（绯凰绝炎冲）：仅在安装了 mgdp 时成立，且不写进升级描述 ----
    /** 凤凰天驱被释放超过这个次数后，傀儡"炉火纯青"，可以被授权。 */
    public static final int MYSTIC_UNLOCK_USES = 50;
    /** 首次授权消耗的傀儡血量（不会致死）。 */
    private static final float MYSTIC_FIRST_COST = 200.0F;
    /** 授权冷却（tick）：一段时间内不能再授权任意傀儡。 */
    /**
     * 授权冷却（tick）：一分钟。玩家侧那把是"所有傀儡共享"的，傀儡侧那把（{@link #MYSTIC_CD_KEY}）
     * 管的是"同一只傀儡"，手动授权与自救授权都会刷新它。
     */
    public static final int MYSTIC_AUTH_COOLDOWN = 1200;
    private static final int MYSTIC_DASH_TICKS = 6;
    private static final double MYSTIC_DASH_DISTANCE = 8.0;
    private static final int MYSTIC_BLASTS = 5;
    private static final int MYSTIC_BLAST_INTERVAL = 3;
    /**
     * 绝炎冲 5 连爆的**总**伤害 = 落地爆 × 该倍率（每爆再除以 {@link #MYSTIC_BLASTS}）。
     * 1.0 = 总和等于一次落地爆（旧行为，每爆只有 1/5，配合护甲/伤害上限会明显吃亏）；
     * {@link #MYSTIC_BLASTS} = 每一爆都按完整落地爆结算；3.0 = 每爆约落地爆的 60%。
     */
    private static final double MYSTIC_DAMAGE_MULTIPLIER = 6.0;

    /**
     * 傀儡自救授权：玩家在混战里未必有空按键，所以傀儡自己判断危险并拿许可。
     * 威胁值 = 附近敌对目标当前血量之和 ÷ 自身生命百分比。
     */
    private static final double AUTO_AUTH_THREAT = 1000.0;
    private static final double AUTO_AUTH_RADIUS = 16.0;
    private static final int AUTO_AUTH_INTERVAL = 10;
    /** 生命百分比下限：残血时不让威胁值被除爆（分母最多算成 0.25，即最多 4 倍）。 */
    private static final double AUTO_AUTH_HP_FLOOR = 0.25;
    /** 生命百分比低于这个值直接不判，预防临死那一下误触发。 */
    private static final double AUTO_AUTH_HP_MIN = 0.10;
    /**
     * 授权凭证：命令手杖，以及整套万能手杖（{@code modulargolems:golem_omni_wand} 标签里的 5 种
     * 万能手杖：命令/回收/召唤/骑乘/编队）。万能手杖是本家手杖的"多合一"版本，玩家持有它时
     * 同样算作在用手杖操作傀儡。
     */
    private static final ResourceLocation MYSTIC_WAND_ID =
            new ResourceLocation("modulargolems", "command_wand");
    private static final TagKey<Item> MYSTIC_WAND_TAG =
            ItemTags.create(new ResourceLocation("modulargolems", "golem_omni_wand"));

    /** 上升高度 = 体高 × 该系数。 */
    private static final double RISE_HEIGHT_FACTOR = 1.6;
    /** 上升时向后（远离敌人方向）移动的距离。 */
    private static final double RISE_BACKWARD = 2.5;
    /** 下坠落点 = 原点 + 方向 × (到敌人距离 × 该系数)，即敌人在路径的 2/3 处。 */
    private static final double DIVE_DISTANCE_FACTOR = 1.5;
    private static final double DIVE_MIN_DISTANCE = 5.0;
    private static final double DIVE_MAX_DISTANCE = 16.0;
    private static final double HIT_PADDING = 1.0;

    /** 上升段伤害 = 攻击力 × 该系数（无视无敌帧的火焰伤害）。 */
    private static final double RISE_DAMAGE_RATIO = 0.1;
    /** 落地伤害 = 攻击力 × 物理系数 × (火焰系数 × L2火焰加成 + L2爆炸加成)。 */
    private static final double IMPACT_PHYSICAL_RATIO = 1.5;
    private static final double IMPACT_FIRE_RATIO = 1.2;
    private static final double IMPACT_RADIUS = 3.5;
    private static final int RISE_FIRE_SECONDS = 2;
    private static final int IMPACT_FIRE_SECONDS = 5;

    /** 冷却从演出结束开始算。 */
    private static final int COOLDOWN_TICKS = 120;

    private static final String CD_KEY = "GolemsArsenalPhoenixCooldown";
    private static final String PHASE_KEY = "GolemsArsenalPhoenixPhase";
    private static final String START_KEY = "GolemsArsenalPhoenixStart";
    private static final String TICKS_KEY = "GolemsArsenalPhoenixTicks";
    private static final String BASE_Y_KEY = "GolemsArsenalPhoenixBaseY";
    private static final String APEX_Y_KEY = "GolemsArsenalPhoenixApexY";
    private static final String ORIGIN_X_KEY = "GolemsArsenalPhoenixOriginX";
    private static final String ORIGIN_Z_KEY = "GolemsArsenalPhoenixOriginZ";
    private static final String DIR_X_KEY = "GolemsArsenalPhoenixDirX";
    private static final String DIR_Z_KEY = "GolemsArsenalPhoenixDirZ";
    private static final String LAND_X_KEY = "GolemsArsenalPhoenixLandX";
    private static final String LAND_Y_KEY = "GolemsArsenalPhoenixLandY";
    private static final String LAND_Z_KEY = "GolemsArsenalPhoenixLandZ";
    private static final String HITS_KEY = "GolemsArsenalPhoenixHits";
    private static final String USES_KEY = "GolemsArsenalPhoenixUses";
    private static final String MYSTIC_READY_KEY = "GolemsArsenalMysticReady";
    private static final String MYSTIC_ARMED_KEY = "GolemsArsenalMysticArmed";
    private static final String MYSTIC_PAID_KEY = "GolemsArsenalMysticPaid";
    private static final String MYSTIC_PENDING_KEY = "GolemsArsenalMysticPending";
    private static final String MYSTIC_FROM_X_KEY = "GolemsArsenalMysticFromX";
    private static final String MYSTIC_FROM_Y_KEY = "GolemsArsenalMysticFromY";
    private static final String MYSTIC_FROM_Z_KEY = "GolemsArsenalMysticFromZ";
    private static final String MYSTIC_TO_X_KEY = "GolemsArsenalMysticToX";
    private static final String MYSTIC_TO_Y_KEY = "GolemsArsenalMysticToY";
    private static final String MYSTIC_TO_Z_KEY = "GolemsArsenalMysticToZ";
    private static final String MYSTIC_BLAST_DONE_KEY = "GolemsArsenalMysticBlastDone";
    private static final String AUTH_CD_KEY = "GolemsArsenalMysticAuthCooldown";
    /** 傀儡侧授权冷却：手动授权与自救授权共用，避免同一只傀儡一分钟内反复拿许可。 */
    private static final String MYSTIC_CD_KEY = "GolemsArsenalMysticCooldown";

    private static final ResourceLocation FIRE_DAMAGE_ATTR =
            new ResourceLocation("l2damagetracker", "fire_damage");
    private static final ResourceLocation EXPLOSION_DAMAGE_ATTR =
            new ResourceLocation("l2damagetracker", "explosion_damage");
    private static final ResourceKey<DamageType> PHOENIX_FIRE =
            ResourceKey.create(Registries.DAMAGE_TYPE, Golems_arsenal.id("phoenix_fire"));
    private static final ResourceKey<DamageType> PHOENIX_IMPACT =
            ResourceKey.create(Registries.DAMAGE_TYPE, Golems_arsenal.id("phoenix_impact"));

    private static Attribute fireDamageCache;
    private static Attribute explosionDamageCache;
    private static boolean lookedUpDamageAttrs;

    private GolemPhoenixHandler() {
    }

    /** 触发：傀儡近战命中。不取消这一击（不做伤害顺序门控）。 */
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
                || !GolemFlagModifier.hasUpgrade(golem, GolemUpgrades.PHOENIX.get())
                || isOwnDamage(event.getSource())) {
            return;
        }
        CompoundTag tag = golem.getPersistentData();
        if (tag.contains(PHASE_KEY) || golem.level().getGameTime() < tag.getLong(CD_KEY)) {
            return;
        }
        start(golem, tag, victim);
    }

    /**
     * 演出期间傀儡打不出伤害：位置被我们钉住、姿态在演凤凰天驱，这时候它还能腾空揍人就很出戏。
     * 只拦"傀儡自己发起的普通攻击"（近战挥击、射出去的箭），我们自己的伤害类型与凤凰自己的
     * 两段伤害不受影响。
     */
    @SubscribeEvent
    public static void onGolemAttackWhilePerforming(LivingAttackEvent event) {
        if (event.getEntity().level().isClientSide) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        if (!golem.getPersistentData().contains(PHASE_KEY) || isOwnDamage(event.getSource())) {
            return;
        }
        event.setCanceled(true);
    }

    private static void start(AbstractGolemEntity<?, ?> golem, CompoundTag tag, LivingEntity victim) {
        Vec3 from = golem.position();
        double dx = victim.getX() - from.x;
        double dz = victim.getZ() - from.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        Vec3 look = golem.getLookAngle();
        double nx = distance > 1.0E-4 ? dx / distance : look.x;
        double nz = distance > 1.0E-4 ? dz / distance : look.z;
        // 下坠落点：敌人在路径的 2/3 处（= 落点距离为到敌人距离的 1.5 倍），带上下限与安全收缩。
        double travel = Math.min(DIVE_MAX_DISTANCE,
                Math.max(DIVE_MIN_DISTANCE, distance * DIVE_DISTANCE_FACTOR));
        double landY = Math.abs(victim.getY() - from.y) <= 2.0 ? victim.getY() : from.y;
        Vec3 wanted = new Vec3(from.x + nx * travel, landY, from.z + nz * travel);
        Vec3 land = freeLanding(golem, from, wanted);

        tag.putDouble(ORIGIN_X_KEY, from.x);
        tag.putDouble(ORIGIN_Z_KEY, from.z);
        tag.putDouble(BASE_Y_KEY, from.y);
        tag.putDouble(APEX_Y_KEY, from.y + golem.getBbHeight() * RISE_HEIGHT_FACTOR);
        tag.putDouble(DIR_X_KEY, nx);
        tag.putDouble(DIR_Z_KEY, nz);
        tag.putDouble(LAND_X_KEY, land.x);
        tag.putDouble(LAND_Y_KEY, land.y);
        tag.putDouble(LAND_Z_KEY, land.z);
        tag.put(HITS_KEY, new CompoundTag());
        // 秘奥义（隐藏彩蛋）：累计释放次数，超过 50 次后标记为"已解锁"（不提示、不进聊天栏）；
        // 已经拿到"一次许可"的话，这一次凤凰天驱会在落地后追加秘奥义（许可是消耗品，用掉即消失）。
        int uses = tag.getInt(USES_KEY) + 1;
        tag.putInt(USES_KEY, uses);
        if (uses > MYSTIC_UNLOCK_USES && !tag.getBoolean(MYSTIC_READY_KEY)) {
            tag.putBoolean(MYSTIC_READY_KEY, true);
        }
        boolean pending = tag.getBoolean(MYSTIC_ARMED_KEY);
        tag.putBoolean(MYSTIC_PENDING_KEY, pending);
        tag.remove(MYSTIC_ARMED_KEY);
        int mysticTicks = pending ? MYSTIC_DASH_TICKS + MYSTIC_BLASTS * MYSTIC_BLAST_INTERVAL : 0;
        tag.putLong(CD_KEY, golem.level().getGameTime()
                + RISE_TICKS + HANG_TICKS + DIVE_TICKS + LAND_TICKS + mysticTicks + COOLDOWN_TICKS);
        golem.swing(InteractionHand.MAIN_HAND);
        golem.setNoGravity(true);
        golem.setDeltaMovement(Vec3.ZERO);
        faceTowards(golem, new Vec3(nx, 0, nz));
        // 客户端只负责姿态：一个包就能让傀儡做后空翻，位置仍旧由服务端每 tick 广播。
        ModNetwork.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> golem),
                new ClientboundFlipPacket(golem.getId(), FLIP_DEGREES, true));
        enterPhase(golem, tag, PHASE_RISE, RISE_TICKS);
    }

    /** 演出推进：四阶段状态机，全程固定位置、不改速度、不累计坠落距离。 */
    @SubscribeEvent
    public static void onGolemTick(LivingEvent.LivingTickEvent event) {
        if (event.getEntity().level().isClientSide
                || !(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        CompoundTag tag = golem.getPersistentData();
        if (!tag.contains(PHASE_KEY)) {
            // 没在演出：顺便评估一次"傀儡自救授权"。
            maybeSelfAuthorize(golem, tag);
            return;
        }
        if (!golem.isAlive()) {
            clear(golem, tag);
            return;
        }
        long now = golem.level().getGameTime();
        int phase = tag.getInt(PHASE_KEY);
        int ticks = Math.max(1, tag.getInt(TICKS_KEY));
        double progress = Math.min(1.0, (double) (now - tag.getLong(START_KEY)) / ticks);
        double baseY = tag.getDouble(BASE_Y_KEY);
        double apexY = tag.getDouble(APEX_Y_KEY);
        double originX = tag.getDouble(ORIGIN_X_KEY);
        double originZ = tag.getDouble(ORIGIN_Z_KEY);
        double nx = tag.getDouble(DIR_X_KEY);
        double nz = tag.getDouble(DIR_Z_KEY);
        Vec3 land = new Vec3(tag.getDouble(LAND_X_KEY), tag.getDouble(LAND_Y_KEY),
                tag.getDouble(LAND_Z_KEY));

        switch (phase) {
            case PHASE_RISE -> {
                // 上升慢、向右后方退：ease-out 的竖直 + 水平的后退位移。
                double ease = easeOut(progress);
                double x = originX - nx * RISE_BACKWARD * ease;
                double z = originZ - nz * RISE_BACKWARD * ease;
                double y = baseY + (apexY - baseY) * ease;
                pin(golem, x, y, z);
                faceTowards(golem, new Vec3(nx, 0, nz));
                if (golem.level() instanceof ServerLevel server) {
                    flameTrail(server, golem, 4);
                }
                riseDamage(golem, tag);
                if (progress >= 1.0) {
                    enterPhase(golem, tag, PHASE_HANG, HANG_TICKS);
                }
                return;
            }
            case PHASE_HANG -> {
                // 顶点悬停：只做一点上下浮动，同时保持火焰环绕。
                double floatUp = Math.sin(Math.PI * progress) * 0.2;
                pin(golem, originX - nx * RISE_BACKWARD, apexY + floatUp,
                        originZ - nz * RISE_BACKWARD);
                if (golem.level() instanceof ServerLevel server) {
                    flameTrail(server, golem, 6);
                }
                riseDamage(golem, tag);
                if (progress >= 1.0) {
                    // 下坠重新计一次命中名单，让已经吃过上升小火的敌人照样吃落地那一下。
                    tag.put(HITS_KEY, new CompoundTag());
                    enterPhase(golem, tag, PHASE_DIVE, DIVE_TICKS);
                }
                return;
            }
            case PHASE_DIVE -> {
                // 下坠快、不旋转：ease-in 冲向落点。
                double ease = progress * progress;
                Vec3 from = new Vec3(originX - nx * RISE_BACKWARD, apexY,
                        originZ - nz * RISE_BACKWARD);
                Vec3 pos = from.lerp(land, ease);
                pin(golem, pos.x, pos.y, pos.z);
                faceTowards(golem, new Vec3(land.x - pos.x, 0, land.z - pos.z));
                if (golem.level() instanceof ServerLevel server) {
                    flameTrail(server, golem, 7);
                }
                if (progress >= 1.0) {
                    impact(golem, tag, new Vec3(originX - nx * RISE_BACKWARD, apexY,
                            originZ - nz * RISE_BACKWARD), land);
                    enterPhase(golem, tag, PHASE_LAND, LAND_TICKS);
                }
                return;
            }
            case PHASE_LAND -> {
                // 落地收势：站定、头指向敌人。
                pin(golem, land.x, land.y, land.z);
                LivingEntity target = golem.getTarget();
                if (target != null && target.isAlive() && target != golem) {
                    faceTowards(golem, target.position().subtract(golem.position()));
                }
                if (progress >= 1.0) {
                    if (tag.getBoolean(MYSTIC_PENDING_KEY)) {
                        // 秘奥义（隐藏彩蛋）：落地后"回头"再追冲一次。方向取傀儡当前朝向
                        // （落地阶段已经把它的头转向敌人），而不是下坠方向 nx/nz —— 下坠的落点
                        // 本来就在敌人身后，沿 nx/nz 继续冲就是顺着冲过头的方向往外飞，看起来像放反了。
                        double[] dashDir = mysticDashDir(golem, nx, nz);
                        tag.putDouble(DIR_X_KEY, dashDir[0]);
                        tag.putDouble(DIR_Z_KEY, dashDir[1]);
                        startMysticDash(golem, tag, land, dashDir[0], dashDir[1]);
                        return;
                    }
                    clear(golem, tag);
                }
            }
            case PHASE_MYSTIC_DASH -> {
                // 秘奥义·追冲：贴地向前冲，两侧拖着一路火焰。
                Vec3 from = mysticFrom(tag);
                Vec3 to = mysticTo(tag);
                Vec3 pos = from.lerp(to, easeOut(progress));
                pin(golem, pos.x, pos.y, pos.z);
                faceTowards(golem, to.subtract(from));
                if (golem.level() instanceof ServerLevel server) {
                    mysticTrail(server, golem, pos, nx, nz);
                }
                if (progress >= 1.0) {
                    tag.putInt(MYSTIC_BLAST_DONE_KEY, 0);
                    enterPhase(golem, tag, PHASE_MYSTIC_BLAST,
                            MYSTIC_BLASTS * MYSTIC_BLAST_INTERVAL);
                }
            }
            case PHASE_MYSTIC_BLAST -> {
                // 秘奥义·5 连爆：沿追冲路径等距引爆，范围限制在走廊半宽内，粒子向上喷。
                Vec3 from = mysticFrom(tag);
                Vec3 to = mysticTo(tag);
                pin(golem, to.x, to.y, to.z);
                int reached = (int) Math.floor(progress * MYSTIC_BLASTS);
                for (int i = tag.getInt(MYSTIC_BLAST_DONE_KEY);
                     i <= Math.min(reached, MYSTIC_BLASTS - 1); i++) {
                    // 每一爆重新计一次命中名单：连续 5 爆要能真的打 5 下。
                    tag.put(HITS_KEY, new CompoundTag());
                    mysticBlast(golem, tag, from.lerp(to, (i + 1) / (double) MYSTIC_BLASTS), i);
                    tag.putInt(MYSTIC_BLAST_DONE_KEY, i + 1);
                }
                if (progress >= 1.0) {
                    clear(golem, tag);
                }
            }
            default -> clear(golem, tag);
        }
    }

    /** 上升段的火焰小伤害：0.1 × 攻击力，清掉受击无敌帧后结算，所以"无视无敌"。 */
    private static void riseDamage(AbstractGolemEntity<?, ?> golem, CompoundTag tag) {
        double base = golem.getAttributeValue(Attributes.ATTACK_DAMAGE);
        double damage = base * RISE_DAMAGE_RATIO;
        if (damage <= 0) {
            return;
        }
        for (LivingEntity target : hitBox(golem, golem.position(), tag)) {
            target.invulnerableTime = 0;
            target.hurt(phoenixFireSource(golem), (float) damage);
            target.setSecondsOnFire(RISE_FIRE_SECONDS);
        }
    }

    /**
     * 落地爆：{@code 攻击力 × 1.5 × (1.2 × L2 火焰加成 + L2 爆炸加成)}，范围伤害并点燃。
     * 两个加成直接读 l2damagetracker 的属性（未安装时为 1.0）。
     */
    private static void impact(AbstractGolemEntity<?, ?> golem, CompoundTag tag, Vec3 diveFrom,
                               Vec3 land) {
        double base = golem.getAttributeValue(Attributes.ATTACK_DAMAGE) * IMPACT_PHYSICAL_RATIO;
        double fire = fireDamageBonus(golem);
        double explosion = explosionDamageBonus(golem);
        double damage = base * (IMPACT_FIRE_RATIO * fire + explosion);
        if (damage <= 0) {
            return;
        }
        // 下坠经过的整条路径 + 落点周围都按落地那一档结算（每个敌人只吃一次）：这样落点设在
        // "敌人处 1.5 倍"、敌人位于路径 2/3 处时，被俯冲的那个敌人一定会吃到落地伤害。
        List<LivingEntity> targets = new java.util.ArrayList<>();
        double length = diveFrom.distanceTo(land);
        int steps = Math.max(1, (int) Math.ceil(length / 0.75));
        for (int i = 0; i <= steps; i++) {
            targets.addAll(hitBox(golem, diveFrom.lerp(land, (double) i / steps), tag));
        }
        targets.addAll(hitBox(golem, land, tag, IMPACT_RADIUS));
        for (LivingEntity target : targets) {
            target.hurt(phoenixImpactSource(golem), (float) damage);
            target.setSecondsOnFire(IMPACT_FIRE_SECONDS);
        }
        if (golem.level() instanceof ServerLevel server) {
            // 粒子按"够看就好"的量给：傀儡数量多起来时，粒子往往比伤害计算更吃性能。
            server.sendParticles(ParticleTypes.EXPLOSION, land.x, land.y + 0.2, land.z,
                    1, 0, 0, 0, 0);
            server.sendParticles(ParticleTypes.FLAME, land.x, land.y + 0.3, land.z,
                    20, IMPACT_RADIUS * 0.4, 0.4, IMPACT_RADIUS * 0.4, 0.06);
            server.sendParticles(ParticleTypes.LAVA, land.x, land.y + 0.2, land.z,
                    6, IMPACT_RADIUS * 0.3, 0.2, IMPACT_RADIUS * 0.3, 0.0);
            server.playSound(null, land.x, land.y, land.z, SoundEvents.GENERIC_EXPLODE,
                    SoundSource.HOSTILE, 1.2F, 0.9F);
        }
    }

    /** 命中名单 + 判定盒；每个敌人每段只吃一次。 */
    private static List<LivingEntity> hitBox(AbstractGolemEntity<?, ?> golem, Vec3 pos,
                                             CompoundTag tag) {
        return hitBox(golem, pos, tag, -1);
    }

    private static List<LivingEntity> hitBox(AbstractGolemEntity<?, ?> golem, Vec3 pos,
                                             CompoundTag tag, double fixedRadius) {
        double half = fixedRadius > 0
                ? fixedRadius
                : Math.max(0.7, golem.getBbWidth() * 0.5 + HIT_PADDING);
        double height = fixedRadius > 0 ? fixedRadius + 1.0 : golem.getBbHeight() + 0.5;
        AABB box = AABB.ofSize(pos.add(0, golem.getBbHeight() * 0.5, 0),
                half * 2, height * 2, half * 2);
        CompoundTag hits = tag.getCompound(HITS_KEY);
        List<LivingEntity> targets = golem.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive() && e != golem && !e.isAlliedTo(golem)
                        && !hits.contains(e.getStringUUID()));
        for (LivingEntity target : targets) {
            hits.putBoolean(target.getStringUUID(), true);
        }
        return targets;
    }

    private static void flameTrail(ServerLevel server, AbstractGolemEntity<?, ?> golem, int count) {
        Vec3 pos = golem.position();
        server.sendParticles(ParticleTypes.FLAME, pos.x, pos.y + golem.getBbHeight() * 0.5, pos.z,
                count, golem.getBbWidth() * 0.5, golem.getBbHeight() * 0.4,
                golem.getBbWidth() * 0.5, 0.02);
        server.sendParticles(ParticleTypes.CLOUD, pos.x, pos.y + 0.1, pos.z,
                3, golem.getBbWidth() * 0.3, 0.05, golem.getBbWidth() * 0.3, 0.01);
    }

    private static void pin(AbstractGolemEntity<?, ?> golem, double x, double y, double z) {
        golem.setNoGravity(true);
        golem.setPos(x, y, z);
        golem.setDeltaMovement(Vec3.ZERO);
        golem.fallDistance = 0;
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

    /**
     * 秘奥义追冲的水平方向：优先追当前活着的目标（落地阶段已经把傀儡转过去对着它），
     * 没有目标时退回傀儡实际朝向，再退回下坠方向的反向。返回单位向量 {x, z}。
     */
    private static double[] mysticDashDir(AbstractGolemEntity<?, ?> golem, double nx, double nz) {
        LivingEntity target = golem.getTarget();
        if (target != null && target.isAlive() && target != golem) {
            Vec3 toTarget = target.position().subtract(golem.position());
            double len = toTarget.horizontalDistance();
            if (len > 1.0E-4) {
                return new double[]{toTarget.x / len, toTarget.z / len};
            }
        }
        return facingDir(golem, -nx, -nz);
    }

    /** 傀儡实际朝向的水平单位向量；退化时用 fallback，再退化到 +Z。 */
    private static double[] facingDir(AbstractGolemEntity<?, ?> golem, double fallbackX, double fallbackZ) {
        Vec3 look = golem.getLookAngle();
        double len = Math.sqrt(look.x * look.x + look.z * look.z);
        if (len > 1.0E-4) {
            return new double[]{look.x / len, look.z / len};
        }
        double fl = Math.sqrt(fallbackX * fallbackX + fallbackZ * fallbackZ);
        if (fl > 1.0E-4) {
            return new double[]{fallbackX / fl, fallbackZ / fl};
        }
        return new double[]{0.0, 1.0};
    }

    private static Vec3 freeLanding(AbstractGolemEntity<?, ?> golem, Vec3 from, Vec3 wanted) {
        Vec3 delta = wanted.subtract(from);
        double length = delta.horizontalDistance();
        if (length <= 1.0E-4) {
            return wanted;
        }
        Vec3 dir = new Vec3(delta.x, 0, delta.z).scale(1.0 / length);
        double allowed = length;
        Vec3 cursor = new Vec3(from.x + dir.x * allowed, wanted.y, from.z + dir.z * allowed);
        while (allowed > DIVE_MIN_DISTANCE
                && !golem.level().noCollision(golem, golem.getBoundingBox().move(cursor.subtract(from)))) {
            allowed = Math.max(DIVE_MIN_DISTANCE, allowed - 0.25);
            cursor = new Vec3(from.x + dir.x * allowed, wanted.y, from.z + dir.z * allowed);
        }
        return cursor;
    }

    private static void enterPhase(AbstractGolemEntity<?, ?> golem, CompoundTag tag,
                                   int phase, int ticks) {
        tag.putInt(PHASE_KEY, phase);
        tag.putInt(TICKS_KEY, ticks);
        tag.putLong(START_KEY, golem.level().getGameTime());
    }

    private static void clear(AbstractGolemEntity<?, ?> golem, CompoundTag tag) {
        tag.remove(PHASE_KEY);
        tag.remove(START_KEY);
        tag.remove(TICKS_KEY);
        tag.remove(BASE_Y_KEY);
        tag.remove(APEX_Y_KEY);
        tag.remove(ORIGIN_X_KEY);
        tag.remove(ORIGIN_Z_KEY);
        tag.remove(DIR_X_KEY);
        tag.remove(DIR_Z_KEY);
        tag.remove(LAND_X_KEY);
        tag.remove(LAND_Y_KEY);
        tag.remove(LAND_Z_KEY);
        tag.remove(HITS_KEY);
        tag.remove(MYSTIC_PENDING_KEY);
        tag.remove(MYSTIC_FROM_X_KEY);
        tag.remove(MYSTIC_FROM_Y_KEY);
        tag.remove(MYSTIC_FROM_Z_KEY);
        tag.remove(MYSTIC_TO_X_KEY);
        tag.remove(MYSTIC_TO_Y_KEY);
        tag.remove(MYSTIC_TO_Z_KEY);
        tag.remove(MYSTIC_BLAST_DONE_KEY);
        golem.setNoGravity(false);
        golem.setDeltaMovement(Vec3.ZERO);
        golem.fallDistance = 0;
    }

    private static double easeOut(double t) {
        double c = Math.max(0.0, Math.min(1.0, t));
        return 1.0 - (1.0 - c) * (1.0 - c);
    }

    /** L2 火焰伤害加成（l2damagetracker:fire_damage），未安装时按 1.0。 */
    private static double fireDamageBonus(AbstractGolemEntity<?, ?> golem) {
        Attribute attr = damageAttribute(FIRE_DAMAGE_ATTR);
        return attr == null ? 1.0 : Math.max(0.0, golem.getAttributeValue(attr));
    }

    /** L2 爆炸伤害加成（l2damagetracker:explosion_damage），未安装时按 1.0。 */
    private static double explosionDamageBonus(AbstractGolemEntity<?, ?> golem) {
        Attribute attr = damageAttribute(EXPLOSION_DAMAGE_ATTR);
        return attr == null ? 1.0 : Math.max(0.0, golem.getAttributeValue(attr));
    }

    private static Attribute damageAttribute(ResourceLocation id) {
        return ForgeRegistries.ATTRIBUTES.getValue(id);
    }

    /** 上升段的火焰伤害源（伤害类型 phoenix_fire）。 */
    private static DamageSource phoenixFireSource(AbstractGolemEntity<?, ?> golem) {
        var registry = golem.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        return new DamageSource(registry.getHolderOrThrow(PHOENIX_FIRE), golem);
    }

    /** 落地爆的伤害源（伤害类型 phoenix_impact）。 */
    private static DamageSource phoenixImpactSource(AbstractGolemEntity<?, ?> golem) {
        var registry = golem.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        return new DamageSource(registry.getHolderOrThrow(PHOENIX_IMPACT), golem);
    }

    /**
     * 授权（隐藏彩蛋的唯一入口，由键位 + 命令手杖那条线调用）：成功返回 true。
     * <p>
     * 条件：安装了 mgdp、傀儡持有凤凰天驱且"炉火纯青"（释放次数超过 {@link #MYSTIC_UNLOCK_USES}）、
     * 玩家自己的授权冷却已过（授权冷却对所有傀儡共享）。首次授权消耗该傀儡
     * {@link #MYSTIC_FIRST_COST} 点血量（直接扣血、不会致死、不吃护甲与吸收），之后同一只傀儡
     * 再授权不再扣血。成功后该傀儡的下一次凤凰天驱会在落地后追加秘奥义演出。
     */
    public static boolean authorize(Player player, AbstractGolemEntity<?, ?> golem) {
        if (!ModList.get().isLoaded("mgdp")) {
            return false;
        }
        CompoundTag tag = golem.getPersistentData();
        if (!GolemFlagModifier.hasUpgrade(golem, GolemUpgrades.PHOENIX.get())
                || !tag.getBoolean(MYSTIC_READY_KEY)) {
            return false;
        }
        long now = golem.level().getGameTime();
        CompoundTag playerTag = player.getPersistentData();
        // 玩家侧冷却（所有傀儡共享）+ 傀儡侧冷却（同一只傀儡）都要过。
        if (now < playerTag.getLong(AUTH_CD_KEY) || now < tag.getLong(MYSTIC_CD_KEY)) {
            return false;
        }
        boolean first = !tag.getBoolean(MYSTIC_PAID_KEY);
        if (first) {
            golem.setHealth(Math.max(1.0F, golem.getHealth() - MYSTIC_FIRST_COST));
            tag.putBoolean(MYSTIC_PAID_KEY, true);
        }
        tag.putBoolean(MYSTIC_ARMED_KEY, true);
        playerTag.putLong(AUTH_CD_KEY, now + MYSTIC_AUTH_COOLDOWN);
        tag.putLong(MYSTIC_CD_KEY, now + MYSTIC_AUTH_COOLDOWN);
        if (golem.level() instanceof ServerLevel server) {
            server.playSound(null, golem.getX(), golem.getY(), golem.getZ(),
                    SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 1.0F, 0.8F);
        }
        return true;
    }

    /** 玩家手上是否拿着命令手杖或万能手杖（授权动作的凭证）。 */
    public static boolean holdsCommandWand(Player player) {
        return isMysticWand(player.getMainHandItem()) || isMysticWand(player.getOffhandItem());
    }

    private static boolean isMysticWand(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Item wand = ForgeRegistries.ITEMS.getValue(MYSTIC_WAND_ID);
        if (wand != null && stack.is(wand)) {
            return true;
        }
        // 万能手杖走本家的物品标签；标签没加载时 is() 只会返回 false，不会抛异常。
        return stack.is(MYSTIC_WAND_TAG);
    }

    /** 傀儡是否已经到了可以被授权的时候（用于客户端提示/按键过滤）。 */
    public static boolean canBeAuthorized(AbstractGolemEntity<?, ?> golem) {
        return ModList.get().isLoaded("mgdp")
                && golem.getPersistentData().getBoolean(MYSTIC_READY_KEY)
                && GolemFlagModifier.hasUpgrade(golem, GolemUpgrades.PHOENIX.get());
    }

    /**
     * 傀儡自救授权：没有玩家按键，傀儡自己在"打不过"的时候拿一次许可。
     * <p>
     * 威胁值 = 附近敌对目标当前血量之和 ÷ 自身生命百分比（分母下限 {@link #AUTO_AUTH_HP_FLOOR}）；
     * 生命百分比低于 {@link #AUTO_AUTH_HP_MIN} 直接不判，避免临死那一下误触发。
     * 超过 {@link #AUTO_AUTH_THREAT} 就给自己发一次许可（不扣血；只刷新傀儡侧的
     * {@link #MYSTIC_AUTH_COOLDOWN} 冷却，不动玩家侧那把）。
     * 每 {@link #AUTO_AUTH_INTERVAL} tick 才评估一次，避免每 tick 扫一遍附近实体。
     */
    private static void maybeSelfAuthorize(AbstractGolemEntity<?, ?> golem, CompoundTag tag) {
        if (golem.level().getGameTime() % AUTO_AUTH_INTERVAL != 0
                || !ModList.get().isLoaded("mgdp")
                || !tag.getBoolean(MYSTIC_READY_KEY)
                || tag.getBoolean(MYSTIC_ARMED_KEY)
                || golem.level().getGameTime() < tag.getLong(MYSTIC_CD_KEY)
                || !GolemFlagModifier.hasUpgrade(golem, GolemUpgrades.PHOENIX.get())) {
            return;
        }
        double maxHp = golem.getMaxHealth();
        double frac = maxHp <= 0.0 ? 0.0 : golem.getHealth() / maxHp;
        if (frac < AUTO_AUTH_HP_MIN) {
            return;
        }
        double divisor = Math.max(AUTO_AUTH_HP_FLOOR, frac);
        double threat = 0.0;
        AABB box = golem.getBoundingBox().inflate(AUTO_AUTH_RADIUS);
        for (LivingEntity enemy : golem.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e != golem && e.isAlive() && !e.isAlliedTo(golem)
                        && (e instanceof Enemy || (e instanceof Mob mob && mob.getTarget() == golem)))) {
            threat += enemy.getHealth();
        }
        threat /= divisor;
        if (threat < AUTO_AUTH_THREAT) {
            return;
        }
        tag.putBoolean(MYSTIC_ARMED_KEY, true);
        tag.putLong(MYSTIC_CD_KEY, golem.level().getGameTime() + MYSTIC_AUTH_COOLDOWN);
        if (golem.level() instanceof ServerLevel server) {
            server.playSound(null, golem.getX(), golem.getY(), golem.getZ(),
                    SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 1.0F, 1.1F);
        }
    }

    /**
     * 落地后开始追冲：沿传入的方向（秘奥义是"回头"，也就是当前朝向/敌人方向）贴地冲出去，
     * 落点做一次安全收缩。
     */
    private static void startMysticDash(AbstractGolemEntity<?, ?> golem, CompoundTag tag,
                                        Vec3 from, double nx, double nz) {
        Vec3 wanted = new Vec3(from.x + nx * MYSTIC_DASH_DISTANCE, from.y,
                from.z + nz * MYSTIC_DASH_DISTANCE);
        Vec3 to = freeLanding(golem, from, wanted);
        tag.putBoolean(MYSTIC_PENDING_KEY, false);
        tag.putDouble(MYSTIC_FROM_X_KEY, from.x);
        tag.putDouble(MYSTIC_FROM_Y_KEY, from.y);
        tag.putDouble(MYSTIC_FROM_Z_KEY, from.z);
        tag.putDouble(MYSTIC_TO_X_KEY, to.x);
        tag.putDouble(MYSTIC_TO_Y_KEY, to.y);
        tag.putDouble(MYSTIC_TO_Z_KEY, to.z);
        golem.setNoGravity(true);
        golem.setDeltaMovement(Vec3.ZERO);
        faceTowards(golem, new Vec3(nx, 0, nz));
        if (golem.level() instanceof ServerLevel server) {
            server.playSound(null, from.x, from.y, from.z, SoundEvents.BLAZE_SHOOT,
                    SoundSource.HOSTILE, 1.2F, 0.9F);
        }
        enterPhase(golem, tag, PHASE_MYSTIC_DASH, MYSTIC_DASH_TICKS);
    }

    private static Vec3 mysticFrom(CompoundTag tag) {
        return new Vec3(tag.getDouble(MYSTIC_FROM_X_KEY), tag.getDouble(MYSTIC_FROM_Y_KEY),
                tag.getDouble(MYSTIC_FROM_Z_KEY));
    }

    private static Vec3 mysticTo(CompoundTag tag) {
        return new Vec3(tag.getDouble(MYSTIC_TO_X_KEY), tag.getDouble(MYSTIC_TO_Y_KEY),
                tag.getDouble(MYSTIC_TO_Z_KEY));
    }

    /** 追冲时两侧的火墙：拖着火尾，还一路犁起泥石和烟。 */
    private static void mysticTrail(ServerLevel server, AbstractGolemEntity<?, ?> golem,
                                    Vec3 pos, double nx, double nz) {
        double px = -nz;
        double pz = nx;
        double half = Math.max(0.7, golem.getBbWidth() * 0.5 + HIT_PADDING);
        for (double side : new double[]{-1.0, 1.0}) {
            double x = pos.x + px * half * side;
            double z = pos.z + pz * half * side;
            server.sendParticles(ParticleTypes.FLAME, x, pos.y + 0.4, z,
                    4, 0.18, 0.28, 0.18, 0.03);
            // count = 0 时，后面的三个偏移量就是速度，于是火焰看上去被带着往前飘。
            server.sendParticles(ParticleTypes.FLAME, x, pos.y + 0.5, z, 0,
                    nx * 0.45, 0.06, nz * 0.45, 1.0);
            // 上升的火星
            server.sendParticles(ParticleTypes.LAVA, x, pos.y + 0.25, z, 0,
                    px * side * 0.06, 0.28, pz * side * 0.06, 1.0);
            // 被犁起来、往外上方飞的泥石
            server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK,
                            side < 0 ? Blocks.DIRT.defaultBlockState()
                                    : Blocks.COBBLESTONE.defaultBlockState()),
                    x, pos.y + 0.15, z, 0,
                    px * side * 0.28, 0.4, pz * side * 0.28, 1.0);
            // 尾后拖着的烟
            server.sendParticles(ParticleTypes.SMOKE, x - nx * 0.5, pos.y + 0.3, z - nz * 0.5,
                    0, -nx * 0.12, 0.05, -nz * 0.12, 1.0);
        }
    }

    /**
     * 秘奥义的一爆：伤害 = 落地爆 × {@link #MYSTIC_DAMAGE_MULTIPLIER} ÷ {@link #MYSTIC_BLASTS}
     * （旧行为是"总和等于一次落地爆"，每爆只有 1/5，被护甲/伤害上限逐次砍掉后总量会明显偏低），
     * 判定半径限制在走廊半宽以内（"范围不超过左右两方宽度"），特效换成向上喷的泥土/碎石/火焰。
     * 引爆前清一次受击无敌帧，保证 5 连爆各打各的。
     */
    private static void mysticBlast(AbstractGolemEntity<?, ?> golem, CompoundTag tag,
                                    Vec3 pos, int index) {
        double base = golem.getAttributeValue(Attributes.ATTACK_DAMAGE) * IMPACT_PHYSICAL_RATIO;
        double damage = base * (IMPACT_FIRE_RATIO * fireDamageBonus(golem)
                + explosionDamageBonus(golem)) * MYSTIC_DAMAGE_MULTIPLIER / MYSTIC_BLASTS;
        double half = Math.max(0.7, golem.getBbWidth() * 0.5 + HIT_PADDING);
        if (damage > 0) {
            for (LivingEntity target : hitBox(golem, pos, tag, half)) {
                target.invulnerableTime = 0;
                target.hurt(phoenixImpactSource(golem), (float) damage);
                target.setSecondsOnFire(IMPACT_FIRE_SECONDS);
            }
        }
        if (!(golem.level() instanceof ServerLevel server)) {
            return;
        }
        // 表现改为"土石向上喷发 + 地面烟尘四散 + 火柱"，不再冒原版爆炸白圈。
        // 关键：sendParticles 只有在 count = 0 时后三个偏移量才是"速度"，count > 0 只是在
        // 一个体积里随机散布（速度也是随机的），所以想看到喷发就必须一股一股地定向发。
        int clods = 12;
        for (int i = 0; i < clods; i++) {
            double angle = Math.PI * 2.0 * i / clods;
            double radius = half * 0.35;
            double vx = Math.cos(angle) * radius * 0.55;
            double vz = Math.sin(angle) * radius * 0.55;
            double vy = 2.0 + (i % 3) * 0.14;
            server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK,
                            i % 3 == 0 ? Blocks.COBBLESTONE.defaultBlockState()
                                    : Blocks.DIRT.defaultBlockState()),
                    pos.x + Math.cos(angle) * radius, pos.y + 0.2, pos.z + Math.sin(angle) * radius,
                    0, vx, vy, vz, 1.0);
        }
        // 第二层喷发：外圈、更快，泥土/圆石/砾石混着往上掀。
        int outerClods = 10;
        for (int i = 0; i < outerClods; i++) {
            double angle = Math.PI * 2.0 * i / outerClods;
            double radius = half * 0.7;
            double vx = Math.cos(angle) * radius * 0.8;
            double vz = Math.sin(angle) * radius * 0.8;
            double vy = 2.5 + (i % 2) * 0.5;
            var debris = i % 3 == 0 ? Blocks.COBBLESTONE.defaultBlockState()
                    : i % 3 == 1 ? Blocks.GRAVEL.defaultBlockState()
                    : Blocks.DIRT.defaultBlockState();
            server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, debris),
                    pos.x + Math.cos(angle) * radius, pos.y + 0.2, pos.z + Math.sin(angle) * radius,
                    0, vx, vy, vz, 1.0);
        }
        // 地面烟尘四散：一圈 12 个方向，烟 + 尘土 + 大烟 + 云，一路往外推。
        int dustDirs = 12;
        double push = Math.max(0.35, half * 0.6);
        for (int i = 0; i < dustDirs; i++) {
            double angle = Math.PI * 2.0 * i / dustDirs;
            double dx = Math.cos(angle) * push;
            double dz = Math.sin(angle) * push;
            server.sendParticles(ParticleTypes.SMOKE, pos.x, pos.y + 0.15, pos.z, 0, dx, 0.05, dz, 1.0);
            if (i % 2 == 0) {
                server.sendParticles(new BlockParticleOption(ParticleTypes.FALLING_DUST,
                                Blocks.DIRT.defaultBlockState()),
                        pos.x, pos.y + 0.2, pos.z, 0, dx * 1.15, 0.08, dz * 1.15, 1.0);
            }
            if (i % 3 == 0) {
                server.sendParticles(ParticleTypes.LARGE_SMOKE, pos.x, pos.y + 0.25, pos.z,
                        0, dx * 0.9, 0.1, dz * 0.9, 0.95);
            }
            if (i % 4 == 0) {
                server.sendParticles(ParticleTypes.CLOUD, pos.x, pos.y + 0.3, pos.z,
                        0, dx * 0.8, 0.14, dz * 0.8, 0.9);
            }
        }
        // 火花爆散：火焰 + 岩浆点子 + 落石碎屑。
        server.sendParticles(ParticleTypes.FLAME, pos.x, pos.y + 0.3, pos.z,
                28, half * 0.5, 0.45, half * 0.5, 0.08);
        server.sendParticles(ParticleTypes.LAVA, pos.x, pos.y + 0.25, pos.z,
                12, half * 0.4, 0.3, half * 0.4, 0.05);
        server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK,
                        Blocks.DIRT.defaultBlockState()),
                pos.x, pos.y + 0.2, pos.z, 6, half * 0.5, 0.25, half * 0.5, 1.0);
        server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK,
                        Blocks.COBBLESTONE.defaultBlockState()),
                pos.x, pos.y + 0.2, pos.z, 4, half * 0.4, 0.25, half * 0.4, 0.9);
        // 火柱：六股错开向上，撑住"炸开的那一下"。
        double[][] columns = {
                {0.0, 1.15}, {0.75, 1.5}, {1.5, 1.05},
                {2.25, 1.7}, {3.0, 1.25}, {3.75, 1.55},
        };
        for (int i = 0; i < columns.length; i++) {
            double angle = columns[i][0];
            double speed = columns[i][1];
            double ox = Math.cos(angle) * half * 0.35;
            double oz = Math.sin(angle) * half * 0.35;
            server.sendParticles(i % 2 == 0 ? ParticleTypes.LAVA : ParticleTypes.FLAME,
                    pos.x + ox, pos.y + 0.2, pos.z + oz, 0, ox * 0.25, speed, oz * 0.25, 1.3);
            server.sendParticles(ParticleTypes.FLAME, pos.x + ox, pos.y + 0.45, pos.z + oz,
                    0, ox * 0.3, speed * 0.8, oz * 0.3, 1.15);
        }
        // 闪光 + 余烬：给一个亮起来的瞬间，再留一圈飘散的余烟。
        server.sendParticles(ParticleTypes.FLASH, pos.x, pos.y + 0.6, pos.z, 1, 0, 0, 0, 0);
        server.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.x, pos.y + 0.3, pos.z,
                4, half * 0.3, 0.15, half * 0.3, 0.02);
        server.sendParticles(ParticleTypes.WHITE_ASH, pos.x, pos.y + 0.5, pos.z,
                10, half * 0.5, 0.4, half * 0.5, 0.02);
        server.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXPLODE,
                SoundSource.HOSTILE, 0.8F, 0.9F + index * 0.06F);
    }

    private static boolean isOwnDamage(DamageSource source) {
        String id = source.getMsgId();
        return "key_sword_magic".equals(id) || "lion_slash".equals(id)
                || "sword_rain".equals(id) || "stance_magic".equals(id)
                || "flame_magic".equals(id) || "phoenix_fire".equals(id)
                || "phoenix_impact".equals(id) || "genmu_zero".equals(id);
    }

}
