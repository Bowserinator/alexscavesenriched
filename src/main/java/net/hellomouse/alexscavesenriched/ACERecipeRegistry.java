package net.hellomouse.alexscavesenriched;

import net.hellomouse.alexscavesenriched.recipe.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SimpleCookingSerializer;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public class ACERecipeRegistry {
    public static final DeferredRegister<RecipeType<?>> TYPE_DEF_REG = DeferredRegister.create(Registries.RECIPE_TYPE, AlexsCavesEnriched.MODID);
    public static final DeferredRegister<RecipeSerializer<?>> DEF_REG = DeferredRegister.create(Registries.RECIPE_SERIALIZER, AlexsCavesEnriched.MODID);

    public static final RegistryObject<RecipeType<NuclearFurnanceRecipeAdditional>> NUCLEAR_FURNACE_TYPE = TYPE_DEF_REG.register("nuclear_furnace_additional", () -> new RecipeType<>() {
    });
    public static final RegistryObject<RecipeSerializer<?>> NUCLEAR_FURNACE = DEF_REG.register("nuclear_furnace_additional", () -> new SimpleCookingSerializer<>(NuclearFurnanceRecipeAdditional::new, 100));

    public static final RegistryObject<RecipeType<NuclearTransmutationRecipe>> NUCLEAR_TRANSMUTATION_TYPE = TYPE_DEF_REG.register("nuclear_transmutation", () -> new RecipeType<>() {
    });
    public static final RegistryObject<RecipeSerializer<NuclearTransmutationRecipe>> NUCLEAR_TRANSMUTATION =
            DEF_REG.register("nuclear_transmutation", () -> new TransmutationRecipe.TransmutationRecipeSerializer<>(NuclearTransmutationRecipe::new));

    public static final RegistryObject<RecipeType<NeutronKillRecipe>> NEUTRON_KILL_TYPE = TYPE_DEF_REG.register("neutron_kill", () -> new RecipeType<>() {
    });
    public static final RegistryObject<RecipeSerializer<NeutronKillRecipe>> NEUTRON_KILL =
            DEF_REG.register("neutron_kill", () -> new TransmutationRecipe.TransmutationRecipeSerializer<>(NeutronKillRecipe::new));

    public static final RegistryObject<RecipeType<CentrifugeRecipe>> CENTRIFUGE_TYPE = TYPE_DEF_REG.register("centrifuge", () -> new RecipeType<>() {
    });
    public static final RegistryObject<RecipeSerializer<CentrifugeRecipe>> CENTRIFUGE =
            DEF_REG.register("centrifuge", CentrifugeRecipe.CentrifugeRecipeSerializer::new);
}
