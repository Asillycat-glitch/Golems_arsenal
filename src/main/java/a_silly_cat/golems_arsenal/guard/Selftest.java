package a_silly_cat.golems_arsenal.guard;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.base.event.GolemsArsenalSetHandler;
import a_silly_cat.golems_arsenal.compat.artifact.DamageAdmission;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Week 4：对一个临时金属傀儡跑五种样本，只观察，不 ENFORCE。
 *
 * <p>每种样本使用一只新的临时傀儡；不碰玩家自己的傀儡。输出三列：
 * blocked（raw 没变）/ logged（审计计数增加）/ missed（变了但审计没报）。</p>
 *
 * <p>这是测试脚手架：sample 的写只落在临时实体上；命令需要 OP；stop 会清理当前临时实体。</p>
 */
public final class Selftest {

    private static final float EPS = 1.0E-3F;
    private static final String TEST_NAME = "GA Selftest";

    private static boolean running;
    private static AbstractGolemEntity<?, ?> current;
    private static List<Row> lastReport = List.of();

    private Selftest() {
    }

    public static boolean isRunning() {
        return running;
    }

    public static synchronized List<Row> run(ServerPlayer player) {
        if (running) {
            return List.of(new Row("busy", 0, 0, false, false, false, "already running"));
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return List.of(new Row("env", 0, 0, false, false, false, "not a server level"));
        }
        running = true;
        List<Row> rows = new ArrayList<>();
        try {
            rows.add(sample(level, player, "setHealth(0)", golem -> golem.setHealth(0.0F), Kind.HEALTH));
            rows.add(sample(level, player, "forceHurt", golem -> writeRaw(golem, 50.0F), Kind.HEALTH));
            rows.add(sample(level, player, "setRemoved", golem -> golem.setRemoved(Entity.RemovalReason.DISCARDED), Kind.REMOVAL));
            rows.add(sample(level, player, "dataMerge", Selftest::dataMerge, Kind.HEALTH));
            rows.add(sample(level, player, "forceRemove", golem ->
                    golem.setRemoved(Entity.RemovalReason.DISCARDED), Kind.REMOVAL));
            rows.add(sample(level, player, "nanHealth", golem -> writeRaw(golem, Float.NaN), Kind.NON_FINITE));
            rows.add(sample(level, player, "nbtWindow", selftest -> selftest.getPersistentData()
                    .putLong(GolemsArsenalSetHandler.PILLAR_UNTIL_KEY, Long.MAX_VALUE), Kind.WINDOW));
            rows.add(sample(level, player, "windowUntil", Selftest::windowUntil, Kind.WINDOW_UNTIL));
            rows.add(sample(level, player, "offThread", Selftest::noteOffThread, Kind.OFF_THREAD));
        } finally {
            running = false;
            current = null;
        }
        lastReport = List.copyOf(rows);
        return lastReport;
    }

    public static synchronized void stop() {
        running = false;
        AbstractGolemEntity<?, ?> golem = current;
        current = null;
        if (golem != null) {
            HealthLedger.forget(golem);
            golem.discard();
        }
    }

    public static List<Row> lastReport() {
        return lastReport;
    }

