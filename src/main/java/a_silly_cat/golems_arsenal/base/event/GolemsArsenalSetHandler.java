package a_silly_cat.golems_arsenal.base.event;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.base.entity.LightPillarEntity;
import a_silly_cat.golems_arsenal.base.entity.PhantomBladeEntity;
import a_silly_cat.golems_arsenal.base.upgrade.GolemETankModifier;
import a_silly_cat.golems_arsenal.base.upgrade.GolemUpgradeLevels;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * "Omega 套装"联动（不是升级物品，而是四件套齐了才生效的机制）：
 * <pre>
 *   减震 II + 节约能源 II + E 罐 + W 罐（E 罐附属，负责把材料换成 FE）
 * </pre>
 * 齐套后获得：
 * <ol>
 *   <li>E 罐每 60 秒自动恢复 1 份修复材料；</li>
 *   <li>受击时四道光柱环绕自身 2 秒（冷却 5 秒），期间免疫伤害，光柱本身也会造成伤害；</li>
 *   <li>每 30 秒可以使用一次幻梦零（Z 光剑蓄力命中时甩出 6 道剑气）。</li>
 * </ol>
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID)
public final class GolemsArsenalSetHandler {
    /** 套装要求：减震与节约能源都要 2 级。 */
    private static final int REQUIRED_SPECIALIST_LEVEL = 2;
    private static final String SHOCK_BUFFER_ID = "golems_arsenal:shock_buffer";
    private static final String ENERGY_SAVER_ID = "golems_arsenal:energy_saver";
    private static final String E_TANK_ID = "golems_arsenal:e_tank";
    private static final String W_TANK_ID = "golems_arsenal:w_tank";

    /** E 罐自动补材料的间隔（tick）：60 秒。 */
    private static final int REFILL_TICKS = 1200;
    /** 光柱：持续 2 秒、冷却 5 秒。 */
    private static final int PILLAR_ACTIVE_TICKS = 40;
    private static final int PILLAR_CD_TICKS = 100;
    private static final int PILLAR_COUNT = 4;
    private static final double PILLAR_RING_RADIUS = 1.3;
    private static final double PILLAR_HIT_RADIUS = 1.6;
    private static final double PILLAR_DAMAGE_RATIO = 0.75;
    /** 幻梦零使用冷却（tick）：30 秒。 */
    private static final int GENMU_CD_TICKS = 600;
    /** 幻梦零分两波：第一波三角阵，隔这么多 tick 之后第二波平铺。 */
    private static final int GENMU_VOLLEY_GAP_TICKS = 6;

    private static final String REFILL_KEY = "GolemsArsenalSetRefill";
    private static final String PILLAR_CD_KEY = "GolemsArsenalSetPillarCd";
    private static final String PILLAR_UNTIL_KEY = "GolemsArsenalSetPillarUntil";
    private static final String GENMU_CD_KEY = "GolemsArsenalSetGenmuCd";
    /** 第二波（平铺）的预定发射时刻；0 = 没有待发的。 */
    private static final String GENMU_VOLLEY2_KEY = "GolemsArsenalSetGenmuVolley2";
    /** 四件套是否齐了。 */
    public static boolean isActive(AbstractGolemEntity<?, ?> golem) {
        return GolemUpgradeLevels.levelOf(golem, SHOCK_BUFFER_ID) >= REQUIRED_SPECIALIST_LEVEL
                && GolemUpgradeLevels.levelOf(golem, ENERGY_SAVER_ID) >= REQUIRED_SPECIALIST_LEVEL
                && GolemUpgradeLevels.levelOf(golem, E_TANK_ID) >= 1
                && GolemUpgradeLevels.levelOf(golem, W_TANK_ID) >= 1;
    }

