package a_silly_cat.golems_arsenal.mixin;

import net.minecraftforge.fml.ModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Only applies the Golem Magicka mixins when that mod is present, so nothing in this mod touches
 * golemmagicka classes when it is not installed.
 */
public final class GolemMagickaMixinPlugin implements IMixinConfigPlugin {
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // ModList may not be initialized yet when the mixin config is prepared, so guard the
        // normal lookup and fall back to checking whether the target class actually exists.
        ModList list = ModList.get();
        if (list != null) {
            return list.isLoaded("golemmagicka");
        }
        try {
            // Check for the class FILE without loading it: loading the target here (during mixin
            // prepare) would cache it untransformed and silently prevent the mixin from applying.
            ClassLoader loader = getClass().getClassLoader();
            return loader.getResource(targetClassName.replace('.', '/') + ".class") != null;
        } catch (Throwable ignored) {
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
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
