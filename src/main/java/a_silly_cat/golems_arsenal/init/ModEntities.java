package a_silly_cat.golems_arsenal.init;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.base.entity.KeyBladeEntity;
import a_silly_cat.golems_arsenal.base.entity.LightPillarEntity;
import a_silly_cat.golems_arsenal.base.entity.OrbitalBeamEntity;
import a_silly_cat.golems_arsenal.base.entity.GuidanceBeamEntity;
import a_silly_cat.golems_arsenal.base.entity.OrbitalImpactEntity;
import a_silly_cat.golems_arsenal.base.entity.OrbitalProjectileEntity;
import a_silly_cat.golems_arsenal.base.entity.OrbitalSigilEntity;
import a_silly_cat.golems_arsenal.base.entity.PhantomBladeEntity;
import a_silly_cat.golems_arsenal.base.entity.RocketEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Golems_arsenal.MODID);

    /**
     * The flying key blade of the "Key Blade Spin" special move. It is an {@code ItemEntity}
     * subclass, so the vanilla item renderer draws the rotating key-sword model with no custom
     * client code beyond registering the entity type.
     */
    public static final RegistryObject<EntityType<KeyBladeEntity>> KEY_BLADE = ENTITY_TYPES.register(
            "key_blade",
            () -> EntityType.Builder.<KeyBladeEntity>of(KeyBladeEntity::new, MobCategory.MISC)
                    .sized(0.6f, 0.6f)
                    .clientTrackingRange(10)
                    .updateInterval(2)
                    .build("key_blade"));

    /** 幻梦零甩出的剑气：纯显示 + 穿透伤害的平面实体。 */
    public static final RegistryObject<EntityType<PhantomBladeEntity>> PHANTOM_BLADE =
            ENTITY_TYPES.register(
                    "phantom_blade",
                    () -> EntityType.Builder.<PhantomBladeEntity>of(PhantomBladeEntity::new,
                                    MobCategory.MISC)
                            .sized(0.8f, 1.2f)
                            .clientTrackingRange(10)
                            .updateInterval(2)
                            .build("phantom_blade"));

    /** 光柱护体：立在原地自转、对半径内敌人造成一次伤害的柱子。 */
    public static final RegistryObject<EntityType<LightPillarEntity>> LIGHT_PILLAR =
            ENTITY_TYPES.register(
                    "light_pillar",
                    () -> EntityType.Builder.<LightPillarEntity>of(LightPillarEntity::new,
                                    MobCategory.MISC)
                            .sized(1.0f, 3.6f)
                            .clientTrackingRange(10)
                            .updateInterval(2)
                            .build("light_pillar"));

    /**
     * 天基激光光束：从高空竖直灌到落点、对柱体内敌人持续造成 MEK 激光伤害。
     * <p>
     * 判定盒给得很小（落点那一格）——光束的高度只用于渲染与伤害范围计算，
     * 实体本身不需要占一个 300 格高的碰撞体（那会让实体遍历与区块判定变得很贵）。
     * 跟踪范围给大一些：玩家可能在远处就看到这道从天而降的光。
     */
    public static final RegistryObject<EntityType<OrbitalBeamEntity>> ORBITAL_BEAM =
            ENTITY_TYPES.register(
                    "orbital_beam",
                    () -> EntityType.Builder.<OrbitalBeamEntity>of(OrbitalBeamEntity::new,
                                    MobCategory.MISC)
                            .sized(1.0f, 1.0f)
                            .clientTrackingRange(64)
                            .updateInterval(10)
                            .build("orbital_beam"));

    /**
     * 引导光束：从高空照下来的细光，落点沿轨迹移动。纯视觉实体（无伤害、无破坏）。
     *
     * <p>实体本身**停在圆心不动**，只同步进度，落点由渲染端算——所以不需要高频位置同步。
     * 跟踪范围给大：它可能有 300 格高。
     */
    public static final RegistryObject<EntityType<GuidanceBeamEntity>> GUIDANCE_BEAM =
            ENTITY_TYPES.register(
                    "guidance_beam",
                    () -> EntityType.Builder.<GuidanceBeamEntity>of(GuidanceBeamEntity::new,
                                    MobCategory.MISC)
                            .sized(0.6f, 0.6f)
                            .clientTrackingRange(64)
                            .updateInterval(2)
                            .build("guidance_beam"));

    /**
     * 法阵：贴地画出的光纹。**作为渲染示例保留**（用户要求，将来可能做法阵类联动）。
     *
     * <p>和 {@link GuidanceBeamEntity} 共用同一条轨迹数据，形状一致；
     * 区别是它把轨迹**画出来**，引导光只是沿轨迹移动。
     */
    public static final RegistryObject<EntityType<OrbitalSigilEntity>> ORBITAL_SIGIL =
            ENTITY_TYPES.register(
                    "orbital_sigil",
                    () -> EntityType.Builder.<OrbitalSigilEntity>of(OrbitalSigilEntity::new,
                                    MobCategory.MISC)
                            .sized(0.6f, 0.6f)
                            .clientTrackingRange(32)
                            .updateInterval(2)
                            .build("orbital_sigil"));

    /**
     * 发射段落点特效：贴地扩散的冲击环 + 中心爆闪。纯视觉实体。
     *
     * <p>参考秘法涡流 {@code Shockwave} 的参数结构（多圈各自的速度/淡出），
     * 以及 {@code mgdp}「五雷天罚」的"瞬间打击感"（它只是召唤纯视觉闪电）。
     */
    public static final RegistryObject<EntityType<OrbitalImpactEntity>> ORBITAL_IMPACT =
            ENTITY_TYPES.register(
                    "orbital_impact",
                    () -> EntityType.Builder.<OrbitalImpactEntity>of(OrbitalImpactEntity::new,
                                    MobCategory.MISC)
                            .sized(0.6f, 0.6f)
                            .clientTrackingRange(64)
                            .updateInterval(2)
                            .build("orbital_impact"));

    /**
     * 白热射弹：瞄准收束后从高空射下的那一发，落地触发 {@link OrbitalImpactEntity}。
     *
     * <p>纯视觉（伤害由落点实体负责）。它移动很快（12 格/tick），所以同步间隔给小
     * （{@code updateInterval = 1}）——否则客户端插值会看到它"跳格"。
     */
    public static final RegistryObject<EntityType<OrbitalProjectileEntity>> ORBITAL_PROJECTILE =
            ENTITY_TYPES.register(
                    "orbital_projectile",
                    () -> EntityType.Builder.<OrbitalProjectileEntity>of(OrbitalProjectileEntity::new,
                                    MobCategory.MISC)
                            .sized(1.0f, 1.0f)
                            .clientTrackingRange(96)
                            .updateInterval(1)
                            .build("orbital_projectile"));

    /**
     * 天基火箭：自己就是平台（也是卫星）。
     *
     * <p><b>尺寸必须和模型一致</b>：模型是 4.44 × 14.94 × 4.50 格（Blockbench 量出来的），
     * 判定盒就给这个尺寸。第一版我为了"省性能"把高度压到 4 格，结果**判定盒和模型完全不符**
     * （开 F3+B 一眼就能看到模型大半在盒子外面），而且埋下了"脚底对不上"的隐患——
     * 视觉、判定、剔除三者应该用同一套尺寸。
     *
     * <p>宽度取 5.0 是为了对齐"占地 5×5"的规则（升空前要检查的空地大小）。
     */
    public static final RegistryObject<EntityType<RocketEntity>> ROCKET =
            ENTITY_TYPES.register(
                    "rocket",
                    () -> EntityType.Builder.<RocketEntity>of(RocketEntity::new,
                                    MobCategory.MISC)
                            .sized(5.0f, 14.94f)
                            .clientTrackingRange(32)
                            .updateInterval(2)
                            .fireImmune()
                            .build("rocket"));

    private ModEntities() {
    }

    public static void register(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
    }
}
