package net.hellomouse.alexscavesenriched.recipe

import net.hellomouse.alexscavesenriched.ACERecipeRegistry
import net.hellomouse.alexscavesenriched.utils.BlockList
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.Block

class NuclearTransmutationRecipe(
    id: ResourceLocation, input: BlockList,
    output: Block, chance: Float
) : TransmutationRecipe(
    ACERecipeRegistry.NUCLEAR_TRANSMUTATION_TYPE.get(),
    ACERecipeRegistry.NUCLEAR_TRANSMUTATION.get(),
    id,
    input,
    output,
    chance
)