package a_silly_cat.golems_arsenal.mixin;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.ModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.slf4j.Logger;

import java.util.List;
import java.util.Set;

/**
 * Only applies the Golem Magicka mixins when that mod is present, so nothing in this mod touches
 * golemmagicka classes when it is not installed.
 */
public final class GolemMagickaMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // ModList may not be initialized yet when the mixin config is prepared, so guard the
        // normal lookup and fall back to checking whether the target class actually exists.
        ModList list = ModList.get();
        if (list != null) {
            boolean apply = list.isLoaded("golemmagicka");
            LOGGER.info("[GolemsArsenal] mixin gate: {} -> {} (golemmagicka loaded={})",
                    mixinClassName, apply, apply);
            return apply;
        }
        try {
            // Check for the class FILE without loading it: loading the target here (during mixin
            // prepare) would cache it untransformed and silently prevent the mixin from applying.
            ClassLoader loader = getClass().getClassLoader();
            boolean present = loader.getResource(targetClassName.replace('.', '/') + ".class") != null;
            LOGGER.info("[GolemsArsenal] mixin gate: {} -> {} (target class present={}, ModList unavailable)",
                    mixinClassName, present, present);
            return present;
        } catch (Throwable ignored) {
            LOGGER.info("[GolemsArsenal] mixin gate: {} -> false (lookup failed, ModList unavailable)",
                    mixinClassName);
            return false;
        }
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        LOGGER.info("[GolemsArsenal] applying mixin {} to {}", mixinClassName, targetClassName);
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
