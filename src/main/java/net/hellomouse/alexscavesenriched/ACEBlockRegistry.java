package net.hellomouse.alexscavesenriched;

import com.github.alexmodguy.alexscaves.server.item.*;
import com.github.alexthe666.citadel.item.BlockItemWithSupplier;
import net.hellomouse.alexscavesenriched.block.*;
import net.hellomouse.alexscavesenriched.block.centrifuge.CentrifugeBaseBlock;
import net.hellomouse.alexscavesenriched.block.centrifuge.CentrifugeMultiBlockBaseBlock;
import net.hellomouse.alexscavesenriched.block.centrifuge.CentrifugeMultiBlockProxyBlock;
import net.hellomouse.alexscavesenriched.block.centrifuge.CentrifugeTopBlock;
import net.hellomouse.alexscavesenriched.block.fumo.XenoFumoBlock;
import net.hellomouse.alexscavesenriched.block.fumo.XiaoyuFumoBlock;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.util.Rarity;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Supplier;

public class ACEBlockRegistry {
    public static final DeferredRegister<Block> DEF_REG = DeferredRegister.create(ForgeRegistries.BLOCKS, AlexsCavesEnriched.MODID);
    public static final RegistryObject<Block> ENRICHED_URANIUM = registerBlockAndItem("enriched_uranium_block", EnrichedUraniumBlock::new, 9);
    public static final RegistryObject<Block> ENRICHED_URANIUM_ROD = registerBlockAndItem("enriched_uranium_rod", EnrichedUraniumRodBlock::new, 9);
    public static final RegistryObject<Block> NUKE_GLOWING_AIR = DEF_REG.register("nuke_glowing_air", NukeGlowingAir::new);
    public static final RegistryObject<Block> BLACK_HOLE_BOMB = registerBlockAndItem("black_hole_bomb", BlackHoleBombBlock::new, 7);
    public static final RegistryObject<Block> NEUTRON_BOMB = registerBlockAndItem("neutron_bomb", NeutronBombBlock::new, 7);
    public static final RegistryObject<Block> MINI_NUKE = registerBlockAndItem("mini_nuke", MiniNukeBlock::new, 7);

    public static final RegistryObject<Block> NUKA_COLA = DEF_REG.register("nuka_cola", NukaColaBlock::new);
    public static final RegistryObject<Block> NUKA_COLA_QUANTUM = DEF_REG.register("nuka_cola_quantum", NukaColaQuantumBlock::new);
    public static final RegistryObject<Block> NUKA_COLA_EMPTY = DEF_REG.register("nuka_cola_empty", NukaColaEmptyBlock::new);

    public static final RegistryObject<Block> XIAOYU_FUMO = registerBlockAndItem("xiaoyu_fumo", XiaoyuFumoBlock::new, 10);
    public static final RegistryObject<Block> XENO_FUMO = registerBlockAndItem("xeno_fumo", XenoFumoBlock::new, 10);

    public static final RegistryObject<Block> CENTRIFUGE = registerBlockAndItem("centrifuge", CentrifugeMultiBlockBaseBlock::new, 0);
    public static final RegistryObject<Block> CENTRIFUGE_PROXY = registerBlockAndItem("centrifuge_proxy", CentrifugeMultiBlockProxyBlock::new, 0);
    public static final RegistryObject<Block> CENTRIFUGE_BASE = registerBlockAndItem("centrifuge_base", CentrifugeBaseBlock::new, 0);
    public static final RegistryObject<Block> CENTRIFUGE_TOP = registerBlockAndItem("centrifuge_top", CentrifugeTopBlock::new, 0);

    public static final RegistryObject<Block> SALTED_URANIUM = registerBlockAndItem("salted_uranium_block", SaltedUraniumBlock::new, 3);

    private static RegistryObject<Block> registerBlockAndItem(String name, Supplier<Block> block) {
        return registerBlockAndItem(name, block, 0);
    }

    private static RegistryObject<Block> registerBlockAndItem(String name, Supplier<Block> block, int itemType) {
        RegistryObject<Block> blockObj = DEF_REG.register(name, block);
        ACEItemRegistry.DEF_REG.register(name, getBlockSupplier(itemType, blockObj));
        return blockObj;
    }

    private static Supplier<? extends BlockItemWithSupplier> getBlockSupplier(int itemType, RegistryObject<Block> blockObj) {
        return switch (itemType) {
            case 1 -> () -> new BlockItemWithSupplierLore(blockObj, new Item.Settings());
            case 2 -> () -> new BlockItemWithScaffolding(blockObj, new Item.Settings());
            case 4 -> () -> new RadioactiveBlockItem(blockObj, new Item.Settings(), 0.001F);
            case 5 -> () -> new RadioactiveOnDestroyedBlockItem(blockObj, new Item.Settings(), 0.01F);
            case 6 -> () -> new BlockItemWithSupplier(blockObj, (new Item.Settings()).rarity(Rarity.UNCOMMON));
            case 7 ->
                    () -> new BlockItemWithSupplier(blockObj, (new Item.Settings()).rarity(Rarity.UNCOMMON).fireproof());
            case 8 ->
                    () -> new BlockItemWithSupplier(blockObj, (new Item.Settings()).rarity(Rarity.UNCOMMON).fireproof().rarity(ACItemRegistry.RARITY_NUCLEAR));
            case 9 ->
                    () -> new RadioactiveBlockItem(blockObj, (new Item.Settings()).rarity(Rarity.UNCOMMON).fireproof().rarity(ACItemRegistry.RARITY_NUCLEAR), 0.001F);
            case 10 -> () -> new BlockItemWithSupplier(blockObj, (new Item.Settings()).rarity(Rarity.EPIC).fireproof());
            default -> () -> new BlockItemWithSupplier(blockObj, new Item.Settings());
        };
    }
}
