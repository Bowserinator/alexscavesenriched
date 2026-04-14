package net.hellomouse.alexscavesenriched.compat.emi;

import com.github.alexmodguy.alexscaves.server.block.ACBlockRegistry;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.render.EmiTexture;
import dev.emi.emi.api.stack.EmiStack;
import net.hellomouse.alexscavesenriched.ACEBlockRegistry;
import net.hellomouse.alexscavesenriched.ACEItemRegistry;
import net.hellomouse.alexscavesenriched.ACERecipeRegistry;
import net.hellomouse.alexscavesenriched.AlexsCavesEnriched;
import net.hellomouse.alexscavesenriched.recipe.CentrifugeRecipe;
import net.hellomouse.alexscavesenriched.recipe.NeutronKillRecipe;
import net.hellomouse.alexscavesenriched.recipe.NuclearFurnanceRecipeAdditional;
import net.hellomouse.alexscavesenriched.recipe.NuclearTransmutationRecipe;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@EmiEntrypoint
public class ACEEMIPlugin implements EmiPlugin {
    public static final ResourceLocation MY_SPRITE_SHEET = ResourceLocation.fromNamespaceAndPath(AlexsCavesEnriched.MODID, "textures/gui/emi_simplified_textures.png");
    public static final EmiStack NUCLEAR_FURNACE = EmiStack.of(ACBlockRegistry.NUCLEAR_FURNACE_COMPONENT.get().asItem());
    public static final EmiStack NUCLEAR_BOMB = EmiStack.of(ACBlockRegistry.NUCLEAR_BOMB.get().asItem());
    public static final EmiStack NEUTRON_BOMB = EmiStack.of(ACEBlockRegistry.NEUTRON_BOMB.get().asItem());
    public static final EmiStack GAMMA_FLASHLIGHT = EmiStack.of(ACEItemRegistry.GAMMA_FLASHLIGHT.get().asItem());
    public static final EmiStack CENTRIFUGE = EmiStack.of(ACEBlockRegistry.CENTRIFUGE_BASE.get().asItem());
    public static final EmiStack CENTRIFUGE2 = EmiStack.of(ACEBlockRegistry.CENTRIFUGE_TOP.get().asItem());

    public static final EmiRecipeCategory ACE_NUCLEAR_FURNACE_CATEGORY
            = new EmiRecipeCategory(ResourceLocation.fromNamespaceAndPath(AlexsCavesEnriched.MODID, "nuclear_furnace"),
            NUCLEAR_FURNACE, new EmiTexture(MY_SPRITE_SHEET, 0, 0, 16, 16));
    public static final EmiRecipeCategory ACE_NUCLEAR_TRANSMUTATION_CATEGORY
            = new EmiRecipeCategory(ResourceLocation.fromNamespaceAndPath(AlexsCavesEnriched.MODID, "nuclear_transmutation"),
            NUCLEAR_BOMB, new EmiTexture(MY_SPRITE_SHEET, 0, 0, 16, 16));
    public static final EmiRecipeCategory ACE_NEUTRON_KILL_CATEGORY
            = new EmiRecipeCategory(ResourceLocation.fromNamespaceAndPath(AlexsCavesEnriched.MODID, "neutron_kill"),
            NEUTRON_BOMB, new EmiTexture(MY_SPRITE_SHEET, 0, 0, 16, 16));
    public static final EmiRecipeCategory ACE_CENTRIFUGE_CATEGORY
            = new EmiRecipeCategory(ResourceLocation.fromNamespaceAndPath(AlexsCavesEnriched.MODID, "centrifuge"),
            CENTRIFUGE, new EmiTexture(MY_SPRITE_SHEET, 0, 0, 16, 16));

    @Override
    public void register(EmiRegistry registry) {
        registry.addCategory(ACE_NUCLEAR_FURNACE_CATEGORY);
        registry.addWorkstation(ACE_NUCLEAR_FURNACE_CATEGORY, NUCLEAR_FURNACE);
        registry.addCategory(ACE_NUCLEAR_TRANSMUTATION_CATEGORY);
        registry.addWorkstation(ACE_NUCLEAR_TRANSMUTATION_CATEGORY, NUCLEAR_BOMB);
        registry.addCategory(ACE_NEUTRON_KILL_CATEGORY);
        registry.addWorkstation(ACE_NEUTRON_KILL_CATEGORY, GAMMA_FLASHLIGHT);
        registry.addWorkstation(ACE_NEUTRON_KILL_CATEGORY, NEUTRON_BOMB);
        registry.addCategory(ACE_CENTRIFUGE_CATEGORY);
        registry.addWorkstation(ACE_CENTRIFUGE_CATEGORY, CENTRIFUGE);
        registry.addWorkstation(ACE_CENTRIFUGE_CATEGORY, CENTRIFUGE2);

        {
            RecipeManager manager = registry.getRecipeManager();
            for (NuclearFurnanceRecipeAdditional recipe : manager.getAllRecipesFor(ACERecipeRegistry.NUCLEAR_FURNACE_TYPE.get()))
                registry.addRecipe(new EMINuclearFurnaceRecipe(recipe));
        }
        {
            HashSet<Component> alreadyVisited = new HashSet<>();
            RecipeManager manager = registry.getRecipeManager();
            for (NuclearTransmutationRecipe recipe : manager.getAllRecipesFor(ACERecipeRegistry.NUCLEAR_TRANSMUTATION_TYPE.get())) {
                if (recipe.getIsTag()) {
                    var tag = TagKey.create(Registries.BLOCK, recipe.getInputLocation());
                    Set<ItemStack> blocksInTag = ForgeRegistries.BLOCKS.getEntries().stream()
                            .filter((entry) -> entry.getValue().defaultBlockState().is(tag))
                            .map((entry) -> entry.getValue().asItem().getDefaultInstance())
                            .collect(Collectors.toSet());
                    for (var blockItem : blocksInTag) {
                        if (alreadyVisited.contains(blockItem.getItem().getDescription()))
                            continue;
                        registry.addRecipe(new EMINuclearTransmutationRecipe(recipe, blockItem));
                        alreadyVisited.add(blockItem.getItem().getDescription());
                    }
                } else {
                    if (!alreadyVisited.contains(recipe.getInput().getItem().getDescription()))
                        registry.addRecipe(new EMINuclearTransmutationRecipe(recipe));
                    alreadyVisited.add(recipe.getInput().getItem().getDescription());
                }
            }
        }
        {
            RecipeManager manager = registry.getRecipeManager();
            for (NeutronKillRecipe recipe : manager.getAllRecipesFor(ACERecipeRegistry.NEUTRON_KILL_TYPE.get())) {
                registry.addRecipe(new EMINeutronKillRecipe(recipe));
            }
            {
                for (CentrifugeRecipe recipe : manager.getAllRecipesFor(ACERecipeRegistry.CENTRIFUGE_TYPE.get()))
                    registry.addRecipe(new EMICentrifugeRecipe(recipe));
            }
        }
    }
}
