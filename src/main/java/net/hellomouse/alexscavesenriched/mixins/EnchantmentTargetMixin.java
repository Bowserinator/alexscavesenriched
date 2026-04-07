package net.hellomouse.alexscavesenriched.mixins;

import com.github.alexmodguy.alexscaves.server.item.ACItemRegistry;
import net.hellomouse.alexscavesenriched.ACEItemRegistry;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

@Mixin(net.minecraft.world.item.enchantment.EnchantmentCategory.class)
public class EnchantmentTargetMixin {
    @Shadow private Predicate<Item> delegate;

    @Inject(at = @At(value = "HEAD"), method = {"canEnchant"}, cancellable = true)
    public void isAcceptableItem(Item item, CallbackInfoReturnable<Boolean> cir) {
        // Allow raygun mk2 to get raygun enchants
        if (ACItemRegistry.RAYGUN.isPresent()) { // Jank to work around a weird crash that I cant reproduce on my machine
            if (this.delegate.test(ACItemRegistry.RAYGUN.get()) && item.equals(ACEItemRegistry.RAYGUN.get())) {
                cir.setReturnValue(true);
            }
        }
    }
}
