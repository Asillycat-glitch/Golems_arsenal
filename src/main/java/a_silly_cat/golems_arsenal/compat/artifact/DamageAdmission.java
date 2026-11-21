package a_silly_cat.golems_arsenal.compat.artifact;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.guard.sanctuary.Anchor;
import a_silly_cat.golems_arsenal.guard.sanctuary.SanctuaryScopes;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 神器伤害“准入/适配”层（正式内容）。
 *
 * <p>设计目标不是针对某一个 mod，而是：</p>
 * <ul>
 *   <li><b>普通 Forge 伤害</b>（LivingAttack/Hurt/Damage 管线）始终放行，走现有抗性；</li>
 *   <li><b>绕过伤害管线的写</b>（true damage、直接 setHealth / DATA_HEALTH_ID）：
 *       在 adapted_* 白名单里的来源由适配器重新按抗性结算；不在白名单里的默认拒绝；</li>
 *   <li>白名单默认包含 fantasy_ending；以后给新神器写适配器时再加。</li>
 * </ul>
 *
 * <p><b>准入模型（2026-11 改）：</b>放行不再看“调用栈里出现了哪个类名”，而是看
 * {@link SanctuaryScopes} 的作用域：这次写是不是发生在一次<b>已被 Sanctuary 准入的 hurt</b>
 * 里面、是不是死亡栈、读档、或者本模组显式声明的内部写。这样就不需要维护一张永远补不全的
 * 类名白名单，恶意模组从任何位置直接写血都会被拒。唯一的例外是宿主模组
 * （{@code dev.xkmc.modulargolems.*}）自己的装配/校验/菜单写——那是我们要扩展的模组本体，
 * 保持放行。</p>
 *
 * <p>本类只做判定/来源构造/日志；真正的写入拦截在两个窄 mixin 里：
 * {@code LivingEntityHealthAdmissionMixin} 与 {@code SynchedEntityDataHealthAdmissionMixin}。
 * 它们只对“玩家拥有的傀儡 + 生命值下降”生效。</p>
 */
public final class DamageAdmission {

    private static final float EPS = 1.0E-3F;
    private static final ResourceKey<DamageType> ADMISSION_REHURT =
            ResourceKey.create(Registries.DAMAGE_TYPE, Golems_arsenal.id("admission_rehurt"));
    private static final StackWalker WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final Map<UUID, Long> LAST_LOG = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_ALLOW_LOG = new ConcurrentHashMap<>();
    private static volatile Boolean strictOverride;

    private static Field healthField;
    private static Field syncedEntityField;
    private static EntityDataAccessor<Float> healthAccessor;

    private DamageAdmission() {
    }

    // ------------------------------------------------------------------ 目标/白名单

