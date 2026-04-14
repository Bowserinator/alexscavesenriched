package net.hellomouse.alexscavesenriched.utils

import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceKey
import net.minecraft.tags.TagKey
import net.minecraft.world.level.block.Block
import net.minecraftforge.registries.ForgeRegistries

object PacketUtils {
    @JvmStatic
    fun readBlock(buf: FriendlyByteBuf): Block {
        val id = buf.readResourceLocation()
        return ForgeRegistries.BLOCKS.getValue(id) ?: throw IllegalArgumentException("$id is not a valid block")
    }

    @JvmStatic
    fun writeBlock(buf: FriendlyByteBuf, block: Block) {
        buf.writeResourceLocation(
            ForgeRegistries.BLOCKS.getKey(block) ?: throw IllegalArgumentException("Registry returned null for $block?")
        )
    }

    @JvmStatic
    fun <T> readTagKey(buf: FriendlyByteBuf, registry: ResourceKey<out Registry<T>>): TagKey<T> {
        return TagKey.create(registry, buf.readResourceLocation())
    }

    @JvmStatic
    fun <T> writeTagKey(buf: FriendlyByteBuf, tagKey: TagKey<T>) {
        buf.writeResourceLocation(tagKey.location)
    }

    @JvmStatic
    fun readBlockTag(buf: FriendlyByteBuf): TagKey<Block> {
        return readTagKey(buf, BuiltInRegistries.BLOCK.key())
    }
}