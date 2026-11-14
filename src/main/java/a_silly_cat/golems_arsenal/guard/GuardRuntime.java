package a_silly_cat.golems_arsenal.guard;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.Golems_arsenal;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Guard 的运行时状态。
 *
 * <p>替代旧的 {@code GuardPolicy} 静态缓存：配置 load/reload 时重新计算，状态放在
 * 不可变 {@link GuardState} 里，由 {@code volatile} 发布。检测只记录“黑名单里命中且已加载”
 * 的 mod id，自动启用最高只到 {@link GuardMode#SHADOW}，绝不自动 ENFORCE。</p>
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class GuardRuntime {

    private static volatile GuardState state = GuardState.initial();

    private GuardRuntime() {
    }

    public static GuardState state() {
        return state;
    }

    public static GuardMode mode() {
        return state.mode();
    }

    public static Set<String> detectedMods() {
        return state.detectedMods();
    }

    public static boolean configLoaded() {
        return state.configLoaded();
    }

    @SubscribeEvent
    public static void onConfigLoading(ModConfigEvent.Loading event) {
        refresh(true);
    }

    @SubscribeEvent
    public static void onConfigReloading(ModConfigEvent.Reloading event) {
        refresh(true);
    }

    public static synchronized void refresh() {
        refresh(true);
    }

    private static synchronized void refresh(boolean loaded) {
        boolean auto = true;
        List<? extends String> blacklist = List.of();
        try {
            auto = Config.GUARD_AUTO_ENABLE.get();
            blacklist = Config.GUARD_BLACKLIST_MODS.get();
        } catch (Throwable ignored) {
            // 配置还没挂上：保持 OFF，但记录 loaded 标志，status 能看出来。
        }
        state = evaluate(loaded, auto, blacklist, GuardRuntime::isLoaded);
    }

    /**
     * 纯函数：配置是否加载、自动启用、黑名单、mod 是否加载 → 运行时状态。
     * 单测直接用这个，不碰 Forge 的静态状态。
     */
    static GuardState evaluate(boolean configLoaded, boolean autoEnable,
                               List<? extends String> blacklist,
                               Predicate<String> loaded) {
        if (!configLoaded) {
            return new GuardState(GuardMode.OFF, Set.of(), false);
        }
        if (!autoEnable || blacklist == null || blacklist.isEmpty()) {
            return new GuardState(GuardMode.OFF, Set.of(), true);
        }
        Set<String> detected = new LinkedHashSet<>();
        for (String id : blacklist) {
            if (id == null || id.isBlank()) {
                continue;
            }
            if (loaded.test(id)) {
                detected.add(id);
            }
        }
        GuardMode mode = detected.isEmpty() ? GuardMode.OFF : GuardMode.SHADOW;
        return new GuardState(mode, Set.copyOf(detected), true);
    }

    private static boolean isLoaded(String id) {
        try {
            return ModList.get().isLoaded(id);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String statusLine() {
        GuardState s = state;
        return "[guard] mode=" + s.mode()
                + " configLoaded=" + s.configLoaded()
                + " detected=" + s.detectedMods();
    }

    public record GuardState(GuardMode mode, Set<String> detectedMods, boolean configLoaded) {
        public GuardState {
            mode = Objects.requireNonNull(mode, "mode");
            // 排序后发布：/ga guard status 的输出不随 Set 实现/配置顺序漂移。
            detectedMods = Collections.unmodifiableSet(new TreeSet<>(detectedMods));
        }

        public static GuardState initial() {
            return new GuardState(GuardMode.OFF, Set.of(), false);
        }
    }
}
