package a_silly_cat.golems_arsenal.base.entity;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.init.ModEntities;
import a_silly_cat.golems_arsenal.tech.orbit.FuelBridge;
import a_silly_cat.golems_arsenal.tech.orbit.HasGasProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 天基火箭 —— **它自己就是平台（也是卫星）**。
 *
 * <p>流程（设计见 {@code docs/ORBITAL-LASER-DESIGN.md} §2）：
 * <pre>
 *   放置（火箭物品） → 加燃料（UI） → 按「发射」 → 倒计时 200 tick → 升空 → 入轨
 * </pre>
 *
 * <p><b>倒计时是实体自己发出的</b>：用户明确要求"实体消失 10 秒倒计时也消失"——
 * 所以计时状态存在实体上（{@code DATA_COUNTDOWN} 同步给客户端用于显示），
 * 实体被移除或被打断，发射自然取消，不需要额外的取消逻辑。
 *
 * <p><b>上升段撞到东西就爆炸</b>：判定用 **5×5 的柱体**（与"占地至少 5×5"一致），
 * 而不是实体自己的碰撞箱——火箭很宽，擦到边角也该算撞。
 *
 * <p><b>这个类刻意不引用任何 Mekanism 的类</b>，所以在没装 MEK 时也能安全加载。
 * 燃料（氢气）目前是一个普通字段；接成 MEK 的气体能力是下一步，届时会放在
 * {@code compat/mekanism} 下并照 {@code RadiationEntityProvider} 的门控写法。
 */
public class RocketEntity extends Entity implements MenuProvider, HasGasProvider {

    /** 状态：待发（可以加燃料、可以发射）。 */
    public static final int STATE_IDLE = 0;
    /** 状态：倒计时中。 */
    public static final int STATE_COUNTDOWN = 1;
    /** 状态：升空中。 */
    public static final int STATE_ASCENT = 2;

    /** 火箭的占地半径：5×5 = 以中心为准 ±2 格。 */
    public static final int FOOTPRINT_RADIUS = 2;

    private static final EntityDataAccessor<Integer> DATA_STATE =
            SynchedEntityData.defineId(RocketEntity.class, EntityDataSerializers.INT);
    /** 剩余倒计时 tick（同步给客户端用于显示数字）。 */
    private static final EntityDataAccessor<Integer> DATA_COUNTDOWN =
            SynchedEntityData.defineId(RocketEntity.class, EntityDataSerializers.INT);
    /** 燃料量（mB）。服务端写入，客户端只读来显示。 */
    private static final EntityDataAccessor<Integer> DATA_FUEL =
            SynchedEntityData.defineId(RocketEntity.class, EntityDataSerializers.INT);
    /**
     * 燃料**气体种类**的注册名（例如 {@code mekanism:hydrogen} / {@code mekanism:ethylene}）。
     *
     * <p>为什么需要单独同步：储罐在服务端，客户端那个只是 {@code createDummy} 造的空壳。
     * 光同步储量（{@link #DATA_FUEL}）不够——界面还要知道**是哪种气**才能取到正确的
     * 贴图、tint 和显示名。MEK 的方块机器靠 {@code SyncableChemicalTank} + 容器同步
     * 一次性把类型和数量都带过去，实体没有容器，只能走实体数据。
     */
    private static final EntityDataAccessor<String> DATA_FUEL_GAS =
            SynchedEntityData.defineId(RocketEntity.class, EntityDataSerializers.STRING);

    private static final String TAG_STATE = "RocketState";
    private static final String TAG_COUNTDOWN = "RocketCountdown";
    private static final String TAG_ASCENT = "RocketAscentTicks";
    private static final String TAG_FUEL = "RocketFuel";
    /** 燃料槽里的储气物品。 */
    private static final String TAG_FUEL_ITEM = "RocketFuelItem";
    /** 氢气储罐的 NBT（MEK 自己的格式，我们只搬运）。 */
    private static final String TAG_FUEL_TANK = "RocketFuelTank";
    private static final String TAG_OWNER = "RocketOwner";

    /** 模型高度（格）。取自 {@code 火箭实体模型.bbmodel}：239 像素 / 16。用于剔除盒。 */
    private static final double MODEL_HEIGHT = 239.0 / 16.0;

    /** 升空结束（达到这个高度）后入轨并消失。 */
    private static final double ASCENT_TARGET_Y = 300.0;
    /**
     * 离地初速（格/tick）。**0 = 真正的静止起步**（用户要求"初速为 0"）：
     * 点火后先慢慢离架，再逐渐加速。
     */
    private static final double ASCENT_START_SPEED = 0.0;
    /** 最大上升速度（格/tick）。用户要求"设个速度上限"，1.0 = 每秒 20 格。 */
    private static final double ASCENT_MAX_SPEED = 1.0;
    /**
     * 速度曲线的时间常数（tick）。**越大加速越慢**（用户要求"更低的加速度"）。
     * τ=100 时：1 秒后 0.18、3 秒后 0.45、5 秒后 0.63、10 秒后 0.86、15 秒后 0.95。
     */
    private static final double ASCENT_ACCEL_TAU = 100.0;

