package a_silly_cat.golems_arsenal.tech.energy;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 读取 {@code data/<namespace>/minion_energy/*.json}，构建反射适配器。
 *
 * <p>每条 JSON 的形状见 {@code docs/MINION-ENERGY-DATAPACK.md}。解析失败只跳过该条并打印
 * 警告，不会让 {@code /reload} 失败。每次 reload 整体替换注册表。
 */
public final class MinionEnergyReloadListener extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new Gson();

    public MinionEnergyReloadListener() {
        super(GSON, "minion_energy");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources,
                         ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<EntityType<?>, MinionEnergyAccess.Access> byType = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            ResourceLocation file = entry.getKey();
            try {
                JsonObject json = entry.getValue().getAsJsonObject();
                List<ResourceLocation> ids = readEntityIds(json, file);
                if (ids.isEmpty()) {
                    Golems_arsenal.LOGGER.warn("[minion_energy] {}: no 'entity' or 'entities'", file);
                    continue;
                }
                for (ResourceLocation id : ids) {
                    EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
                    if (type == null) {
                        Golems_arsenal.LOGGER.warn("[minion_energy] {}: unknown entity type '{}'", file, id);
                        continue;
                    }
                    ReflectionEnergyAdapter adapter = ReflectionEnergyAdapter.parse(
                            json, type.getBaseClass(), file, id.toString());
                    if (adapter != null) {
                        if (byType.put(type, adapter) != null) {
                            Golems_arsenal.LOGGER.warn(
                                    "[minion_energy] {}: duplicate entry for '{}'; the later file wins",
                                    file, id);
                        }
                    }
                }
            } catch (RuntimeException e) {
                Golems_arsenal.LOGGER.warn("[minion_energy] {}: parse failed: {}", file, e.toString());
            }
        }
        MinionEnergyRegistry.replace(byType);
        Golems_arsenal.LOGGER.info("[minion_energy] loaded {} reflection adapters", byType.size());
    }

    private static List<ResourceLocation> readEntityIds(JsonObject json, ResourceLocation file) {
        List<ResourceLocation> ids = new ArrayList<>();
        if (json.has("entity")) {
            addId(ids, json.get("entity").getAsString(), file);
        }
        if (json.has("entities")) {
            for (JsonElement element : json.getAsJsonArray("entities")) {
                addId(ids, element.getAsString(), file);
            }
        }
        return ids;
    }

    private static void addId(List<ResourceLocation> ids, String value, ResourceLocation file) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) {
            Golems_arsenal.LOGGER.warn("[minion_energy] {}: invalid entity id '{}'", file, value);
            return;
        }
        ids.add(id);
    }
}
