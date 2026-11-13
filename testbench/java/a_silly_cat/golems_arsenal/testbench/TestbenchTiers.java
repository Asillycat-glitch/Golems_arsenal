package a_silly_cat.golems_arsenal.testbench;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Locale;

/**
 * 八个 tag 档位（PRIVATE TESTBENCH）。
 *
 * <p>伤害类型的 tag 是注册表级、数据包级的，注册完就固定，<b>运行时切不了</b>。所以"多 tag
 * 拿不定主意"的解法不是选一个，而是把要对比的组合全部预注册成档位，再用开关切档。
 * 档位定义见 {@code testbench/resources/data/golems_arsenal/damage_type/ga_test_*.json}
 * 与构建期生成的 tag 文件（{@code build.gradle} 里的 {@code generateTestbenchTags}）。</p>
 *
 * <table>
 *   <tr><th>档位</th><th>tag 组合</th><th>用途</th></tr>
 *   <tr><td>plain</td><td>无</td><td>基线</td></tr>
 *   <tr><td>armor</td><td>armor</td><td>护甲那一层值多少</td></tr>
 *   <tr><td>armor_ench</td><td>armor + enchantments</td><td>再加保护附魔</td></tr>
 *   <tr><td>pierce4</td><td>armor + enchantments + resistance + effects</td><td>完整"破防四件套"</td></tr>
 *   <tr><td>true_edge</td><td>pierce4 + invulnerability</td><td>是否连带吃掉 meror_defense / damage_cap / damage_reduction / 血量地板</td></tr>
 *   <tr><td>true_edge_cd</td><td>true_edge + cooldown</td><td>多段能不能落地</td></tr>
 *   <tr><td>magic</td><td>pierce4 + forge:is_magic</td><td>对照组：被 magic_immune 取消</td></tr>
 *   <tr><td>fire</td><td>pierce4 + minecraft:is_fire</td><td>对照组：被 fire_affinity 取消并送 buff</td></tr>
 * </table>
 */
public enum TestbenchTiers {
    /** 特殊档：不换伤害类型，杠杆直接作用在真实命中上（测"只加杠杆、不改 tag"）。 */
    VANILLA,
    PLAIN,
    ARMOR,
    ARMOR_ENCH,
    PIERCE4,
    TRUE_EDGE,
    TRUE_EDGE_CD,
    MAGIC,
    FIRE;

    public static final List<TestbenchTiers> ALL = List.of(values());

    /** 真正带伤害类型的档位（不含 {@link #VANILLA}），多段轮换用。 */
    public static final List<TestbenchTiers> REAL = List.of(
            PLAIN, ARMOR, ARMOR_ENCH, PIERCE4, TRUE_EDGE, TRUE_EDGE_CD, MAGIC, FIRE);

    private final String id = name().toLowerCase(Locale.ROOT);

    public String id() {
        return id;
    }

    /** {@link #VANILLA} 没有自己的伤害类型，返回 {@code null}。 */
    public ResourceKey<DamageType> key() {
        if (this == VANILLA) {
            return null;
        }
        return ResourceKey.create(Registries.DAMAGE_TYPE,
                new ResourceLocation("golems_arsenal", "ga_test_" + id));
    }

    /** 用这个档位的伤害类型造一个伤害源（攻击者＝傀儡或玩家）。 */
    public DamageSource source(Level level, Entity attacker) {
        var registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        return new DamageSource(registry.getHolderOrThrow(key()), attacker);
    }

    /** 这个伤害源是不是本测试台的某个档位；不是则返回 null。 */
    public static TestbenchTiers of(DamageSource source) {
        for (TestbenchTiers tier : values()) {
            if (source.is(tier.key())) {
                return tier;
            }
        }
        return null;
    }

    public static TestbenchTiers byId(String id) {
        for (TestbenchTiers tier : values()) {
            if (tier.id.equals(id)) {
                return tier;
            }
        }
        return null;
    }
}
