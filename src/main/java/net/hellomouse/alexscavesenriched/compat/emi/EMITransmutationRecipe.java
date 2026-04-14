package net.hellomouse.alexscavesenriched.compat.emi;

import com.google.gson.JsonSyntaxException;
import dev.emi.emi.EmiPort;
import dev.emi.emi.api.recipe.BasicEmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.render.EmiTexture;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;
import net.hellomouse.alexscavesenriched.AlexsCavesEnriched;
import net.hellomouse.alexscavesenriched.recipe.TransmutationRecipe;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.List;

public class EMITransmutationRecipe extends BasicEmiRecipe {
    protected TransmutationRecipe recipe;

    public EMITransmutationRecipe(EmiRecipeCategory category, TransmutationRecipe recipe) {
        super(category, recipe.getId(), 70, 18);
        this.recipe = recipe;
        try {
            this.inputs = List.of(
                    EmiIngredient.of(recipe.getInput().toItemStacks().stream()
                            .map(itemStack -> EmiIngredient.of(Ingredient.of(itemStack)))
                            .distinct()
                            .filter(itemStack -> !itemStack.isEmpty())
                            .toList())
            );
        } catch (JsonSyntaxException exception) {
            AlexsCavesEnriched.LOGGER.error("Invalid block tag in recipe {}", recipe.getId());
            exception.printStackTrace();
        }
        this.outputs.add(EmiStack.of(recipe.getOutput()));
    }

    @Override
    public int getDisplayWidth() {
        return 82;
    }

    @Override
    public int getDisplayHeight() {
        return 38;
    }

    @Override
    public void addWidgets(WidgetHolder widgets) {
        widgets.addTexture(EmiTexture.FULL_ARROW, 24, 5);
        widgets.addText(EmiPort.ordered(EmiPort.translatable("emi.transmutation_chance", recipe.getChance() * 100)),
                0, 28, -1, true);
        widgets.addSlot(inputs.get(0), 0, 4);
        widgets.addSlot(outputs.get(0), 56, 0).large(true).recipeContext(this);
    }
}