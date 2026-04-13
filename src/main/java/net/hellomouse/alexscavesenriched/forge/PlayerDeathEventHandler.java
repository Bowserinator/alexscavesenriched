package net.hellomouse.alexscavesenriched.forge;

import com.github.alexmodguy.alexscaves.server.item.ACItemRegistry;
import net.hellomouse.alexscavesenriched.ACEBlockRegistry;
import net.hellomouse.alexscavesenriched.ACEItemRegistry;
import net.hellomouse.alexscavesenriched.AlexsCavesEnriched;
import net.hellomouse.alexscavesenriched.item.DeadmanSwitchItem;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

@Mod.EventBusSubscriber(modid = AlexsCavesEnriched.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class PlayerDeathEventHandler {
    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        for (var item : player.getInventory().items)
            if (item.getItem() instanceof DeadmanSwitchItem && (DeadmanSwitchItem.isActive(item)))
                DeadmanSwitchItem.detonate(player.level(), player, item);
            else if (event.getSource().getDirectEntity() instanceof LivingEntity livingEntity) {
                var mainHand = livingEntity.getMainHandItem();
                var isACWeapon = mainHand.is(ACEItemRegistry.FLAMETHROWER.get()) || mainHand.is(ACEItemRegistry.RAYGUN.get()) || mainHand.is(ACItemRegistry.RAYGUN.get());
                @Nullable Item fumo = null;
                switch (player.getName().getString()) {
                    case "Xenonomous" -> {
                        fumo = ACEBlockRegistry.XENO_FUMO.get().asItem();
                    }
                    case "Bowserinator" -> {
                        fumo = ACEBlockRegistry.XIAOYU_FUMO.get().asItem();
                    }
                }
                if (fumo != null && !player.level().isClientSide) {
                    var itemEntity = new ItemEntity(player.level(), player.getX(), player.getY(), player.getZ(), fumo.getDefaultInstance());
                    itemEntity.setDefaultPickUpDelay();
                    player.level().addFreshEntity(itemEntity);
                }
            }
        for (var item : player.getInventory().offhand)
            if (item.getItem() instanceof DeadmanSwitchItem && (DeadmanSwitchItem.isActive(item)))
                DeadmanSwitchItem.detonate(player.level(), player, item);
    }
}