    /** 每 60 秒给 E 罐补一份材料。 */
    @SubscribeEvent
    public static void onGolemTick(LivingEvent.LivingTickEvent event) {
        if (event.getEntity().level().isClientSide
                || !(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        // 第二波剑气：这个检查必须每 tick 做，不能等下面 20 tick 的节拍，不然两波会隔太开。
        long volley2 = golem.getPersistentData().getLong(GENMU_VOLLEY2_KEY);
        if (volley2 != 0L && golem.level().getGameTime() >= volley2) {
            golem.getPersistentData().putLong(GENMU_VOLLEY2_KEY, 0L);
            if (golem.isAlive() && isActive(golem)) {
                PhantomBladeEntity.fire(golem, golem.getMainHandItem(),
                        PhantomBladeEntity.Formation.FLAT);
            }
        }
        if (golem.tickCount % 20 != 0 || !isActive(golem)) {
            return;
        }
        long now = golem.level().getGameTime();
        CompoundTag tag = golem.getPersistentData();
        int tankLevel = GolemETankModifier.levelOf(golem);
        long next = tag.getLong(REFILL_KEY);
        if (next == 0L) {
            tag.putLong(REFILL_KEY, now + REFILL_TICKS);
            return;
        }
        if (now >= next) {
            tag.putLong(REFILL_KEY, now + REFILL_TICKS);
            GolemETankModifier.storeOne(golem, tankLevel);
        }
        // 幻梦零：套装生效时每 30 秒必定触发一次（不依赖蓄力命中，方便测试）。
        // 分两波：三角阵立刻发，平铺隔 GENMU_VOLLEY_GAP_TICKS 再发。
        if (now >= tag.getLong(GENMU_CD_KEY)) {
            tag.putLong(GENMU_CD_KEY, now + GENMU_CD_TICKS);
            PhantomBladeEntity.fire(golem, golem.getMainHandItem(),
                    PhantomBladeEntity.Formation.TRIANGLE);
            tag.putLong(GENMU_VOLLEY2_KEY, now + GENMU_VOLLEY_GAP_TICKS);
        }
    }

    /** 受击触发光柱（冷却 5 秒）。 */
    @SubscribeEvent
    public static void onGolemDamaged(LivingDamageEvent event) {
        if (event.getEntity().level().isClientSide
                || !(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)
                || !golem.isAlive()
                || !isActive(golem)) {
            return;
        }
        long now = golem.level().getGameTime();
        CompoundTag tag = golem.getPersistentData();
        if (now < tag.getLong(PILLAR_CD_KEY)) {
            return;
        }
        tag.putLong(PILLAR_CD_KEY, now + PILLAR_CD_TICKS);
        tag.putLong(PILLAR_UNTIL_KEY, now + PILLAR_ACTIVE_TICKS);
        double damage = golem.getAttributeValue(Attributes.ATTACK_DAMAGE) * PILLAR_DAMAGE_RATIO;
        LightPillarEntity.spawnRing(golem, PILLAR_COUNT, PILLAR_RING_RADIUS, (float) damage,
                PILLAR_HIT_RADIUS, PILLAR_ACTIVE_TICKS);
        if (golem.level() instanceof ServerLevel server) {
            server.playSound(null, golem.getX(), golem.getY(), golem.getZ(),
                    SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 1.0F, 0.7F);
            server.sendParticles(ParticleTypes.END_ROD, golem.getX(), golem.getY() + 0.4,
                    golem.getZ(), 24, PILLAR_RING_RADIUS, 0.4, PILLAR_RING_RADIUS, 0.02);
        }
    }

    /** 光柱期间无敌（不拦 bypass 类伤害）。 */
    @SubscribeEvent
    public static void onGolemAttacked(LivingAttackEvent event) {
        if (event.getEntity().level().isClientSide
                || !(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        if (golem.level().getGameTime() < golem.getPersistentData().getLong(PILLAR_UNTIL_KEY)
                && !event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            event.setCanceled(true);
        }
    }

    /** 幻梦零的 30 秒闸门：就绪则记下冷却并返回 true。 */
    public static boolean tryUseGenmuZero(AbstractGolemEntity<?, ?> golem) {
        if (!isActive(golem)) {
            return false;
        }
        long now = golem.level().getGameTime();
        CompoundTag tag = golem.getPersistentData();
        if (now < tag.getLong(GENMU_CD_KEY)) {
            return false;
        }
        tag.putLong(GENMU_CD_KEY, now + GENMU_CD_TICKS);
        return true;
    }

    private GolemsArsenalSetHandler() {
    }

}
