package net.hellomouse.alexscavesenriched.mixins.entity;

import com.github.alexmodguy.alexscaves.server.entity.ACEntityRegistry;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.hellomouse.alexscavesenriched.ACEEntityRegistry;
import net.hellomouse.alexscavesenriched.AlexsCavesEnriched;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(EntityType.class)
public class EntityTypeMixin<T extends Entity> {
    @WrapMethod(method = "create(Lnet/minecraft/world/level/Level;)Lnet/minecraft/world/entity/Entity;")
    T create(Level level, Operation<T> original) {
        if (this.equals(ACEntityRegistry.NUCLEAR_EXPLOSION.get()) && AlexsCavesEnriched.CONFIG.nuclear.useNewNuke) {
            var entity = ACEEntityRegistry.NUCLEAR_EXPLOSION2.get().create(level);
            return (T) entity;
        }
        return original.call(level);
    }
}
