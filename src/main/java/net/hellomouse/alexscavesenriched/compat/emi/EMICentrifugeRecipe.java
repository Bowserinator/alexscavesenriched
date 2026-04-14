package net.hellomouse.alexscavesenriched.compat.emi;

import dev.emi.emi.EmiPort;
import dev.emi.emi.api.recipe.BasicEmiRecipe;
import dev.emi.emi.api.render.EmiTexture;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;
import net.hellomouse.alexscavesenriched.recipe.CentrifugeRecipe;
import net.minecraft.world.item.ItemStack;

public class EMICentrifugeRecipe extends BasicEmiRecipe {
    protected CentrifugeRecipe recipe;

    private final int X_START = 56;
    private final int Y_START = 4;
    private final int BOX_SIZE = 18;

    public EMICentrifugeRecipe(CentrifugeRecipe recipe) {
        super(ACEEMIPlugin.ACE_CENTRIFUGE_CATEGORY, recipe.getId(), 70, 18);
        this.recipe = recipe;
        this.inputs.add(EmiIngredient.of(recipe.getIngredient()));

        for (var entry : recipe.getOutputs())
            this.outputs.add(EmiStack.of(entry));
    }

    public EMICentrifugeRecipe(CentrifugeRecipe recipe, ItemStack inputsOverride) {
        super(ACEEMIPlugin.ACE_CENTRIFUGE_CATEGORY, recipe.getId().withPrefix("/" + inputsOverride.getDescriptionId()), 70, 18);
        this.recipe = recipe;
        this.inputs.add(EmiStack.of(inputsOverride));
        for (var output : recipe.getOutputs())
            this.outputs.add(EmiStack.of(output));
    }

    @Override
    public int getDisplayWidth() {
        return 114;
    }

    @Override
    public int getDisplayHeight() {
        return Math.max(38, BOX_SIZE * (this.outputs.size() / 3 + 1) + Y_START * 2);
    }

    @Override
    public void addWidgets(WidgetHolder widgets) {
        widgets.addTexture(EmiTexture.FULL_ARROW, 24, 5);
        widgets.addText(EmiPort.ordered(EmiPort.translatable("emi.centrifuge_chance", Math.round(1 / recipe.getChance())) ),
                0, 28, -1, true);
        widgets.addSlot(inputs.get(0), 0, 4);

        final var EMPTY_STACK = EmiStack.of(ItemStack.EMPTY);
        for (int i = 0; i < outputs.size(); i++)
            widgets.addSlot(outputs.get(i), X_START + BOX_SIZE * (i % 3), Y_START + BOX_SIZE * (i / 3)).recipeContext(this);
        if (outputs.size() < 9 && outputs.size() % 3 != 0) {
            for (int i = outputs.size() % 3; i % 3 != 0; i++)
                widgets.addSlot(EMPTY_STACK, X_START + BOX_SIZE * (i % 3), Y_START + BOX_SIZE * (outputs.size() / 3)).recipeContext(this);
        }
    }
}