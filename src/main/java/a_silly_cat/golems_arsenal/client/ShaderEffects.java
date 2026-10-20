package a_silly_cat.golems_arsenal.client;

import a_silly_cat.golems_arsenal.Config;
import a_silly_cat.golems_arsenal.Golems_arsenal;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 光影包下的“曝光刹车”。
 *
 * <p><b>问题</b>：MakeUp 这类包把
 * {@code luma(color * exposure) > 0.85} 的像素当成 bloom 源，而且夜里 exposure 可以到 3.6。
 * 我们的激光/冲击是近白、全亮、多层叠在一起的几何，任何一层都够触发 bloom；
 * 一旦触发，bloom 半径是 0.1 个屏幕，看起来就是一片白。
 *
 * <p><b>做法</b>：不试图关掉 shaderpack 的 bloom（做不到），而是让我们的自发光几何
 * “低于它的 bloom 预算”：
 * <ul>
 *   <li>亮度倍率（config {@code orbital_beam.shader_effect_brightness}，默认 0.45）；</li>
 *   <li>颜色往暖金拉，避免纯白没有色相余量；</li>
 *   <li>渲染器里再配合背面剔除，减少叠层。</li>
 * </ul>
 *
 * <p>是否“正在使用光影包”优先走 Iris API（Oculus 也带），失败则读
 * {@code config/oculus.properties} / {@code config/iris.properties} 的 {@code enableShaders}。
 * 二十 tick 刷新一次，切换光影后最多一秒生效。
 */
@Mod.EventBusSubscriber(modid = Golems_arsenal.MODID, value = Dist.CLIENT)
public final class ShaderEffects {

    private static final float DEFAULT_BRIGHTNESS = 0.35F;

    private static boolean apiChecked;
    private static Method irisIsShaderPackInUse;
    private static Object irisApi;

    private static boolean shaderPackActive;
    private static int tick;

    private ShaderEffects() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++tick % 20 == 0) {
            refresh();
        }
    }

    public static boolean shaderPackActive() {
        if (!apiChecked) {
            refresh();
        }
        return shaderPackActive;
    }

    /** 光影下的自发光 alpha 倍率；无光影时始终 1.0。 */
    public static float brightness() {
        if (!shaderPackActive()) {
            return 1.0F;
        }
        try {
            if (Config.SHADER_EFFECT_BRIGHTNESS != null) {
                Double value = Config.SHADER_EFFECT_BRIGHTNESS.get();
                if (value != null) {
                    return (float) Math.max(0.05D, Math.min(1.0D, value));
                }
            }
        } catch (Throwable ignored) {
        }
        return DEFAULT_BRIGHTNESS;
    }

    /** 0 = 保留原色，1 = 完全拉到暖金。 */
    public static float warmth() {
        return shaderPackActive() ? 1.0F : 0.0F;
    }

    public static float tintR(float r) {
        // 红色保留：暖色靠压 G/B 得到，而不是把所有颜色拉向同一个金色，
        // 否则"白热芯 / 红热边"的温度层次会被抹平。
        return r;
    }

    public static float tintG(float g) {
        return g * (1.0F - 0.28F * warmth());
    }

    public static float tintB(float b) {
        return b * (1.0F - 0.65F * warmth());
    }

    private static void refresh() {
        if (!apiChecked) {
            resolveIrisApi();
        }
        if (irisIsShaderPackInUse != null && irisApi != null) {
            try {
                shaderPackActive = Boolean.TRUE.equals(irisIsShaderPackInUse.invoke(irisApi));
                return;
            } catch (Throwable t) {
                irisIsShaderPackInUse = null;
                irisApi = null;
            }
        }
        shaderPackActive = readEnableShaders();
    }

    private static void resolveIrisApi() {
        apiChecked = true;
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            irisApi = api.getMethod("getInstance").invoke(null);
            irisIsShaderPackInUse = api.getMethod("isShaderPackInUse");
        } catch (Throwable ignored) {
            irisIsShaderPackInUse = null;
            irisApi = null;
        }
    }

    /**
     * Iris API 不在时的兜底：直接读配置文件。
     * 注意这是“光影包是否开启”，不是“Oculus 是否加载”——没开光影就不该压暗。
     */
    private static boolean readEnableShaders() {
        for (String name : new String[]{"oculus.properties", "iris.properties"}) {
            Path path = FMLPaths.CONFIGDIR.get().resolve(name);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(path)) {
                properties.load(in);
            } catch (Throwable ignored) {
                continue;
            }
            return Boolean.parseBoolean(properties.getProperty("enableShaders", "false"));
        }
        return false;
    }
}
