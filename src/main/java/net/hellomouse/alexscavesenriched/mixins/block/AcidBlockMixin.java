package net.hellomouse.alexscavesenriched.mixins.block;

import com.github.alexmodguy.alexscaves.server.block.AcidBlock;
import net.minecraft.block.AbstractBlock;
import net.minecraft.fluid.FlowableFluid;
import net.minecraftforge.registries.RegistryObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AcidBlock.class)
public abstract class AcidBlockMixin extends FluidBlockMixin {

    @Inject(
            method = "<init>",
            at = @At(
                    value = "TAIL"
            )
    )
    public void init(RegistryObject<FlowableFluid> flowingFluid, AbstractBlock.Settings properties, CallbackInfo ci) {
        this.alexscavesenriched$setFluid(flowingFluid.get());
    }

}