    public static boolean isProtected(LivingEntity entity) {
        if (!enabled() || entity == null || entity.level().isClientSide()) {
            return false;
        }
        if (!(entity instanceof AbstractGolemEntity<?, ?> golem)) {
            return false;
        }
        try {
            return !Config.DAMAGE_ADMISSION_OWNED_ONLY.get() || golem.getOwnerUUID() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 这个来源是否有适配器（白名单）。 */
    public static boolean isHandled(LivingEntity target, DamageSource source) {
        if (!isProtected(target) || source == null) {
            return false;
        }
        String id = source.getMsgId();
        if (id != null && !id.isEmpty()) {
            for (String exact : handledDamageTypes()) {
                if (id.equals(exact)) {
                    return true;
                }
            }
            for (String mod : handledMods()) {
                if (id.equals(mod) || id.startsWith(mod + ".")) {
                    return true;
                }
            }
        }
        return isHandledItem(weapon(source));
    }

    /**
     * 适配器重结算用的正常伤害源。
     *
     * <p>不能复用 playerAttack/mobAttack：玩家傀儡对 owner 的 player 伤害有友伤保护，
     * {@code entity.hurt} 会直接返回 false（实测梦终剑 applied=false，看起来像“左键不能攻击”）。
     * 这里用无持有者的 generic，让护甲/减伤/L2 照常生效，同时不触发友伤判断。</p>
     */
    public static DamageSource normalSource(LivingEntity target, DamageSource original) {
        try {
            Holder<DamageType> holder = target.level().registryAccess()
                    .registryOrThrow(Registries.DAMAGE_TYPE)
                    .getHolderOrThrow(ADMISSION_REHURT);
            // 不带 attacker：避免 PlayerImmuneModifier 友伤；类型自身没有 bypasses_* tag，
            // 所以护甲/抗性/L2 仍会生效。归因看 original 的日志。
            return new DamageSource(holder);
        } catch (Throwable ignored) {
            return target.damageSources().generic();
        }
    }

    private static ItemStack weapon(DamageSource source) {
        if (source == null) {
            return ItemStack.EMPTY;
        }
        Entity direct = source.getDirectEntity();
        if (direct instanceof LivingEntity living) {
            return living.getMainHandItem();
        }
        Entity attacker = source.getEntity();
        if (attacker instanceof LivingEntity living) {
            return living.getMainHandItem();
        }
        return ItemStack.EMPTY;
    }

    private static boolean isHandledItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && handledItems().contains(id.toString());
    }

    // ------------------------------------------------------------------ 写入口判定

    /** LivingEntity#setHealth HEAD：只拦“受保护傀儡 + 生命值下降 + 不在任何已声明作用域里”。 */
    public static boolean allowSetHealth(LivingEntity entity, float amount) {
        return allowHealthChange(entity, amount, "setHealth");
    }

    /** SynchedEntityData#set(DATA_HEALTH_ID) HEAD：同上，覆盖直接 DataItem 写。 */
    public static boolean allowDataWrite(LivingEntity entity, float amount) {
        return allowHealthChange(entity, amount, "data.health");
    }

    private static boolean allowHealthChange(LivingEntity entity, float amount, String op) {
        // 客户端只做一件事：有主傀儡的血量不允许被同步成 0/负数/非有限值。
        // 服务端已经拒绝了真实清血；客户端若还落地 0，会进入死亡动画并让碰撞箱/模型消失，
        // 形成“服务端 dump 还在、客户端看不见”的幽灵。
        if (entity != null && entity.level().isClientSide()) {
            if (entity instanceof AbstractGolemEntity<?, ?> golem && golem.getOwnerUUID() != null
                    && (!Float.isFinite(amount) || amount <= 0.0F)) {
                logRejected(entity, op, "client lethal sync amount=" + amount);
                return false;
            }
            return true;
        }
        if (!isProtected(entity)) {
            return true;
        }
        if (strictWrites()) {
            if (!Float.isFinite(amount)) {
                logRejected(entity, op, "strict non-finite amount=" + amount);
                return false;
            }
            if (entity.level() instanceof ServerLevel level && !level.getServer().isSameThread()) {
                logRejected(entity, op, "strict off-thread amount=" + amount
                        + " thread=" + Thread.currentThread().getName());
                return false;
            }
        }
        if (!defaultDeny()) {
            return true;
        }
        if (!Float.isFinite(amount)) {
            logRejected(entity, op, "non-finite amount=" + amount);
            return false;
        }
        float raw = rawHealth(entity);
        if (!Float.isFinite(raw)) {
            return true;
        }
        // 只处理下降；上升/不变（heal、加载、恢复）不拦。
        if (amount >= raw - EPS) {
            return true;
        }
        // 不限制“大伤害”，只拒绝不合法的血量值：
        // 正常 L2 一百万血量/伤害仍然放行；toaru 的 -Float.MAX_VALUE 不是伤害，
        // 是非法负血量，必须拒绝。读档/死亡/内部修复允许负值过渡。
        if (amount < 0.0F
                && !SanctuaryScopes.inLoad()
                && !SanctuaryScopes.inDeath()
                && !SanctuaryScopes.inInternal()) {
            logRejected(entity, op, "negative health amount=" + amount
                    + " raw=" + raw + " caller=" + callerText());
            return false;
        }
        // 作用域放行：已准入的 hurt / 死亡栈 / 读档 / 本模组内部写 / 宿主模组自己的写。
        if (SanctuaryScopes.inLoad() || SanctuaryScopes.inDeath()) {
            return true;
        }
        if (SanctuaryScopes.inInternal()) {
            return true;
        }
        if (SanctuaryScopes.isAdmitted(entity)) {
            logAllowed(entity, op, amount, raw, "hurt");
            return true;
        }
        if (isHostModWrite()) {
            // 宿主模组（modulargolems）自己的写放行，但把"谁驱动的"记下来：
            // 外部模组借 setGuardedDataImpl 之类的 API 写血时，caller 会是那个模组。
            logAllowed(entity, op, amount, raw, "host");
            return true;
        }
        logRejected(entity, op, "amount=" + amount + " raw=" + raw + " caller=" + callerText());
        return false;
    }

    /**
     * 诊断：一次"被放行的大幅下降"是从哪个作用域、哪个调用者进来的。
     *
     * <p>平时不打印（只处理超过 20% 最大生命的下降 + 每只傀儡 20 tick 一次），
     * 专门用来抓"血被清零但我们看不到拒绝日志"的那类路径——例如 VanSh 这种。</p>
     */
    private static void logAllowed(LivingEntity entity, String op, float amount, float raw, String scope) {
        if (!(entity instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        float max = entity.getMaxHealth();
        if (!Float.isFinite(max) || max <= 0.0F) {
            return;
        }
        if (raw - amount < max * 0.2F) {
            return;
        }
        long now = golem.level().getGameTime();
        Long last = LAST_ALLOW_LOG.put(golem.getUUID(), now);
        if (last != null && now - last < 20) {
            return;
        }
        Golems_arsenal.LOGGER.info(
                "[DamageAdmission] allowed {} target={} uuid={} scope={} amount={} raw={} caller={}",
                op, golem.getName().getString(), golem.getUUID(), scope, amount, raw, callerText());
    }

    // ------------------------------------------------------------------ 栈判定（只剩宿主模组）

    /**
     * 宿主模组（Modular Golems）自己的写。
     *
     * <p>它的装配台、升级菜单、{@code validateGuardedData}、{@code setGuardedDataImpl}、
     * 死亡链都会写血，而且都不在 hurt 窗口里。这些是我们要扩展的模组本体，保持放行。</p>
     *
     * <p><b>但只放行"宿主自己驱动"的写</b>：如果栈里宿主帧的上一层是别的模组
     * （例如 ArcaneVortex 调 {@code setGuardedDataImpl} 把血清零），就拒绝。
     * 2026-11-06 实测：VanSh 的 {@code raw 666 -> 0} 正是从这条路进来的。</p>
     */
    private static boolean isHostModWrite() {
        List<Frame> frames = frames();
        // 技术条件：宿主 API 的栈里只要出现“非宿主/非 vanilla/非框架”的模组帧，
        // 就不认作宿主自己的写。这里不按任何具体 mod 名字判断。
        for (Frame frame : frames) {
            if (!isTrustedHostCaller(frame.cls)
                    && !frame.cls.startsWith("a_silly_cat.")
                    && !frame.cls.startsWith("sanctuary.agent")) {
                Golems_arsenal.LOGGER.info(
                        "[DamageAdmission] host_api_foreign caller={}#{} (宿主 API 被外部帧驱动，已拒绝)",
                        frame.cls, frame.method);
                return false;
            }
        }
        for (int i = 0; i < frames.size(); i++) {
            if (!frames.get(i).cls.startsWith("dev.xkmc.modulargolems.")) {
                continue;
            }
            Frame caller = i + 1 < frames.size() ? frames.get(i + 1) : null;
            if (caller == null || isTrustedHostCaller(caller.cls)) {
                return true;
            }
            return false;
        }
        return false;
    }

    private static boolean isTrustedHostCaller(String cls) {
        return cls.startsWith("dev.xkmc.modulargolems.")
                // 宿主所依赖的 l2 框架（l2library / l2damagetracker / l2artifacts / l2serial）：
                // 它们和宿主是同一套调度，替傀儡回血/改 guardedData 属于正常行为。
                || cls.startsWith("dev.xkmc.l2")
                || cls.startsWith("net.minecraft.")
                || cls.startsWith("net.minecraftforge.")
                || cls.startsWith("com.mojang.")
                || cls.startsWith("java.")
                || cls.startsWith("sun.")
                || cls.startsWith("org.spongepowered.");
    }

    // ------------------------------------------------------------------ 原始血/owner

    public static float rawHealth(LivingEntity entity) {
        try {
            EntityDataAccessor<Float> accessor = healthAccessor();
            if (accessor == null) {
                return Float.NaN;
            }
            Object value = entity.getEntityData().get(accessor);
            return value instanceof Number n ? n.floatValue() : Float.NaN;
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    /**
     * 内部用：把 {@code DATA_HEALTH} 直接写成指定值。
     *
     * <p>账本兜底修复走这里：包在 {@code internal} 作用域里，所以自己的写入口不会拒它；
     * 只写 raw，不碰 {@code guardedData}。</p>
     */
    public static boolean writeRawHealth(LivingEntity entity, float value) {
        // 2026-11-08：不再走 SynchedEntityData#set。
        // 那条路上有别人的 set/字段重定向（本包里就有把 DataItem 写入吞掉的实现），
        // 我们的"补血"会被静默丢弃，而日志看起来一切正常。这里直接写 DataItem.value，
        // 再把 item.dirty / data.dirty 置真，让 ServerEntity 自己发包同步。
        try {
            EntityDataAccessor<Float> accessor = healthAccessor();
            if (accessor == null) {
                return false;
            }
            SynchedEntityData data = entity.getEntityData();
            Object item = healthDataItem(data, accessor);
            if (item != null && writeItemValue(item, value)) {
                return true;
            }
            SanctuaryScopes.runInternal(() -> data.set(accessor, value));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Field dataItemValueField;
    private static Field dataItemDirtyField;
    private static Field synchedDirtyField;

    private static Object healthDataItem(SynchedEntityData data, EntityDataAccessor<Float> accessor) {
        try {
            if (itemsByIdField == null) {
                itemsByIdField = ObfuscationReflectionHelper.findField(SynchedEntityData.class, "f_135345_");
                itemsByIdField.setAccessible(true);
            }
            Object map = itemsByIdField.get(data);
            return map instanceof Map<?, ?> items ? items.get(accessor.getId()) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean writeItemValue(Object item, float value) {
        try {
            if (dataItemValueField == null) {
                dataItemValueField = ObfuscationReflectionHelper.findField(
                        SynchedEntityData.DataItem.class, "f_135391_");
                dataItemValueField.setAccessible(true);
            }
            dataItemValueField.set(item, value);
            if (dataItemDirtyField == null) {
                dataItemDirtyField = ObfuscationReflectionHelper.findField(
                        SynchedEntityData.DataItem.class, "f_135392_");
                dataItemDirtyField.setAccessible(true);
            }
            dataItemDirtyField.setBoolean(item, true);
            if (synchedDirtyField == null) {
                synchedDirtyField = ObfuscationReflectionHelper.findField(SynchedEntityData.class, "f_135348_");
                synchedDirtyField.setAccessible(true);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static EntityDataAccessor<Float> healthAccessor() {
        EntityDataAccessor<Float> accessor = healthAccessor;
        if (accessor != null) {
            return accessor;
        }
        try {
            Field field = healthField;
            if (field == null) {
                field = ObfuscationReflectionHelper.findField(LivingEntity.class, "f_20961_");
                field.setAccessible(true);
                healthField = field;
            }
            @SuppressWarnings("unchecked")
            EntityDataAccessor<Float> resolved = (EntityDataAccessor<Float>) field.get(null);
            healthAccessor = resolved;
            return resolved;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isHealthAccessor(EntityDataAccessor<?> key) {
        EntityDataAccessor<Float> health = healthAccessor();
        return key != null && health != null && key.getId() == health.getId();
    }

    // ------------------------------------------------------------------ D3：DataItem 字段级封条

    /**
     * 受保护傀儡的 {@code DATA_HEALTH} 的那个 {@code DataItem} 对象。
     *
     * <p>为什么要存对象本身：{@code DataItem#setValue} 的 {@code putfield} 里拿不到实体，
     * 只能按身份反查。DataItem 在实体构造时创建、整个生命周期不变，所以这张表是稳定的。</p>
     */
    private static final Map<Object, LivingEntity> SEALED =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    private static Field itemsByIdField;
    private static long sealedBlocked;

    /** 把一只受保护傀儡的血量 DataItem 登记进封条表（进世界 / 受击时调用，幂等）。 */
    public static void sealHealthItem(LivingEntity entity) {
        try {
            if (entity == null || entity.level().isClientSide()) {
                return;
            }
            EntityDataAccessor<Float> accessor = healthAccessor();
            if (accessor == null) {
                return;
            }
            if (itemsByIdField == null) {
                itemsByIdField = ObfuscationReflectionHelper.findField(SynchedEntityData.class, "f_135345_");
                itemsByIdField.setAccessible(true);
            }
            Object map = itemsByIdField.get(entity.getEntityData());
            if (map instanceof Map<?, ?> items) {
                Object item = items.get(accessor.getId());
                if (item != null) {
                    SEALED.put(item, entity);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean isSealedHealthItem(Object item) {
        return item != null && !SEALED.isEmpty() && SEALED.containsKey(item);
    }

    /** {@code DataItem#setValue} 的准入：语义和 {@code SynchedEntityData#set} 完全一致。 */
    public static boolean allowDataItemHealth(Object item, float amount) {
        LivingEntity entity = SEALED.get(item);
        if (entity == null) {
            return true;
        }
        boolean allowed = allowHealthChange(entity, amount, "dataitem.value");
        if (!allowed) {
            sealedBlocked++;
        }
        return allowed;
    }

    public static long sealedBlocked() {
        return sealedBlocked;
    }

    public static Entity entityOf(SynchedEntityData data) {
        if (data == null) {
            return null;
        }
        try {
            Field field = syncedEntityField;
            if (field == null) {
                field = ObfuscationReflectionHelper.findField(SynchedEntityData.class, "f_135344_");
                field.setAccessible(true);
                syncedEntityField = field;
            }
            return (Entity) field.get(data);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 日志

    public static void logHandled(LivingEntity target, String detail) {
        log(target, "handled", detail);
    }

    public static void logRejected(LivingEntity target, String op, String detail) {
        log(target, "rejected " + op, detail);
        // 被拒之后立刻对账一次：攻击方可能已经用另一条看不见的路径（DataItem 直写）
        // 改过血，这里让账本兜底立即把 raw 拉回去，而不是等 20 tick 的常规审计。
        if (target instanceof AbstractGolemEntity<?, ?> golem) {
            try {
                // 有人刚试过直写血：把这只傀儡标记为"最近被攻击过"，
                // 移除守卫在这段时间里不再相信"见过死亡事件"。
                a_silly_cat.golems_arsenal.guard.HealthLedger.noteTamper(golem);
                // 强制快照（触发点 4/4）：有人刚试过直写血。此刻的实体字段可能已经被写脏，
                // 快照里 Health 会被账本值覆盖，所以重建出来的傀儡不会继承被写过的血量。
                Anchor.snapshotNow(golem, "write_rejected");
                if (a_silly_cat.golems_arsenal.guard.HealthLedger.isEnabled()) {
                    a_silly_cat.golems_arsenal.guard.HealthLedger.auditNow(golem);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void log(LivingEntity target, String action, String detail) {
        if (!(target instanceof AbstractGolemEntity<?, ?> golem)) {
            return;
        }
        long now = golem.level().getGameTime();
        Long last = LAST_LOG.put(golem.getUUID(), now);
        if (last != null && now - last < 20) {
            return;
        }
        Golems_arsenal.LOGGER.info("[DamageAdmission] {} target={} uuid={} {}",
                action, golem.getName().getString(), golem.getUUID(), detail);
    }

    // ------------------------------------------------------------------ 配置

    private static boolean enabled() {
        try {
            return Config.DAMAGE_ADMISSION_ENABLED.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean strictWrites() {
        Boolean override = strictOverride;
        if (override != null) {
            return override;
        }
        try {
            return Config.DAMAGE_ADMISSION_STRICT_WRITES.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 运行时 kill switch；null 表示回到配置值。 */
    public static void setStrictOverride(Boolean value) {
        strictOverride = value;
    }

    public static void rotateIdentity(java.util.UUID old, java.util.UUID fresh) {
        Long at = LAST_LOG.remove(old);
        if (at != null) {
            LAST_LOG.put(fresh, at);
        }
        Long allow = LAST_ALLOW_LOG.remove(old);
        if (allow != null) {
            LAST_ALLOW_LOG.put(fresh, allow);
        }
    }

    public static String strictStatus() {
        Boolean override = strictOverride;
        return "strict=" + (strictWrites() ? "on" : "off")
                + (override == null ? "(config)" : "(runtime)");
    }

    private static boolean defaultDeny() {
        try {
            return Config.DAMAGE_ADMISSION_DEFAULT_DENY.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static List<? extends String> handledMods() {
        try {
            return Config.DAMAGE_ADMISSION_HANDLED_MODS.get();
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private static List<? extends String> handledDamageTypes() {
        try {
            return Config.DAMAGE_ADMISSION_HANDLED_DAMAGE_TYPES.get();
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private static List<? extends String> handledItems() {
        try {
            return Config.DAMAGE_ADMISSION_HANDLED_ITEMS.get();
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------ 小工具

    private static List<Frame> frames() {
        try {
            return WALKER.walk(s -> s
                    .filter(f -> !f.getClassName().startsWith("a_silly_cat.golems_arsenal"))
                    .filter(f -> !f.getClassName().startsWith("org.spongepowered."))
                    // Mixin 生成的处理器长在目标类里（例如 SynchedEntityData#handler$efh000$ga$admitData），
                    // 名字前缀固定是 handler$，不滤掉的话 callerText 永远指向我们自己。
                    .filter(f -> !f.getMethodName().startsWith("handler$"))
                    .limit(24)
                    .map(f -> new Frame(f.getClassName(), f.getMethodName()))
                    .collect(Collectors.toList()));
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private static String callerText() {
        List<Frame> frames = frames();
        return frames.isEmpty() ? "?" : frames.get(0).cls + "#" + frames.get(0).method;
    }

    private static boolean in(String value, String... candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(value)) {
                return true;
            }
        }
        return false;
    }


    private record Frame(String cls, String method) {
    }
}
