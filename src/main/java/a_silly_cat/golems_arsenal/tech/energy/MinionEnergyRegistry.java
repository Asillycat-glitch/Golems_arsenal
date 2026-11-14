package a_silly_cat.golems_arsenal.tech.energy;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.Map;

/**
 * 数据包加载出来的“实体 → FE 访问器”注册表。
 *
 * <p>路径：{@code data/<namespace>/minion_energy/<name>.json}。每次 {@code /reload} 重建。
 * 反射只在**加载时**解析一次 Field，运行期只做 Field.get/set，不在每 tick 查类名。
 *
 * <p>v1 只按**精确实体类型**匹配（一条 JSON 可以用 {@code entities} 列多个 id）。
 * 实体标签没有做：同一个字段名不一定在标签里的每个类上都存在，强行匹配容易静默失效。
 */
public final class MinionEnergyRegistry {

    private static volatile Map<EntityType<?>, MinionEnergyAccess.Access> byType = Map.of();

    public static void replace(Map<EntityType<?>, MinionEnergyAccess.Access> types) {
        byType = Map.copyOf(types);
    }

    /** 找到这个实体对应的数据包适配器；没有返回 null。 */
    public static MinionEnergyAccess.Access find(Entity entity) {
        return byType.get(entity.getType());
    }

    public static int size() {
        return byType.size();
    }

    private MinionEnergyRegistry() {
    }
}