    /** 升空已经走了多少 tick。 */
    private int ascentTicks;
    /** 当前打开的界面（同时只会有一个）。用于燃料变动时刷新槽位。 */
    private a_silly_cat.golems_arsenal.tech.orbit.RocketMenu openMenu;
    /** 储罐内容变化标记（IContentsListener 回调），tick 里统一同步给客户端。 */
    private boolean fuelDirty;
    /**
     * 氢气储罐的能力提供者（真实类型是 {@code RocketGasProvider}，引用 MEK 的类）。
     *
     * <p>字段类型是 {@code Object}，这样实体的类签名里不出现 MEK——
     * 存取走 {@link HasGasProvider} 接口，只有 {@code FuelBridge} 会强转它。
     */
    @Nullable
    private Object gasProvider;

    public RocketEntity(EntityType<? extends RocketEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;       // 不参与常规碰撞（撞东西由我们自己的柱体判定负责）
        this.setNoGravity(true);
    }

    // ------------------------------------------------------------------
    // 燃料槽物品（界面用）
    // ------------------------------------------------------------------

    /** 让打开着的界面刷新槽位。改动燃料物品后调用。 */
    public void notifyFuelChanged() {
        if (this.openMenu != null) {
            this.openMenu.broadcastChanges();
        }
    }

    // ------------------------------------------------------------------
    // MenuProvider：右键开界面
    // ------------------------------------------------------------------

    @Override
    public Component getDisplayName() {
        return Component.translatable("item.golems_arsenal.rocket");
    }

