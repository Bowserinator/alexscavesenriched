package net.hellomouse.alexscavesenriched.recipe

import net.hellomouse.alexscavesenriched.ACERecipeRegistry
import net.hellomouse.alexscavesenriched.utils.BlockList
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.Block

class NeutronKillRecipe(
    id: ResourceLocation, input: BlockList,
    output: Block, chance: Float
) : TransmutationRecipe(
    ACERecipeRegistry.NEUTRON_KILL_TYPE.get(),
    ACERecipeRegistry.NEUTRON_KILL.get(),
    id,
    input,
    output,
    chance
)