package net.hellomouse.alexscavesenriched;

import com.mojang.logging.LogUtils;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.Toml4jConfigSerializer;
import net.hellomouse.alexscavesenriched.advancements.ACECriterionTriggers;
import net.hellomouse.alexscavesenriched.client.particle.texture.DemonCoreGlowTexture;
import net.hellomouse.alexscavesenriched.item.ACEDispenserItemBehavior;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(AlexsCavesEnriched.MODID)
public class AlexsCavesEnriched {
    public static final String MODID = "alexscavesenriched";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final TagKey<Block> NEUTRONREFLECTOR_TAG = BlockTags.create(ResourceLocation.fromNamespaceAndPath(MODID, "neutron_reflector"));
    public static final TagKey<Block> WEAK_PLANTS_TAG = BlockTags.create(ResourceLocation.fromNamespaceAndPath(MODID, "weak_plants"));
    public static final TagKey<Item> FLAMETHROWER_FUEL_TAG = ItemTags.create(ResourceLocation.fromNamespaceAndPath(MODID, "flamethrower_fuel"));

    public static ACEConfig CONFIG;
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TAB_REG = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final RegistryObject<CreativeModeTab> CREATIVE_TAB_ACE = CREATIVE_TAB_REG.register(MODID, () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup." + MODID + ".creative_tab"))
            .icon(() -> new ItemStack(ACEBlockRegistry.ENRICHED_URANIUM_ROD.get()))
            .withTabsBefore(CreativeModeTabs.SPAWN_EGGS)
            .displayItems((enabledFeatures, output) -> {
                output.accept(ACEBlockRegistry.CENTRIFUGE_BASE.get());
                output.accept(ACEBlockRegistry.CENTRIFUGE_TOP.get());
                output.accept(ACEBlockRegistry.SALTED_URANIUM.get());
                output.accept(ACEItemRegistry.ENRICHED_URANIUM_NUGGET.get());
                output.accept(ACEBlockRegistry.ENRICHED_URANIUM.get());
                output.accept(ACEBlockRegistry.ENRICHED_URANIUM_ROD.get());
                output.accept(ACEItemRegistry.ENRICHED_URANIUM.get());
                output.accept(ACEItemRegistry.ROCKET_LAUNCHER.get());
                output.accept(ACEItemRegistry.ROCKET_NORMAL.get());
                output.accept(ACEItemRegistry.ROCKET.get());
                output.accept(ACEItemRegistry.ROCKET_NUCLEAR.get());
                output.accept(ACEItemRegistry.ROCKET_NEUTRON.get());
                output.accept(ACEItemRegistry.ROCKET_MINI_NUKE.get());
                output.accept(ACEItemRegistry.URANIUM_ARROW.get());
                output.accept(ACEItemRegistry.URANIUM_CANDY.get());
                output.accept(ACEItemRegistry.RAYGUN.get());
                output.accept(ACEItemRegistry.RAYGUN_UPGRADE_TEMPLATE.get());
                output.accept(ACEItemRegistry.FLAMETHROWER.get());
                output.accept(ACEBlockRegistry.MINI_NUKE.get());
                output.accept(ACEBlockRegistry.NEUTRON_BOMB.get());
                output.accept(ACEBlockRegistry.BLACK_HOLE_BOMB.get());
                output.accept(ACEItemRegistry.DEADMAN_SWITCH.get());
                output.accept(ACEItemRegistry.GAMMA_FLASHLIGHT.get());
                output.accept(ACEItemRegistry.NUKA_COLA.get());
                output.accept(ACEItemRegistry.NUKA_COLA_QUANTUM.get());
                output.accept(ACEItemRegistry.NUKA_COLA_EMPTY.get());
                output.accept(ACEItemRegistry.RAILGUN.get());
                output.accept(ACEItemRegistry.RAILGUN_AMMO.get());
            })
            .build());

    public AlexsCavesEnriched(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();
        LOGGER.info("Loading AlexsCavesEnriched configuration...");

        var configHandler = AutoConfig.register(ACEConfig.class, Toml4jConfigSerializer::new);
        configHandler.registerSaveListener((configHolder, config) -> {
            DemonCoreGlowTexture.resetIfChanged();
            return InteractionResult.SUCCESS;
        });

        CONFIG = AutoConfig.getConfigHolder(ACEConfig.class).getConfig();

        modEventBus.addListener(this::commonSetup);
        ACERecipeRegistry.DEF_REG.register(modEventBus);
        ACERecipeRegistry.TYPE_DEF_REG.register(modEventBus);
        ACEEntityRegistry.DEF_REG.register(modEventBus);
        ACEBlockRegistry.DEF_REG.register(modEventBus);
        ACEItemRegistry.DEF_REG.register(modEventBus);
        ACEBlockEntityRegistry.DEF_REG.register(modEventBus);
        ACEEffectRegistry.DEF_REG.register(modEventBus);
        ACEParticleRegistry.DEF_REG.register(modEventBus);
        ACEMenuRegistry.DEF_REG.register(modEventBus);

        CREATIVE_TAB_REG.register(modEventBus);

        modEventBus.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(ACEDispenserItemBehavior::bootStrap);
        event.enqueueWork(ACECriterionTriggers::init);
        LOGGER.info("Alex's Caves Enriched has loaded");
    }
}
