package a_silly_cat.golems_arsenal.compat.l2damagetracker;

import a_silly_cat.golems_arsenal.base.event.KeySwordEventHandler;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;

/**
 * 数据包驱动的「END 层返还」表：<b>任何武器都能被数据包授予返还</b>，不需要改代码。
 *
 * <p>文件位置：{@code data/<命名空间>/golems_arsenal_restitution/*.json}（可放多个文件，会合并）</p>
 *
 * <pre>
 * {
 *   "values": [
 *     { "item": "golems_arsenal:key_sword",          "value": 0.15, "max": 0.30 },
 *     { "item": "golems_arsenal:golem_energy_katana","value": 0.10 },
 *     { "item": "some_mod:some_weapon",              "value": 0.05 }
 *   ]
 * }
 * </pre>
 *
 * <ul>
 *   <li>{@code value}：基础返还比例（0~1，实际还会和傀儡的 {@code golems_arsenal:end_restitution}
 *       属性相加，并由监听器统一夹到 <b>0.8</b>）。</li>
 *   <li>{@code max}：可选。填了就从 {@code value} 开始、随键刃的「处决进度」
 *       （{@link KeySwordEventHandler#executeProgress}，目标血量越低越高）线性升到 {@code max}；
 *       不填等于恒定 {@code value}。适合"敌人越残越能穿减伤"的武器。</li>
 * </ul>
 *
 * <p>缺失/写错的文件会被跳过并记一条日志，不会影响游戏启动。</p>
 */
public final class RestitutionData extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    private static final String FOLDER = "golems_arsenal_restitution";
    private static final RestitutionData INSTANCE = new RestitutionData();
    private static final Map<Item, Entry> ENTRIES = new HashMap<>();

    /** 单件武器的返还配置：{@code value} 基础值，{@code max} 处决进度满时的上限。 */
    public record Entry(double value, double max) {
    }

    private RestitutionData() {
        super(GSON, FOLDER);
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(RestitutionData::onAddReloadListener);
    }

    private static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(INSTANCE);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager,
                         ProfilerFiller profiler) {
        ENTRIES.clear();
        files.forEach((id, element) -> {
            try {
                JsonObject root = element.getAsJsonObject();
                JsonArray values = GsonHelper.getAsJsonArray(root, "values");
                for (JsonElement value : values) {
                    readEntry(value.getAsJsonObject());
                }
            } catch (Exception e) {
                a_silly_cat.golems_arsenal.Golems_arsenal.LOGGER.warn(
                        "skipping bad restitution file {}: {}", id, e.toString());
            }
        });
    }

    private static void readEntry(JsonObject obj) {
        ResourceLocation itemId = new ResourceLocation(GsonHelper.getAsString(obj, "item"));
        Item item = ForgeRegistries.ITEMS.getValue(itemId);
        if (item == null) {
            a_silly_cat.golems_arsenal.Golems_arsenal.LOGGER.warn(
                    "restitution entry references unknown item {}", itemId);
            return;
        }
        double value = Mth.clamp(GsonHelper.getAsDouble(obj, "value"), 0.0D, 1.0D);
        double max = obj.has("max") ? Mth.clamp(GsonHelper.getAsDouble(obj, "max"), 0.0D, 1.0D) : value;
        ENTRIES.put(item, new Entry(value, Math.max(value, max)));
    }

    /**
     * 这把武器本次能拿到多少返还：{@code value}（或按处决进度插值到 {@code max}）。
     * 和傀儡属性相加、夹到 0.8 的收尾在 {@link DamageRestitutionListener} 里做。
     */
    public static double valueFor(ItemStack stack, LivingEntity target) {
        Entry entry = stack == null || stack.isEmpty() ? null : ENTRIES.get(stack.getItem());
        if (entry == null) {
            return 0.0D;
        }
        if (entry.max() > entry.value()) {
            double progress = Mth.clamp(KeySwordEventHandler.executeProgress(target), 0.0D, 1.0D);
            return entry.value() + (entry.max() - entry.value()) * progress;
        }
        return entry.value();
    }

    /** 表里有没有这件武器（诊断用）。 */
    public static boolean hasEntry(ItemStack stack) {
        return stack != null && !stack.isEmpty() && ENTRIES.containsKey(stack.getItem());
    }
}
