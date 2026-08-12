package a_silly_cat.golems_arsenal.mixin;

import a_silly_cat.golems_arsenal.energy.GolemEnergyProvider;
import a_silly_cat.golems_arsenal.energy.GolemEnergyStorage;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractGolemEntity.class)
public abstract class AbstractGolemEnergyMixin {
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void writeEnergy(CompoundTag tag, CallbackInfo ci) {
        ((AbstractGolemEntity<?, ?>) (Object) this).getCapability(GolemEnergyProvider.CAPABILITY).ifPresent(cap -> {
            GolemEnergyStorage e = (GolemEnergyStorage) cap;
            tag.putInt("GolemEnergy", e.getEnergyStored());
            tag.putInt("GolemMaxEnergy", e.getMaxEnergyStored());
        });
    }
    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void readEnergy(CompoundTag tag, CallbackInfo ci) {
        ((AbstractGolemEntity<?, ?>) (Object) this).getCapability(GolemEnergyProvider.CAPABILITY).ifPresent(cap -> {
            GolemEnergyStorage e = (GolemEnergyStorage) cap;
            e.setCapacity(tag.getInt("GolemMaxEnergy"));
            e.setEnergy(tag.getInt("GolemEnergy"));
        });
    }
}
