package net.hellomouse.alexscavesenriched.utils

import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.tags.TagKey
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import java.util.function.Predicate

data class BlockList(val list: Array<Either<Block, TagKey<Block>>>) : Predicate<BlockState> {
    override fun test(blockState: BlockState): Boolean {
        for (blockOrTag in list) {
            if (blockOrTag.map({ block -> blockState.`is`(block) }, { tagKey -> blockState.`is`(tagKey) })) {
                return true
            }
        }
        return false
    }

    companion object {
        private val BLOCK_ENTRY_CODEC: Codec<Either<Block, TagKey<Block>>> = Codec.either(
            BuiltInRegistries.BLOCK.byNameCodec(),
            TagKey.hashedCodec(BuiltInRegistries.BLOCK.key())
        )

        @JvmField
        val CODEC: Codec<BlockList> =
            Codec.either(BLOCK_ENTRY_CODEC, BLOCK_ENTRY_CODEC.listOf())
                .xmap(
                    { entry -> entry.map({ block -> listOf(block) }, { list -> list }) },
                    { entries -> Either.right(entries) }
                ).xmap({ list -> BlockList(list.toTypedArray()) }, { blockList -> blockList.list.toList() })

        @JvmStatic
        fun fromBuf(buf: FriendlyByteBuf): BlockList {
            val length = buf.readVarInt()
            val list = arrayOfNulls<Either<Block, TagKey<Block>>?>(length)
            for (i in 0..<length) {
                val isTag = buf.readBoolean()
                list[i] = if (isTag) {
                    Either.right(PacketUtils.readBlockTag(buf))
                } else {
                    Either.left(PacketUtils.readBlock(buf))
                }
            }
            @Suppress("unchecked_cast")
            return BlockList(list as Array<Either<Block, TagKey<Block>>>)
        }
    }

    fun toBuf(buf: FriendlyByteBuf) {
        buf.writeVarInt(list.size)
        for (blockOrTag in list) {
            blockOrTag.map({ block ->
                buf.writeBoolean(false)
                PacketUtils.writeBlock(buf, block)
            }, { tagKey ->
                buf.writeBoolean(true)
                PacketUtils.writeTagKey(buf, tagKey)
            })
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BlockList

        return list.contentEquals(other.list)
    }

    override fun hashCode(): Int {
        return list.contentHashCode()
    }

    fun toItemStacks(): List<ItemStack> {
        return list.flatMap { either ->
            either.map<Iterable<ItemStack>>(
                { block -> listOf(block.asItem().defaultInstance) },
                { tagKey ->
                    BuiltInRegistries.BLOCK.getTagOrEmpty(tagKey)
                        .map { holder -> holder.get().asItem().defaultInstance }
                })
        }
    }
}