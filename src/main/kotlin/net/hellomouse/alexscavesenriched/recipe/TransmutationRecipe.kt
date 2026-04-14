package net.hellomouse.alexscavesenriched.recipe

import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.mojang.serialization.Codec
import com.mojang.serialization.JsonOps
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.hellomouse.alexscavesenriched.utils.BlockList
import net.hellomouse.alexscavesenriched.utils.BlockList.Companion.fromBuf
import net.hellomouse.alexscavesenriched.utils.PacketUtils.readBlock
import net.hellomouse.alexscavesenriched.utils.PacketUtils.writeBlock
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import java.util.function.Consumer

abstract class TransmutationRecipe(
    val recipeType: RecipeType<*>,
    val recipeSerializer: RecipeSerializer<*>,
    val resourceLocation: ResourceLocation,
    val input: BlockList,
    val output: Block,
    val chance: Float
) :
    Recipe<SimpleContainer> {
    fun matches(state: BlockState): Boolean {
        return input.test(state)
    }

    override fun getId(): ResourceLocation {
        return resourceLocation
    }

    // These methods aren't used here but must be implemented
    override fun matches(inv: SimpleContainer, level: Level): Boolean {
        return false
    }

    override fun assemble(inventory: SimpleContainer, registryManager: RegistryAccess): ItemStack {
        return ItemStack.EMPTY
    }

    override fun canCraftInDimensions(width: Int, height: Int): Boolean {
        return true
    }

    override fun getSerializer(): RecipeSerializer<*> {
        return recipeSerializer
    }

    override fun getType(): RecipeType<*> {
        return recipeType
    }

    override fun getResultItem(registryAccess: RegistryAccess): ItemStack {
        return output.asItem().defaultInstance
    }

    fun interface TransmutationRecipeMaker<T> {
        operator fun invoke(
            id: ResourceLocation,
            input: BlockList,
            output: Block,
            chance: Float
        ): T
    }

    class TransmutationRecipeSerializer<T : TransmutationRecipe>(val factory: TransmutationRecipeMaker<T>) :
        RecipeSerializer<T> {
        class Incomplete(
            val input: BlockList,
            val output: Block,
            val chance: Float
        ) {
            companion object {
                val CODEC: MapCodec<Incomplete> = RecordCodecBuilder.mapCodec { instance ->
                    instance.group(
                        BlockList.CODEC.fieldOf("input").forGetter(Incomplete::input),
                        BuiltInRegistries.BLOCK.byNameCodec().fieldOf("output").forGetter(Incomplete::output),
                        Codec.FLOAT.optionalFieldOf("chance", 1.0f).forGetter(Incomplete::chance)
                    ).apply(instance, ::Incomplete)
                }
            }
        }

        override fun fromJson(
            recipeId: ResourceLocation,
            serializedRecipe: JsonObject
        ): T {
            val incomplete = Incomplete.CODEC.codec().parse(JsonOps.INSTANCE, serializedRecipe)
                .getOrThrow(false, Consumer { msg: String? ->
                    throw JsonSyntaxException(msg)
                })
            return factory(recipeId, incomplete.input, incomplete.output, incomplete.chance)
        }

        override fun fromNetwork(id: ResourceLocation, buf: FriendlyByteBuf): T {
            val input = fromBuf(buf)
            val output = readBlock(buf)
            val chance = buf.readFloat()
            return factory(id, input, output, chance)
        }

        override fun toNetwork(buf: FriendlyByteBuf, recipe: T) {
            recipe.input.toBuf(buf)
            writeBlock(buf, recipe.output)
            buf.writeFloat(recipe.chance)
        }
    }
}
