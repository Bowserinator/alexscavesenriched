package net.hellomouse.alexscavesenriched.mixins.block;

import net.minecraft.block.FluidBlock;
import net.minecraft.fluid.FlowableFluid;
import org.spongepowered.asm.mixin.*;

@Mixin(FluidBlock.class)
public abstract class FluidBlockMixin {
    @Mutable
    @Final
    @Shadow
    private FlowableFluid fluid;

    @Unique
    public void alexscavesenriched$setFluid(FlowableFluid fluid) {
        this.fluid = fluid;
    }
}
