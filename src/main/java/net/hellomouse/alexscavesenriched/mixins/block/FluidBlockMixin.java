package net.hellomouse.alexscavesenriched.mixins.block;

import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.material.FlowingFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LiquidBlock.class)
public abstract class FluidBlockMixin {
    @Mutable
    @Shadow
    private FlowingFluid fluid;

    @Unique
    public void alexscavesenriched$setFluid(FlowingFluid fluid) {
        this.fluid = fluid;
    }
}
