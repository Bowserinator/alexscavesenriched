package net.hellomouse.alexscavesenriched.recipe;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import org.jetbrains.annotations.NotNull;

public abstract class CodecRecipeSerializer<T extends Recipe<?>, P extends CodecRecipeSerializer.Partial<T>> implements RecipeSerializer<T> {

    public abstract Codec<P> getCodec();

    @Override
    public @NotNull T fromJson(@NotNull ResourceLocation recipeId, @NotNull JsonObject serializedRecipe) {
        return getCodec().parse(JsonOps.INSTANCE, serializedRecipe).getOrThrow(false, msg -> {
            throw new JsonSyntaxException(msg);
        }).withId(recipeId);
    }

    public interface Partial<T extends Recipe<?>> {
        T withId(ResourceLocation id);
    }
}
