package net.hellomouse.alexscavesenriched.recipe

import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.mojang.serialization.Codec
import com.mojang.serialization.JsonOps
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeSerializer
import java.util.function.Consumer

abstract class CodecRecipeSerializer<T : Recipe<*>, P : CodecRecipeSerializer.Partial<T>>(val codec: Codec<P>) :
    RecipeSerializer<T> {
    interface Partial<T : Recipe<*>> {
        fun withId(id: ResourceLocation): T
    }

    override fun fromJson(recipeId: ResourceLocation, serializedRecipe: JsonObject): T {
        return this.codec.parse(JsonOps.INSTANCE, serializedRecipe)
            .getOrThrow(false, Consumer { msg: String? ->
                throw JsonSyntaxException(msg)
            }).withId(recipeId)
    }
}