    private static Row sample(ServerLevel level, ServerPlayer player, String name,
                              Sample action, Kind kind) {
        boolean removal = kind == Kind.REMOVAL;
        AbstractGolemEntity<?, ?> golem = spawn(level, player);
        if (golem == null) {
            return new Row(name, 0, 0, false, false, true, "spawn failed");
        }
        current = golem;
        float before = DamageAdmission.rawHealth(golem);
        HealthLedger.Snapshot beforeSnap = HealthLedger.snapshot();
        HealthLedger.auditNow(golem);
        try {
            action.run(golem);
        } catch (Throwable t) {
            HealthLedger.forget(golem);
            if (!golem.isRemoved()) {
                golem.discard();
            }
            current = null;
            return new Row(name, before, DamageAdmission.rawHealth(golem),
                    false, false, true, "action error: " + t.getClass().getSimpleName());
        }

        float after = DamageAdmission.rawHealth(golem);
        boolean removed = golem.isRemoved();
        if (removal) {
            HealthLedger.auditRemovedNow(golem);
        } else {
            HealthLedger.auditNow(golem);
        }
        HealthLedger.Snapshot afterSnap = HealthLedger.snapshot();

        boolean blocked;
        boolean logged;
        boolean missed;
        String detail;
        switch (kind) {
            case REMOVAL -> {
                blocked = !removed;
                logged = afterSnap.removedWithoutDeath() > beforeSnap.removedWithoutDeath();
                missed = removed && !logged;
                detail = removed ? "removed" : "still present";
            }
            case NON_FINITE -> {
                boolean changed = !Float.isFinite(after)
                        || (Float.isFinite(before) && Math.abs(after - before) > EPS);
                blocked = !changed;
                logged = afterSnap.nonFinite() > beforeSnap.nonFinite();
                missed = changed && !logged;
                detail = "raw " + before + " -> " + after;
            }
            case WINDOW -> {
                blocked = false;
                logged = afterSnap.nbtWindowChanged() > beforeSnap.nbtWindowChanged()
                        || afterSnap.windowAnomaly() > beforeSnap.windowAnomaly();
                missed = !logged;
                detail = "pillarUntil=" + golem.getPersistentData()
                        .getLong(GolemsArsenalSetHandler.PILLAR_UNTIL_KEY);
            }
            case WINDOW_UNTIL -> {
                blocked = false;
                logged = afterSnap.windowRejected() > beforeSnap.windowRejected();
                missed = !logged;
                detail = "PillarUntil MAX through onGolemAttacked";
            }
            case OFF_THREAD -> {
                blocked = false;
                logged = afterSnap.offThread() > beforeSnap.offThread();
                missed = !logged;
                detail = "detection path only (no world write)";
            }
            default -> {
                boolean changed = Float.isFinite(before) && Float.isFinite(after)
                        && Math.abs(after - before) > EPS;
                blocked = !changed;
                logged = afterSnap.unexplained() > beforeSnap.unexplained();
                missed = changed && !logged;
                detail = "raw " + before + " -> " + after;
            }
        }

        if (!removal && !golem.isRemoved()) {
            HealthLedger.forget(golem);
            golem.discard();
        }
        current = null;
        return new Row(name, before, after, blocked, logged, missed, detail);
    }

    private static AbstractGolemEntity<?, ?> spawn(ServerLevel level, ServerPlayer player) {
        try {
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(
                    new ResourceLocation("modulargolems", "metal_golem"));
            if (type == null) {
                return null;
            }
            Entity entity = type.create(level);
            if (!(entity instanceof AbstractGolemEntity<?, ?> golem)) {
                return null;
            }
            golem.setPos(player.getX() + 2.0, player.getY(), player.getZ());
            golem.setOwnerUUID(player.getUUID());
            golem.setCustomName(Component.literal(TEST_NAME));
            golem.setCustomNameVisible(false);
            level.addFreshEntity(golem);
            golem.setGuardedDataImpl(golem.getMaxHealth(), true, true);
            HealthLedger.auditNow(golem);
            return golem;
        } catch (Throwable t) {
            Golems_arsenal.LOGGER.warn("[Selftest] spawn failed: {}", t.toString());
            return null;
        }
    }

    private static void dataMerge(AbstractGolemEntity<?, ?> golem) {
        CompoundTag tag = new CompoundTag();
        golem.saveWithoutId(tag);
        tag.putFloat("Health", 50.0F);
        golem.load(tag);
    }

    private static void writeRaw(AbstractGolemEntity<?, ?> golem, float value) {
        try {
            Field field = ObfuscationReflectionHelper.findField(LivingEntity.class, "f_20961_");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            EntityDataAccessor<Float> accessor = (EntityDataAccessor<Float>) field.get(null);
            golem.getEntityData().set(accessor, value);
        } catch (Throwable t) {
            throw new IllegalStateException("raw write failed", t);
        }
    }

    private static void noteOffThread(AbstractGolemEntity<?, ?> golem) throws InterruptedException {
        Thread thread = new Thread(() -> HealthLedger.noteOffThread(golem, "selftest"),
                "GA-Selftest-OffThread");
        thread.start();
        thread.join(1000L);
    }

    private static void windowUntil(AbstractGolemEntity<?, ?> golem) {
        golem.getPersistentData().putLong(GolemsArsenalSetHandler.PILLAR_UNTIL_KEY, Long.MAX_VALUE);
        LivingAttackEvent event = new LivingAttackEvent(
                golem, golem.damageSources().generic(), 1.0F);
        GolemsArsenalSetHandler.onGolemAttacked(event);
    }

    private enum Kind {
        HEALTH,
        REMOVAL,
        NON_FINITE,
        WINDOW,
        WINDOW_UNTIL,
        OFF_THREAD
    }

    private interface Sample {
        void run(AbstractGolemEntity<?, ?> golem) throws Exception;
    }

    public record Row(String sample, float before, float after,
                      boolean blocked, boolean logged, boolean missed, String detail) {
        public String format() {
            return String.format("%-14s before=%.1f after=%.1f blocked=%s logged=%s missed=%s %s",
                    sample, before, after, yes(blocked), yes(logged), yes(missed), detail);
        }

        private static String yes(boolean value) {
            return value ? "yes" : "no";
        }
    }
}
