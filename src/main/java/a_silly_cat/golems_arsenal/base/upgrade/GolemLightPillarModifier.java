package a_silly_cat.golems_arsenal.base.upgrade;

import a_silly_cat.golems_arsenal.base.entity.LightPillarEntity;
import dev.xkmc.modulargolems.content.core.StatFilterType;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;

import java.util.List;

/**
 * 光柱护体的"旧独立升级"载体。<b>效果已经移交给 Omega 四件套联动</b>
 * （见 {@code GolemsArsenalSetHandler}：受击 → 4 道光柱 2 秒、冷却 5 秒、期间无敌）。
 * <p>
 * 这个 modifier 与对应的物品 id 仍然保留注册（避免老存档里的物品变成 AirItem 而崩），
 * 但不再有任何主动效果，也不再出现在创造栏里。
 */
public class GolemLightPillarModifier extends GolemModifier {
    public static final int MAX_LEVEL = 1;

    /** 持续时长（tick）：2 秒。 */
    private static final int ACTIVE_TICKS = 40;
    /** 冷却（tick）：30 秒。 */
    private static final int COOLDOWN_TICKS = 600;
    /** 几根柱子。 */
    private static final int PILLAR_COUNT = 4;
    /** 柱子环绕半径（格）。 */
    private static final double RING_RADIUS = 1.3;
    /** 每根柱子的命中半径（格）。 */
    private static final double HIT_RADIUS = 1.6;
    /** 单根柱子造成的伤害 = 攻击力 × 该系数（每个敌人每根柱子只吃一次）。 */
    private static final double DAMAGE_RATIO = 0.75;

    private static final String CD_KEY = "GolemsArsenalPillarCooldown";
    private static final String UNTIL_KEY = "GolemsArsenalPillarUntil";

    public GolemLightPillarModifier() {
        super(StatFilterType.HEALTH, MAX_LEVEL);
    }

    @Override
    public Component getTooltip(int level) {
        return Component.translatable("upgrade.golems_arsenal.light_pillar")
                .withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    @Override
    public List<MutableComponent> getDetail(int level) {
        return List.of(
                Component.translatable("upgrade.golems_arsenal.light_pillar.desc")
                        .withStyle(ChatFormatting.GREEN),
                Component.translatable("upgrade.golems_arsenal.light_pillar.detail",
                        ACTIVE_TICKS / 20, COOLDOWN_TICKS / 20).withStyle(ChatFormatting.GRAY));
    }

}
