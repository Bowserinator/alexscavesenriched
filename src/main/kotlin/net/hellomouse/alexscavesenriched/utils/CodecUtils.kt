package net.hellomouse.alexscavesenriched.utils

import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonSyntaxException
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.util.ExtraCodecs
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.Ingredient
import java.util.*
import kotlin.jvm.optionals.getOrNull

object CodecUtils {
    @JvmField
    val INGREDIENT_CODEC: Codec<Ingredient> =
        ExtraCodecs.JSON.comapFlatMap(
            tryDataResult(Ingredient::fromJson),
            Ingredient::toJson
        )

    // net.minecraft.item.ItemStack.CODEC is _weird_, count is required, oh wait, no, its `Count` not `count` ...
    @JvmField
    val ITEM_STACK_CODEC: MapCodec<ItemStack> = RecordCodecBuilder.mapCodec { instance ->
        instance.group(
            BuiltInRegistries.ITEM.byNameCodec().fieldOf("item")
                .forGetter(ItemStack::getItem),
            Codec.INT.optionalFieldOf("count", 1).forGetter(ItemStack::getCount),
            CompoundTag.CODEC.optionalFieldOf("tag")
                .forGetter { itemStack -> Optional.ofNullable(itemStack.tag) }
        ).apply(instance) { item, int, nbt -> ItemStack(item, int, nbt.getOrNull()) }
    }

    private fun <T> tryDataResult(converter: (JsonElement) -> T): (JsonElement) -> DataResult<T> {
        return { jsonElement ->
            try {
                DataResult.success(converter(jsonElement))
            } catch (e: JsonSyntaxException) {
                DataResult.error { e.message }
            } catch (e: JsonParseException) {
                DataResult.error { e.message }
            }
        }
    }
}