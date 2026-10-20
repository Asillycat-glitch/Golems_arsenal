package a_silly_cat.golems_arsenal.base.command;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;

/**
 * 指令升级自建的伤害类型。
 *
 * <p>它是数据包定义的（{@code data/golems_arsenal/damage_type/command_extra.json}），
 * 这里只提供 {@link ResourceKey} 和构造 {@link DamageSource} 的助手。</p>
 *
 * <h2>为什么伤害类型要单独定义而不是复用 {@code playerAttack}</h2>
 * 追击段要「无视无敌帧」，这件事在原版里只能靠<b>伤害类型标签</b>表达
 * （{@code bypasses_cooldown}）。把标签打在 {@code minecraft:player_attack} 上会污染整个游戏，
 * 所以它需要自己的类型。
 *
 * <h2>这里曾经还有一个 {@code command_magic}</h2>
 * 它属于已删除的「魔法追击」效果（追加一段无视护甲与抗性的魔法伤害）。
 * 删掉的原因是那个效果<b>对两个易伤都免疫</b>：它走 {@code bypasses_armor} 绕过护甲，
 * 所以物理易伤（削护甲韧性）无效；它又是直接 {@code target.hurt()} 打出的第二段、
 * 不经过 l2 管线，所以魔法易伤（削 {@code damage_reduction} 乘区）也无效。
 * 一个"吃不到任何增益"的效果不该存在，于是连同它的伤害类型一起删了。
 */
public final class CommandDamageTypes {

    /**
     * 追击伤害（效果 8）。标记 {@code bypasses_cooldown}，所以哪怕目标还在无敌帧里也能打进去
     * ——「无视无敌帧」在原版里就是这个标签的语义。
     */
    public static final ResourceKey<DamageType> COMMAND_EXTRA =
            ResourceKey.create(Registries.DAMAGE_TYPE, Golems_arsenal.id("command_extra"));

    private CommandDamageTypes() {
    }

    /**
     * 造一个伤害源。
     * <p>
     * 拿不到类型的 holder 时返回 {@code null} 而不是抛异常：数据包被别的整合包覆盖掉、
     * 或者类型写错了名字时，宁可这一枚效果静默失效，也不要把整场战斗崩掉。
     */
    private static DamageSource source(ServerLevel level, ResourceKey<DamageType> key, Entity attacker) {
        var holder = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolder(key);
        return holder.map(type -> new DamageSource(type, attacker)).orElse(null);
    }

    public static DamageSource extra(ServerLevel level, Entity attacker) {
        return source(level, COMMAND_EXTRA, attacker);
    }
}
