package net.hellomouse.alexscavesenriched.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.Optional;
import java.util.function.Function;

public class CodecUtils {
    // net.minecraft.item.ItemStack.CODEC is _weird_, count is required, oh wait, no, its `Count` not `count` ...
    public static final Codec<ItemStack> ITEMSTACK_CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                            BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(ItemStack::getItem),
                            Codec.INT.optionalFieldOf("count", 1).forGetter(ItemStack::getCount),
                            CompoundTag.CODEC.optionalFieldOf("tag").forGetter(arg -> Optional.ofNullable(arg.getTag()))
                    )
                    .apply(instance, (item, integer, compoundTag) -> new ItemStack(item, integer, compoundTag.orElse(null)))
    );
    public static Codec<Ingredient> INGRIDIENT_CODEC = ExtraCodecs.JSON.comapFlatMap(tryDataResult(Ingredient::fromJson), Ingredient::toJson);

    private static <T> Function<JsonElement, DataResult<T>> tryDataResult(Function<JsonElement, T> converter) {
        return (elm) -> {
            DataResult<T> result;
            try {
                result = DataResult.success(converter.apply(elm));
            } catch (JsonParseException e) {
                result = DataResult.error(e::getMessage);
            }
            return result;
        };
    }
}
