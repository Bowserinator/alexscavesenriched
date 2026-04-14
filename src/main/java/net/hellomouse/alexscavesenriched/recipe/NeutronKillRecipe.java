package net.hellomouse.alexscavesenriched.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.hellomouse.alexscavesenriched.ACERecipeRegistry;
import net.hellomouse.alexscavesenriched.utils.BlockList;
import net.hellomouse.alexscavesenriched.utils.PacketUtils;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

// For in-world neutron bomb detonation transmutation
public class NeutronKillRecipe implements Recipe<SimpleContainer> {
    private final ResourceLocation id;
    private final BlockList input;
    private final Block outputBlock;
    private final float chance;

    public NeutronKillRecipe(ResourceLocation id, BlockList input, Block output, float chance) {
        this.id = id;
        this.input = input;
        this.outputBlock = output;
        this.chance = chance;
    }

    public boolean matches(@NotNull BlockState state) {
        return input.test(state);
    }

    public Block getOutput() {
        return outputBlock;
    }

    public float getChance() {
        return chance;
    }

    public BlockList getInput() {
        return input;
    }

    @Override
    public @NotNull ResourceLocation getId() {
        return id;
    }

    // These methods aren't used here but must be implemented
    @Override
    public boolean matches(SimpleContainer inv, Level level) {
        return false;
    }

    @Override
    public ItemStack assemble(SimpleContainer inventory, RegistryAccess registryManager) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return true;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess p_267052_) {
        return outputBlock.asItem().getDefaultInstance();
    }

    @Override
    public @NotNull RecipeSerializer<?> getSerializer() {
        return ACERecipeRegistry.NEUTRON_KILL.get();
    }

    @Override
    public @NotNull RecipeType<?> getType() {
        return ACERecipeRegistry.NEUTRON_KILL_TYPE.get();
    }

    public record Incomplete(BlockList input, Block output,
                             float chance) implements CodecRecipeSerializer.Partial<NeutronKillRecipe> {
        public static MapCodec<Incomplete> CODEC = RecordCodecBuilder.mapCodec(instance ->
                instance.group(
                        BlockList.CODEC.fieldOf("input").forGetter(Incomplete::input),
                        BuiltInRegistries.BLOCK.byNameCodec().fieldOf("output").forGetter(Incomplete::output),
                        Codec.FLOAT.optionalFieldOf("chance", 1.0f).forGetter(Incomplete::chance)
                ).apply(instance, Incomplete::new)
        );

        @Override
        public NeutronKillRecipe withId(ResourceLocation id) {
            return new NeutronKillRecipe(id, input, output, chance);
        }
    }

    public static class NeutronKillRecipeSerializer extends CodecRecipeSerializer<NeutronKillRecipe, Incomplete> {
        @Override
        public NeutronKillRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
            var input = BlockList.fromBuf(buf);
            var output = PacketUtils.readBlock(buf);
            var chance = buf.readFloat();
            return new NeutronKillRecipe(id, input, output, chance);
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, NeutronKillRecipe recipe) {
            recipe.input.toBuf(buf);
            PacketUtils.writeBlock(buf, recipe.outputBlock);
            buf.writeFloat(recipe.chance);
        }

        @Override
        public Codec<Incomplete> getCodec() {
            return Incomplete.CODEC.codec();
        }
    }
}
