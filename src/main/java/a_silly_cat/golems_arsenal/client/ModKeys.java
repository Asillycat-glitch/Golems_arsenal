package a_silly_cat.golems_arsenal.client;

import a_silly_cat.golems_arsenal.Golems_arsenal;
import a_silly_cat.golems_arsenal.init.ModNetwork;
import a_silly_cat.golems_arsenal.network.ServerboundAuthorizePacket;
import com.mojang.blaze3d.platform.InputConstants;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 授权键（默认 O）：手里拿着命令手杖、准星对着傀儡时按下，请求服务端为这只傀儡"授权"。
 * <p>
 * 客户端只做"按键 → 发一个包"，是否真的成立完全由服务端判断（隐藏彩蛋不该在客户端提前暴露状态）。
 */
public final class ModKeys {
    public static final KeyMapping AUTHORIZE = new KeyMapping("key.golems_arsenal.authorize",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, "key.categories.golems_arsenal");

    private ModKeys() {
    }

    /** MOD 总线：注册键位。 */
    @Mod.EventBusSubscriber(modid = Golems_arsenal.MODID, value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(AUTHORIZE);
        }

        private Registration() {
        }
    }

    /** FORGE 总线：按键处理。 */
    @Mod.EventBusSubscriber(modid = Golems_arsenal.MODID, value = Dist.CLIENT)
    public static final class Input {
        @SubscribeEvent
        public static void onKeyInput(InputEvent.Key event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.screen != null) {
                return;
            }
            while (AUTHORIZE.consumeClick()) {
                if (mc.hitResult instanceof EntityHitResult hit
                        && hit.getEntity() instanceof AbstractGolemEntity<?, ?> golem) {
                    ModNetwork.CHANNEL.sendToServer(new ServerboundAuthorizePacket(golem.getId()));
                }
            }
        }

        private Input() {
        }
    }
}