    /**
     * 服务端建菜单。客户端靠 {@code extraData} 里的**实体 id** 找回同一个火箭
     * （{@link a_silly_cat.golems_arsenal.tech.orbit.RocketMenu} 的构造函数会读这个 id）。
     */
    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory inventory, Player player) {
        a_silly_cat.golems_arsenal.tech.orbit.RocketMenu menu =
                new a_silly_cat.golems_arsenal.tech.orbit.RocketMenu(windowId, inventory, this.getId());
        this.openMenu = menu;
        return menu;
    }

    /** 界面关闭时清掉引用，免得一直握着一个死菜单。 */
    public void onMenuClosed(a_silly_cat.golems_arsenal.tech.orbit.RocketMenu menu) {
        if (this.openMenu == menu) {
            this.openMenu = null;
        }
    }

    // ------------------------------------------------------------------
    // 生成
    // ------------------------------------------------------------------

    /** 在指定位置生成一台火箭。 */
    public static RocketEntity place(ServerLevel level, Vec3 pos) {
        RocketEntity rocket = new RocketEntity(ModEntities.ROCKET.get(), level);
        rocket.setPos(pos.x, pos.y, pos.z);
        level.addFreshEntity(rocket);
        // 用 BEACON_ACTIVATE 而不是 IRON_PLACE：后者在本映射下不存在（IRON_PLACE 是错误字段名）。
        // 音效之后可以换成更贴的金属声，先用一个确定存在的。
        level.playSound(null, pos.x, pos.y, pos.z,
                SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.2F, 0.7F);
        return rocket;
    }

    /** 放置高度：当地地表。 */
    public static Vec3 groundPos(Level level, BlockPos pos) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
        return new Vec3(pos.getX() + 0.5, y, pos.getZ() + 0.5);
    }

    // ------------------------------------------------------------------
    // 同步数据
    // ------------------------------------------------------------------

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA_STATE, STATE_IDLE);
        this.entityData.define(DATA_COUNTDOWN, 0);
        this.entityData.define(DATA_FUEL, 0);
        this.entityData.define(DATA_FUEL_GAS, "");
    }

    public int state() {
        return this.entityData.get(DATA_STATE);
    }

    public int countdown() {
        return this.entityData.get(DATA_COUNTDOWN);
    }

    /**
     * 客户端读这个来画燃料条。
     *
     * <p>储罐在服务端，客户端拿不到，所以服务端每 tick 把储量写进这个同步字段
     * （见 {@code tick} 里 {@code DATA_FUEL} 的写入）。
     */
    public int fuelDisplay() {
        return this.entityData.get(DATA_FUEL);
    }

    /** 客户端读这个来取气体的贴图 / tint / 名称；空罐是空串。 */
    public String fuelGasId() {
        return this.entityData.get(DATA_FUEL_GAS);
    }

    private void setState(int state) {
        this.entityData.set(DATA_STATE, state);
    }

    // ------------------------------------------------------------------
    // 占地检查（5×5）
    // ------------------------------------------------------------------

    /**
     * 脚下 5×5 那一层是不是空的、且脚下有支撑（**放置时**用）。
     *
     * @return 空闲返回 true
     */
    public static boolean footprintClear(Level level, BlockPos center) {
        for (int dx = -FOOTPRINT_RADIUS; dx <= FOOTPRINT_RADIUS; dx++) {
            for (int dz = -FOOTPRINT_RADIUS; dz <= FOOTPRINT_RADIUS; dz++) {
                BlockPos p = center.offset(dx, 0, dz);
                if (!level.getBlockState(p).isAir()) {
                    return false;
                }
                if (level.getBlockState(p.below()).isAir()) {
                    return false;   // 悬空放置
                }
            }
        }
        return true;
    }

    /**
     * 5×5 的**整根柱体**（从脚下到升空目标高度）有没有阻挡（**点火时**用）。
     *
     * <p>用户定的是"上升段撞上物体会爆炸"，所以点火前先查一遍，避免玩家一点火就自杀。
     *
     * <p><b>从 {@code baseY + 1} 起扫</b>：实体站在地板方块的**顶面**上（见
     * {@code RocketItem.useOn}），所以"实体所在那一格"就是地板本身，不能算阻挡。
     * 这点之前搞错过——从 {@code baseY} 起扫会把脚下的地判成阻挡，点火立刻取消，
     * 火箭永远起不来。
     *
     * <p><b>性能</b>：这是 5×5×235 ≈ 5,900 次方块查询，**只在点火那一次调用**。
     * 升空途中绝不能这么查（见 {@link #hitsSomething()}）。
     *
     * @return 第一个挡路的方块坐标；畅通返回 null
     */
    public BlockPos firstObstruction() {
        int baseY = (int) Math.floor(this.getY());
        int topY = (int) ASCENT_TARGET_Y;
        int cx = (int) Math.floor(this.getX());
        int cz = (int) Math.floor(this.getZ());
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = baseY + 1; y <= topY; y++) {
            for (int dx = -FOOTPRINT_RADIUS; dx <= FOOTPRINT_RADIUS; dx++) {
                for (int dz = -FOOTPRINT_RADIUS; dz <= FOOTPRINT_RADIUS; dz++) {
                    cursor.set(cx + dx, y, cz + dz);
                    if (!this.level().isLoaded(cursor)) {
                        continue;   // 未加载的区块不算阻挡
                    }
                    if (!this.level().getBlockState(cursor).isAir()) {
                        return cursor.immutable();
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 发射
    // ------------------------------------------------------------------

    /** 能不能发射：有燃料、且处于待发状态。 */
    public boolean canLaunch() {
        return this.state() == STATE_IDLE && this.fuelStored() > 0;
    }

    /**
     * 从燃料槽里的储气物品把氢气搬进储罐（每 tick 一次）。
     *
     * <p>搬运逻辑在 {@link FuelBridge} → {@code RocketTank} 里，**那两个类引用 Mekanism**，
     * 所以这里必须先过 {@code ModList.isLoaded} 门控；对它们的引用写在方法体内部，
     * 类加载推迟到第一次真正调用时（照 {@code GolemRadiationAttachHandler} 的先例）。
     */
    private void tickFuelTransfer() {
        // 门控必须在最前面：下面每一行都会碰到 FuelBridge
        if (!FuelBridge.available()) {
            return;
        }
        // **搬运完全交给 MEK 自己的槽位**（GasInventorySlot.fillTank → fillChemicalTankFromItem）。
        // 之前我自己写了一套"先 simulate 再抽"，虽然是照抄的，但终究是另一份实现。
        FuelBridge.tickFill(this);
    }

    /** 储罐内容变化的回调（IContentsListener）：交给 tick 统一同步给客户端。 */
    public void markFuelDirty() {
        this.fuelDirty = true;
    }

    /**
     * 当前燃料储量（mB）。
     *
     * <p><b>客户端必须读同步字段 {@link #DATA_FUEL}，不能读储罐</b>：客户端也有
     * 能力提供者（{@code AttachCapabilitiesEvent} 两边都会触发），但里面那个储罐是空的
     * ——真实储量只在服务端。
     *
     * <p>这一点很要命：框架 {@code BaseContainerScreen.click(int)} 的实现是
     * <pre>
     *   if (menu.clickMenuButton(clientPlayer, btn)) {
     *       player.connection.send(new ServerboundContainerButtonClickPacket(...));
     *   }
     * </pre>
     * <b>先在客户端判一次，通过才发包</b>。客户端的 {@code canLaunch()} 算不对，
     * 按钮就「点了没反应」（实测就是这个症状）。
     */
    public int fuelStored() {
        if (this.level() != null && this.level().isClientSide) {
            return this.entityData.get(DATA_FUEL);
        }
        if (!FuelBridge.available()) {
            return 0;
        }
        return FuelBridge.stored(this);
    }

    /** 储罐能力提供者（{@code FuelBridge} 在挂载能力时设置）。 */
    @Override
    @Nullable
    public Object gasProvider() {
        return this.gasProvider;
    }

    /** 由 {@code FuelBridge} 在 {@code AttachCapabilitiesEvent} 里调用。 */
    @Override
    public void setGasProvider(@Nullable Object provider) {
        this.gasProvider = provider;
    }

    /**
     * 燃料箱容量（mB）。
     *
     * <p><b>以配置值为准，不从储罐读</b>：储罐只在装了 MEK 时存在，而容量本来是配置项
     * （{@code rocket.fuel_capacity}）。从储罐读会让"没装 MEK"和"储罐没建起来"两种情况
     * 都显示成 0，界面上就是 {@code 0 / 0}——玩家看不懂，我也难查。
     */
    public int fuelCapacity() {
        return Config.ROCKET_FUEL_CAPACITY.get();
    }

    /**
     * 点火：进入倒计时。**不在这里扣燃料**——燃料在升空那一步才消耗，
     * 这样倒计时被打断时燃料不损失。
     */
    public void ignite() {
        if (this.state() != STATE_IDLE) {
            return;
        }
        this.setState(STATE_COUNTDOWN);
        this.entityData.set(DATA_COUNTDOWN, Config.ROCKET_COUNTDOWN.get());
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.BEACON_ACTIVATE, SoundSource.NEUTRAL, 3.0F, 0.5F);
    }

    /** 取消倒计时，回到待发（被打断 / 柱体有阻挡时）。 */
    public void abortCountdown() {
        this.setState(STATE_IDLE);
        this.entityData.set(DATA_COUNTDOWN, 0);
    }

    // ------------------------------------------------------------------
    // tick
    // ------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (!(this.level() instanceof ServerLevel server)) {
            clientTick();
            return;
        }
        switch (this.state()) {
            case STATE_COUNTDOWN -> tickCountdown(server);
            case STATE_ASCENT -> tickAscent(server);
            default -> {
                // 待发：从燃料槽把氢气搬进储罐
                this.tickFuelTransfer();
            }
        }
        // 燃料储量：储罐变化过就同步给客户端（界面燃料条读它）
        if (this.fuelDirty) {
            this.fuelDirty = false;
            this.entityData.set(DATA_FUEL, this.fuelStored());
            // 气体种类也一起同步——界面的贴图/tint/名称都靠它
            if (FuelBridge.available()) {
                this.entityData.set(DATA_FUEL_GAS, FuelBridge.gasId(this));
            }
        }
    }

    private void tickCountdown(ServerLevel server) {
        int left = this.countdown() - 1;
        this.entityData.set(DATA_COUNTDOWN, left);

        // 倒计时期间：点火前就在喷烟喷火，同时地面铺开云雾。
        // spread 故意取小：点火阶段要的是"从喷口往下喷"，不是"围着火箭炸开"。
        spawnSmoke(server, 10, 0.8);
        spawnFire(server, 6, 0.6);
        spawnCloud(server, 12, 3.5);
        // 每秒一声：MEK 的机器激光音（"充能中"的感觉）
        if (left % 20 == 0 && left > 0) {
            playMekSound(server, "tile.machine.laser", 2.2F, 1.4F);
        }
        if (left <= 0) {
            beginAscent(server);
        }
    }

    private void beginAscent(ServerLevel server) {
        // 点火前再查一次整根柱体：有阻挡就取消发射（而不是当场爆炸）
        BlockPos obstruction = firstObstruction();
        if (obstruction != null) {
            // **必须留日志**：这一支之前是静默返回的，结果"点火没反应"和"根本没点火"
            // 在日志里长得一模一样，排查了两轮才定位。
            Golems_arsenal.LOGGER.info(
                    "Rocket launch aborted: column blocked at {} (rocket at {}). "
                            + "The 5x5 column up to y={} must be clear.",
                    obstruction, this.blockPosition(), (int) ASCENT_TARGET_Y);
            abortCountdown();
            if (this.getOwnerPlayer() != null) {
                this.getOwnerPlayer().displayClientMessage(
                        net.minecraft.network.chat.Component.translatable(
                                        "message.golems_arsenal.rocket_blocked")
                                .withStyle(net.minecraft.ChatFormatting.RED), true);
            }
            server.sendParticles(ParticleTypes.SMOKE,
                    obstruction.getX() + 0.5, obstruction.getY() + 0.5, obstruction.getZ() + 0.5,
                    10, 0.3, 0.3, 0.3, 0.0);
            return;
        }
        // 扣燃料：从储罐里消耗
        if (FuelBridge.available()) {
            FuelBridge.consume(this, Config.ROCKET_FUEL_COST.get());
            this.entityData.set(DATA_FUEL, this.fuelStored());
        }
        this.setState(STATE_ASCENT);
        this.ascentTicks = 0;
        Golems_arsenal.LOGGER.info("Rocket launch started at {}", this.blockPosition());
        // 点火：MEK 的喷火器音效（比原版烟花火箭贴得多）
        playMekSound(server, "item.flamethrower.active", 3.5F, 0.8F);
    }

    private void tickAscent(ServerLevel server) {
        this.ascentTicks++;
        this.setPos(this.getX(), this.getY() + ascentSpeed(), this.getZ());

        // 尾焰：烟 + 火 + 云雾，数量随速度增长（越快的段越狂暴）
        double t = Math.min(1.0, this.ascentTicks / 60.0);
        double power = 0.7 + t * 1.3;
        spawnFire(server, (int) (30 * power), 1.2 + t * 0.6);
        spawnSmoke(server, (int) (45 * power), 1.6 + t * 1.0);
        spawnCloud(server, (int) (25 * power), 2.0 + t * 1.5);

        // 上升段的持续轰鸣：每 6 tick 补一次 MEK 的喷火音，听起来是连着的
        if (this.ascentTicks % 6 == 0) {
            playMekSound(server, "item.flamethrower.active", 3.2F, 0.7F + (float) t * 0.3F);
        }

        // **上升段撞到东西就爆炸**
        if (hitsSomething()) {
            explode(server);
            return;
        }
        if (this.getY() >= ASCENT_TARGET_Y) {
            reachOrbit(server);
        }
    }

    /**
     * 当前上升速度（格/tick）——**指数逼近的速度曲线**，初速为 0。
     *
     * <p>{@code v(t) = max × (1 − e^(−t/τ))}：起步是静止的（0 格/tick，真正"缓缓离架"），
     * 然后越来越快、渐近到 {@link #ASCENT_MAX_SPEED}。τ 越大加速越平缓。
     */
    private double ascentSpeed() {
        return ASCENT_MAX_SPEED * (1.0 - Math.exp(-this.ascentTicks / ASCENT_ACCEL_TAU));
    }

    /**
     * 播放 Mekanism 自己的音效。
     *
     * <p>用户要求的"用 MEK 的喷火音效"。这里**不是复制它的 ogg 资产**，而是按注册名引用它的事件
     * （{@code mekanism:item/flamethrower_active} 等，见 {@code assets/mekanism/sounds.json}）。
     * 从注册表里取而不是直接引用常量，是为了让本类不必 import 任何 MEK 的类——
     * 与"这个实体在没装 MEK 时也能安全加载"保持一致。取不到就静默跳过。
     */
    private void playMekSound(ServerLevel server, String path, float volume, float pitch) {
        var id = new net.minecraft.resources.ResourceLocation("mekanism", path);
        var event = net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.get(id);
        if (event == null) {
            return;   // 没装 MEK（或它改了音效名）：静音，不报错
        }
        server.playSound(null, this.getX(), this.getY(), this.getZ(),
                event, SoundSource.NEUTRAL, volume, pitch);
    }

    /**
     * 四个角上的喷口在**实体模型空间**里的水平偏移（格）。
     *
     * <p>来自 Blockbench 文件里那四个空目标（用户特意为喷口加的）：
     * <pre>
     *   null_object   [ 24, 0, -24]     null_object3  [-24, 0,  24]
     *   null_object2  [ 24, 0,  24]     null_object4  [-24, 0, -24]
     * </pre>
     * 换算：{@code x/16}（与 24 无关）、{@code y → 24 - y}（{@code 24-0 = 24}，即脚底平面）、
     * {@code z/16}。所以四个喷口都在"脚底那一层"的 1.5 格角上。
     */
    private static final double[][] NOZZLE_CORNERS = {
            {24.0, -24.0 / 16.0},
            {24.0, 24.0 / 16.0},
            {-24.0, 24.0 / 16.0},
            {-24.0, -24.0 / 16.0},
    };

    /**
     * 喷口层高的经验校准量（格），**正 = 往上抬**。
     *
     * <p>几何本身来自用户的 Blockbench 导出（不是推的）：
     * <pre>
     *   着陆腿（四角）：最低面 y = +24 px = +1.50 格  →  这一端就是喷嘴
     *   中心喷口 bone2：最低面 y = -15 px = −0.94 格  →  比腿底高 2.44 格
     * </pre>
     * 加上渲染的垂直偏移后，四角喷嘴正好落在脚底平面，中心喷嘴比它高
     * {@link #CENTER_NOZZLE_LIFT} 格。
     *
     * <p>这个常量是留给"实测和几何对不上"时的补偿口子——我先给过 8.0，实测偏高了，
     * 现在按几何取 0。喷口位置调不准时改这一个数。
     */
    private static final double NOZZLE_Y_TWEAK = 0.0;

    /**
     * 中心喷口比四角喷嘴高多少（格）。
     *
     * <p>来自几何：{@code (+24 − (−15)) / 16 = 2.4375}。四角喷嘴在着陆腿底面（脚底平面），
     * 中心喷口段（bone2）的底面比它高这么多。
     */
    private static final double CENTER_NOZZLE_LIFT = 39.0 / 16.0;

    /**
     * 算出一个喷口在**世界坐标**里的位置。
     *
     * <p>把模型的变换链照搬过来，这样粒子和模型喷口对齐：
     * <pre>
     *   1. 绕 Z 翻 180°        -> 水平方向取反（x→-x, z→z）
     *   2. 绕 Y 转 (180° − 偏航) -> 化简为对取反后的坐标施加偏航旋转
     *   3. 垂直位置 = 渲染器同一个偏移 + 该喷口在模型里的层高 − 额外下沉
     * </pre>
     *
     * @param idx  喷口下标；0..3 = 四角着陆腿喷嘴，其它值 = 中心喷口
     * @param drop 额外的向下偏移（格），让粒子从喷口稍下方冒出来
     */
    private Vec3 nozzlePos(int idx, double drop) {
        boolean center = idx < 0 || idx >= NOZZLE_CORNERS.length;
        double lx = 0.0;
        double lz = 0.0;
        if (!center) {
            lx = -NOZZLE_CORNERS[idx][0] / 16.0;   // 第 1 步：x 取反
            lz = NOZZLE_CORNERS[idx][1];           // z 不变
        }
        // 第 2 步：绕 Y 施加偏航
        double yaw = Math.toRadians(this.getYRot());
        double cos = Math.cos(yaw);
        double sin = Math.sin(yaw);
        double wx = lx * cos + lz * sin;
        double wz = -lx * sin + lz * cos;
        // 第 3 步：垂直位置
        //   rocketVerticalOffset()：渲染器把模型放在哪（同一个数 → 模型动，粒子跟着动）
        //   四角喷嘴：正好在模型脚底平面（腿底 y = +24px = 脚底），所以不再加减
        //   中心喷嘴：比四角高 CENTER_NOZZLE_LIFT
        double wy = rocketVerticalOffset()
                + (center ? CENTER_NOZZLE_LIFT : 0.0)
                + NOZZLE_Y_TWEAK - drop;
        return new Vec3(this.getX() + wx, this.getY() + wy, this.getZ() + wz);
    }

    /**
     * 取渲染器用的垂直偏移量。
     *
     * <p>{@code RocketRenderer} 是 {@code @OnlyIn(CLIENT)} 的，纯服务端环境里加载不了，
     * 所以这里捕获 {@link NoClassDefFoundError} 并退回实测锚点常量。
     */
    private static double rocketVerticalOffset() {
        try {
            return a_silly_cat.golems_arsenal.client.RocketRenderer.verticalOffset();
        } catch (NoClassDefFoundError | NoSuchMethodError e) {
            return 45.0 / 16.0;   // 实测锚点，见 RocketRenderer.FALLBACK_ANCHOR
        }
    }

    /**
     * 尾焰的火：从**四个角的喷口**向下喷成一股。
     *
     * <p><b>两个踩过的坑</b>：
     * <ol>
     *   <li>{@code sendParticles} 的 xd/yd/zd 是<b>随机偏移量</b>而不是速度——用它做尾焰
     *       只会得到"原地乱飘"。</li>
     *   <li>改用带 {@code delta} 的重载后，<b>不能把速度写成位置的函数</b>
     *       （{@code ox * 0.08} 这种）：生成得越靠外的粒子横向速度越大，结果是一团炸开的火，
     *       而不是一股喷流。横向速度必须<b>基本为零</b>。</li>
     * </ol>
     *
     * <p>{@code speed} 参数也要压得很小：它是"在 delta 方向上的随机散布"，
     * 给 1.0 会把方向彻底打乱（比向下速度本身还大）。
     */
    private void spawnFire(ServerLevel server, int count, double spread) {
        double down = -0.45 - ascentSpeed() * 0.8;
        for (int i = 0; i < count; i++) {
            Vec3 p = nozzlePos(i % NOZZLE_CORNERS.length, 0.2);
            // 生成点只做很小的抖动，横向速度精确为 0 → 是一股向下的喷流
            double jx = (this.random.nextDouble() - 0.5) * spread * 0.18;
            double jz = (this.random.nextDouble() - 0.5) * spread * 0.18;
            var type = (i & 1) == 0 ? ParticleTypes.FLAME : ParticleTypes.SOUL_FIRE_FLAME;
            server.sendParticles(type,
                    p.x + jx, p.y, p.z + jz,
                    1,
                    0.0, down, 0.0,      // ← 横向速度为 0，只向下
                    0.02);               // ← 速度散布要小，否则方向被打乱
        }
    }

    /** 烟：从四个角喷口冒出，速度比火慢、扩散稍大（烟本来就会膨胀）。 */
    private void spawnSmoke(ServerLevel server, int count, double spread) {
        double down = -0.25 - ascentSpeed() * 0.6;
        for (int i = 0; i < count; i++) {
            Vec3 p = nozzlePos(i % NOZZLE_CORNERS.length, 0.35);
            double jx = (this.random.nextDouble() - 0.5) * spread * 0.35;
            double jz = (this.random.nextDouble() - 0.5) * spread * 0.35;
            var type = (i % 3 == 0) ? ParticleTypes.LARGE_SMOKE : ParticleTypes.CAMPFIRE_COSY_SMOKE;
            server.sendParticles(type,
                    p.x + jx, p.y, p.z + jz,
                    1,
                    0.0, down, 0.0,
                    0.03);
        }
    }

    /** 云雾：贴着地面**向外铺开**（横向速度明显、向下很小），给发射场体积感。 */
    private void spawnCloud(ServerLevel server, int count, double spread) {
        for (int i = 0; i < count; i++) {
            // 云雾从火箭四周的地面铺开，所以用中心点 + 随机方向
            double a = this.random.nextDouble() * Math.PI * 2.0;
            double r = spread * (0.3 + this.random.nextDouble() * 0.5);
            double outward = 0.2 + this.random.nextDouble() * 0.15;
            Vec3 base = nozzlePos(4, 0.4);   // 中心（null_object5 在 [0,0,0]）
            server.sendParticles(ParticleTypes.CLOUD,
                    base.x + Math.cos(a) * r,
                    this.getY() + rocketVerticalOffset() - 1.5,
                    base.z + Math.sin(a) * r,
                    1,
                    Math.cos(a) * outward, -0.02, Math.sin(a) * outward,
                    0.02);
        }
    }

    /** 上升段碰撞：5×5 柱体判定（与占地一致）。 */
    private boolean hitsSomething() {
        int cx = (int) Math.floor(this.getX());
        int cz = (int) Math.floor(this.getZ());
        int y = (int) Math.floor(this.getY());
        // **只扫实体脚下往上的空间**。之前从 y 开始扫，把"火箭自己踩着的那块地"也算进去，
        // 结果第一 tick 就判定撞到东西 → 当场爆炸，从来起不了飞。
        for (int dy = 1; dy <= 2; dy++) {
            for (int dx = -FOOTPRINT_RADIUS; dx <= FOOTPRINT_RADIUS; dx++) {
                for (int dz = -FOOTPRINT_RADIUS; dz <= FOOTPRINT_RADIUS; dz++) {
                    BlockPos p = new BlockPos(cx + dx, y + dy, cz + dz);
                    if (this.level().isLoaded(p) && !this.level().getBlockState(p).isAir()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void explode(ServerLevel server) {
        server.explode(this, this.getX(), this.getY(), this.getZ(),
                Config.ROCKET_EXPLOSION_POWER.get().floatValue(),
                Config.ROCKET_EXPLOSION_FIRE.get()
                        ? Level.ExplosionInteraction.TNT : Level.ExplosionInteraction.NONE);
        this.discard();
    }

    /** 入轨：写 SavedData，实体消失。 */
    private void reachOrbit(ServerLevel server) {
        a_silly_cat.golems_arsenal.tech.orbit.OrbitalPlatforms.addPlatform(
                server, this.blockPosition(), this.ownerId());
        server.sendParticles(ParticleTypes.FIREWORK,
                this.getX(), this.getY(), this.getZ(), 40, 2.0, 2.0, 2.0, 0.2);
        server.playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.NEUTRAL, 4.0F, 0.8F);
        this.discard();
    }

    // ------------------------------------------------------------------
    // 归属
    // ------------------------------------------------------------------

    public UUID ownerId() {
        return this.getPersistentData().hasUUID(TAG_OWNER)
                ? this.getPersistentData().getUUID(TAG_OWNER) : null;
    }

    public void setOwner(Player player) {
        this.getPersistentData().putUUID(TAG_OWNER, player.getUUID());
    }

    public Player getOwnerPlayer() {
        UUID id = ownerId();
        if (id == null || !(this.level() instanceof ServerLevel server)) {
            return null;
        }
        return server.getPlayerByUUID(id);
    }

    // ------------------------------------------------------------------
    // 交互 / 掉落
    // ------------------------------------------------------------------

    /**
     * 右键：打开燃料与发射界面。
     *
     * <p><b>界面尚未实现</b>——需要 `MenuType` + l2 的 GUI 框架（调研已完成，见对话记录）。
     * 这里先做成"右键待发状态就点火"的临时入口，方便在界面做好之前测试整套逻辑。
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (this.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (this.state() != STATE_IDLE) {
            return InteractionResult.PASS;
        }
        // 打开界面（加燃料 + 按发射）。菜单通过 MenuProvider 的 extraData 拿到实体 id。
        if (player instanceof ServerPlayer serverPlayer) {
            NetworkHooks.openScreen(serverPlayer, this, buf -> buf.writeInt(this.getId()));
        }
        return InteractionResult.CONSUME;
    }

    /**
     * 被打掉：退回火箭物品，倒计时自然作废。
     *
     * <p>用户定的"实体消失 10 秒倒计时也消失"——所以这里只要取消状态即可，
     * 计时器是实体自己的字段，实体没了它也就没了。
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide) {
            return false;
        }
        if (this.state() == STATE_ASCENT) {
            return false;   // 升空后打不掉（要么入轨要么爆炸）
        }
        if (this.state() == STATE_COUNTDOWN) {
            this.abortCountdown();
            return false;
        }
        if (!this.isRemoved()) {
            // TODO 退回火箭物品（物品类尚未实现）
            this.discard();
        }
        return true;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        setState(tag.getInt(TAG_STATE));
        this.entityData.set(DATA_COUNTDOWN, tag.getInt(TAG_COUNTDOWN));
        this.ascentTicks = tag.getInt(TAG_ASCENT);
        // 燃料槽里的储气物品：存在 MEK 槽位自己的 NBT 里
        if (FuelBridge.available() && tag.contains(TAG_FUEL_ITEM)) {
            FuelBridge.loadSlot(this, tag.getCompound(TAG_FUEL_ITEM));
        }
        // 气体储量存在储罐自己的 NBT 里（MEK 的格式），我们只是搬运
        if (FuelBridge.available() && tag.contains(TAG_FUEL_TANK)) {
            FuelBridge.load(this, tag.getCompound(TAG_FUEL_TANK));
            // **存量和气体种类必须都补写**。
            //
            // 以前只补了 DATA_FUEL，DATA_FUEL_GAS 只在 tick() 的 fuelDirty 分支里写。
            // 而读档时 `deserializeNBT` 到底会不会回调 IContentsListener（把 fuelDirty
            // 置 true）是不确定的——一旦不回调，客户端就永远不知道里面是哪种气体，
            // 界面只能退回"没有气体"的显示。
            //
            // 这里直接补两个字段，并且主动置 fuelDirty，让下一 tick 再同步一次，
            // 这样不依赖任何隐式副作用。
            this.entityData.set(DATA_FUEL, this.fuelStored());
            this.entityData.set(DATA_FUEL_GAS, FuelBridge.gasId(this));
            this.fuelDirty = true;
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt(TAG_STATE, this.state());
        tag.putInt(TAG_COUNTDOWN, this.countdown());
        tag.putInt(TAG_ASCENT, this.ascentTicks);
        if (FuelBridge.available()) {
            tag.put(TAG_FUEL_ITEM, FuelBridge.saveSlot(this));
        }
        if (FuelBridge.available()) {
            tag.put(TAG_FUEL_TANK, FuelBridge.save(this));
        }
    }

    /**
     * 实体移除时松开对能力提供者的引用。
     *
     * <p>不再需要"从静态表里删掉自己"——储罐现在是挂在实体上的能力
     * （{@code AttachCapabilitiesEvent}），随实体一起被回收。
     */
    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        this.gasProvider = null;
    }

    /** 客户端：倒计时冒烟、升空喷焰，让远处的人也能看见（服务端已经在发包，这里只补近距离的）。 */
    private void clientTick() {
        if (this.state() == STATE_COUNTDOWN && this.tickCount % 3 == 0) {
            this.level().addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                    this.getX() + (this.random.nextDouble() - 0.5) * 3.0,
                    this.getY() + 0.2,
                    this.getZ() + (this.random.nextDouble() - 0.5) * 3.0,
                    0.0, 0.04, 0.0);
        } else if (this.state() == STATE_ASCENT) {
            // 尾焰的火焰芯（烟与云雾由服务端发包，避免两边重复）
            this.level().addParticle(ParticleTypes.FLAME,
                    this.getX(), this.getY() - 0.5, this.getZ(), 0.0, -0.2, 0.0);
        }
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    /**
     * 剔除盒。
     *
     * <p><b>为什么要撑开这么多</b>：模型绘制时会整体上移/下移一个 {@link #rocketVerticalOffset()}
     * （Blockbench 导出的模型自带原点，见 {@code RocketRenderer}），而实体自己的判定盒是不动的。
     * 原版剔除盒只包住判定盒，于是**模型一旦被抬到盒子外面，就会在高处被剔除掉**——
     * 表现就是"飞到一定高度模型突然消失，但碰撞箱还在"。
     *
     * <p>这里上下各撑开一个模型高度：宁可多留余量（剔除盒大一点只是少剔几个实体，代价极小），
     * 也不要出现"模型莫名消失"这种难查的现象。
     */
    @Override
    public AABB getBoundingBoxForCulling() {
        return super.getBoundingBoxForCulling()
                .inflate(2.5, MODEL_HEIGHT, 2.5);
    }

    @Override
    public void push(Entity entity) {
    }

    @Override
    public boolean isPushable() {
        return false;
    }
}
