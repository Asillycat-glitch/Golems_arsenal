package a_silly_cat.golems_arsenal.testbench;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 私有测试台的入口（PRIVATE TESTBENCH）。
 *
 * <p><b>这不是 mod 内容。</b>本包只存在于 {@code testbench/} 源集里（见仓库根
 * {@code testbench/PRIVATE-README.md} 与 {@code build.gradle} 顶部的说明）：默认构建完全不编译、
 * 不打包，只有 {@code gradlew build -Ptestbench} 才会并进
 * {@code golems_arsenal-<版本>-testbench.jar}。目录已在 {@code .gitignore} 里。</p>
 *
 * <p>禁止提交 / 禁止发布 / 禁止上传 GitHub。测出结论后只把结论搬进 {@code src/main}，
 * 实现按正式标准重写。</p>
 */
@Mod.EventBusSubscriber(modid = "golems_arsenal", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class PrivateTestbench {

    public static final Logger LOGGER = LoggerFactory.getLogger("GolemsArsenalTestbench");

    /** 横幅文本；任何测试台入口都可以顺手打一遍。 */
    public static final String BANNER =
            "================ 私有测试台已加载（PRIVATE TESTBENCH BUILD）—— 不要发布 / 不要上传 ================";

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        LOGGER.warn(BANNER);
        TestbenchCore.register();
        // P0：只读血量观测。l2 监听器只记录 finalize/写血配对，不修改任何伤害。
        HealthTrace.registerL2();
        LOGGER.warn("杠杆默认全关：/ga debug list 查看，/ga debug enable 开启，"
                + "/ga debug reset 复位（每次服务器启动也会自动复位）。");
        LOGGER.warn("P0 血量观测：/ga health track control|test → /ga health start → 打同一个伤害源 → /ga health stop。");
    }

    private PrivateTestbench() {
    }
}
