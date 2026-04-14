package net.hellomouse.alexscavesenriched.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.hellomouse.alexscavesenriched.ACERecipeRegistry;
import net.hellomouse.alexscavesenriched.utils.CodecUtils;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

// For centrifuge
public class CentrifugeRecipe implements Recipe<SimpleContainer> {
    private final ResourceLocation id;
    private final Ingredient ingredient;
    private final List<ItemStack> outputItems;
    private final float chance;

    public CentrifugeRecipe(ResourceLocation id, Ingredient ingredient, List<ItemStack> output, float chance) {
        this.id = id;
        this.ingredient = ingredient;
        this.outputItems = output;
        this.chance = chance;
    }

    public Ingredient getIngredient() {
        return ingredient;
    }
    public boolean matches(@NotNull ItemStack stack) {
        return ingredient.test(stack);
    }

    public List<ItemStack> getOutputs() {
        return outputItems;
    }

    public float getChance() {
        return chance;
    }

    @Override
    public @NotNull ResourceLocation getId() {
        return id;
    }

    // These methods aren't used here but must be implemented
    @Override
    public boolean matches(@NotNull SimpleContainer inv, @NotNull Level level) {
        return false;
    }

    @Override
    public @NotNull ItemStack assemble(@NotNull SimpleContainer inventory, @NotNull RegistryAccess registryManager) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return true;
    }

    @Override
    public @NotNull ItemStack getResultItem(@NotNull RegistryAccess p_267052_) {
        return outputItems.get(0);
    }

    @Override
    public @NotNull RecipeSerializer<?> getSerializer() {
        return ACERecipeRegistry.CENTRIFUGE.get();
    }

    @Override
    public @NotNull RecipeType<?> getType() {
        return ACERecipeRegistry.CENTRIFUGE_TYPE.get();
    }

    public record Incomplete(Ingredient ingredient, List<ItemStack> output,
                             float chance) implements CodecRecipeSerializer.Partial<CentrifugeRecipe> {
        public static final MapCodec<Incomplete> CODEC = RecordCodecBuilder.mapCodec((instance) ->
                instance.group(
                        CodecUtils.INGRIDIENT_CODEC.fieldOf("input").forGetter(Incomplete::ingredient),
                        CodecUtils.ITEMSTACK_CODEC.listOf().fieldOf("outputs").forGetter(Incomplete::output),
                        Codec.FLOAT.optionalFieldOf("chance", 1.0f).forGetter(Incomplete::chance)
                ).apply(instance, Incomplete::new)
        );

        @Override
        public CentrifugeRecipe withId(ResourceLocation id) {
            return new CentrifugeRecipe(id, ingredient, output, chance);
        }
    }

    public static class CentrifugeRecipeSerializer extends CodecRecipeSerializer<CentrifugeRecipe, Incomplete> {
        @Override
        public CentrifugeRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
            var input = Ingredient.fromNetwork(buf);
            var outputLen = buf.readVarInt();
            var outputs = new ArrayList<ItemStack>(outputLen);
            for (int i = 0; i < outputLen; i++) {
                outputs.add(buf.readItem());
            }
            var chance = buf.readFloat();
            return new CentrifugeRecipe(id, input, outputs, chance);
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, CentrifugeRecipe recipe) {
            recipe.ingredient.toNetwork(buf);
            buf.writeVarInt(recipe.outputItems.size());
            for (ItemStack itemStack : recipe.outputItems) {
                buf.writeItem(itemStack);
            }
            buf.writeFloat(recipe.chance);
        }

        @Override
        public Codec<Incomplete> getCodec() {
            return Incomplete.CODEC.codec();
        }
    }
}
