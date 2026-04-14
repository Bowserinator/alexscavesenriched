package net.hellomouse.alexscavesenriched.block.block_entity;

import com.github.alexmodguy.alexscaves.AlexsCaves;
import net.hellomouse.alexscavesenriched.ACEBlockEntityRegistry;
import net.hellomouse.alexscavesenriched.ACERecipeRegistry;
import net.hellomouse.alexscavesenriched.ACESounds;
import net.hellomouse.alexscavesenriched.AlexsCavesEnriched;
import net.hellomouse.alexscavesenriched.block.centrifuge.CentrifugeMultiBlockBaseBlock;
import net.hellomouse.alexscavesenriched.block.centrifuge.CentrifugeUtil;
import net.hellomouse.alexscavesenriched.client.sound.CentrifugeSound;
import net.hellomouse.alexscavesenriched.inventory.CentrifugeBlockMenu;
import net.hellomouse.alexscavesenriched.recipe.CentrifugeRecipe;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.SidedInvWrapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Objects;

public class CentrifugeBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer {
    public static final int N_INPUT_SLOTS = 9;
    public static final int N_OUTPUT_SLOTS = 9;
    public static final int N_SLOTS = N_INPUT_SLOTS + N_OUTPUT_SLOTS;
    public static final int MAX_SPIN_SPEED = AlexsCavesEnriched.CONFIG.centrifuge.maxSpeed;
    public static final float ANGLE_PER_TICK = 50F;

    protected final ContainerData dataAccess;
    protected NonNullList<ItemStack> items;

    public int age;
    private int spinSpeed = 0;
    private float rotation = 0;
    private boolean spawnedSound = false;

    private static final int[] SLOTS_FOR_TOP = new int[]{0,1,2,3,4,5,6,7,8};
    private static final int[] SLOTS_FOR_DOWN = new int[]{9,10,11,12,13,14,15,16,17};
    private final LazyOptional<? extends IItemHandler>[] handlers;

    public CentrifugeBlockEntity(BlockPos pos, BlockState state) {
        super(ACEBlockEntityRegistry.CENTRIFUGE.get(), pos, state);
        this.items = NonNullList.withSize(N_SLOTS, ItemStack.EMPTY);

        this.dataAccess = new ContainerData() {
            public int get(int type) {
                return switch (type) {
                    case 0 -> CentrifugeBlockEntity.this.spinSpeed;
                    default -> 0;
                };
            }

            public void set(int type, int value) {
                switch (type) {
                    case 0 -> CentrifugeBlockEntity.this.spinSpeed = value;
                    default -> {}
                }
            }

            public int getCount() {
                return 1;
            }
        };
        this.handlers = SidedInvWrapper.create(this, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);
    }

    public static void tick(Level level, BlockPos blockPos, BlockState state, CentrifugeBlockEntity entity) {
        entity.age++;
        entity.rotation += ((float)entity.spinSpeed / MAX_SPIN_SPEED) * ANGLE_PER_TICK;
        while (entity.rotation > 360)
            entity.rotation -= 360;

        if (level.isClientSide) {
            entity.clientTick();
            return;
        }

        boolean requireResync = false;
        boolean invChanged = false;
        var prevSpinSpeed = entity.spinSpeed;
        entity.spinSpeed += entity.getBlockState().getValue(CentrifugeMultiBlockBaseBlock.POWERED) ? 1 : -1;
        entity.spinSpeed = Math.max(0, Math.min(MAX_SPIN_SPEED, entity.spinSpeed));
        if (prevSpinSpeed != entity.spinSpeed)
            requireResync = true;

        if (entity.spinSpeed > 0) {
            if (AlexsCavesEnriched.CONFIG.centrifuge.cantInteractWithActive && entity.age % 10 == 0)
                entity.closeAllOpenScreens();

            for (int i = 0; i < N_INPUT_SLOTS; i++) {
                ItemStack inStack = entity.items.get(i);
                if (inStack.isEmpty()) continue;

                var recipes = level.getRecipeManager().getAllRecipesFor(ACERecipeRegistry.CENTRIFUGE_TYPE.get());
                for (CentrifugeRecipe recipe : recipes) {
                    float chance = recipe.getChance() * ((float)entity.spinSpeed) / MAX_SPIN_SPEED;
                    if (!recipe.matches(inStack) || (level.getRandom().nextFloat() > chance))
                        continue;

                    inStack.shrink(1);
                    requireResync = true;
                    invChanged = true;

                    for (var outStack : recipe.getOutputs()) {
                        boolean canFitInside = false;

                        for (int j = N_INPUT_SLOTS; j < N_SLOTS; j++) {
                            if (!entity.canFitInResultSlot(outStack, j))
                                continue;
                            entity.addOutStack(outStack, j);
                            canFitInside = true;
                            break;
                        }
                        if (!canFitInside) {
                            Vec3 spawnPos = Vec3.atCenterOf(entity.getBlockPos());
                            ItemEntity itemEntity = new ItemEntity(level, spawnPos.x, spawnPos.y, spawnPos.z, outStack);
                            itemEntity.setDeltaMovement(
                                (level.random.nextDouble() - 0.5) * 0.25,
                                (level.random.nextDouble() - 0.5) * 0.25,
                                (level.random.nextDouble() - 0.5) * 0.25
                            );
                            level.addFreshEntity(itemEntity);
                        }
                    }
                    break; // End recipe
                }
            }
        }
        if (invChanged || (entity.rotation == 0 && level.getGameTime() % 20 == 0)) {
            for (int dy = 0; dy < CentrifugeUtil.CENTRIFUGE_HEIGHT; dy++) {
                var pos = entity.getBlockPos().relative(Direction.UP, dy);
                level.updateNeighbourForOutputSignal(pos, level.getBlockState(pos).getBlock());
            }
        }
        if (requireResync)
            entity.syncWithClient();
    }

    public static boolean inputAllowed(Item input, Level level) {
        var recipes = level.getRecipeManager().getAllRecipesFor(ACERecipeRegistry.CENTRIFUGE_TYPE.get());
        for (CentrifugeRecipe recipe : recipes) {
            if (recipe.matches(new ItemStack(input))) {
                return true;
            }
        }
        return false;
    }

    public float getRotation() { return rotation; }

    public int getSpinSpeed() { return spinSpeed; }

    @OnlyIn(Dist.CLIENT)
    public void clientTick() {
        if (!spawnedSound) {
            spawnedSound = true;
            var sound = new CentrifugeSound(this, ACESounds.CENTRIFUGE, SoundSource.AMBIENT);
            Minecraft.getInstance().getSoundManager().queueTickingSound(sound);
        }
    }

    public int getComparatorPowerInput() {
        return getComparatorPower(0, N_INPUT_SLOTS);
    }

    public int getComparatorPowerOutput() {
        return getComparatorPower(N_INPUT_SLOTS, N_SLOTS);
    }

    private int getComparatorPower(int startSlot, int endSlot) { // End slot not inclusive
        if (items.size() == 0)
            return 0;
        int filledSlots = 0;
        float fullness = 0F;

        for (int i = startSlot; i < endSlot; i++) { // Only output slots
            var stack = items.get(i);
            if (!stack.isEmpty()) {
                filledSlots++;
                fullness += (float) stack.getCount() / stack.getMaxStackSize();
            }
        }
        float scale = fullness / (endSlot - startSlot);
        return (int)Math.ceil(scale * 14.0F) + (filledSlots > 0 ? 1 : 0);
    }

    public void closeAllOpenScreens() {
        Level level = getLevel();
        assert level != null;
        if (level.isClientSide)
            return;
        ServerLevel serverLevel = (ServerLevel) level;

        for (ServerPlayer player : serverLevel.getPlayers(playerEntity -> {
            AbstractContainerMenu menu = playerEntity.containerMenu;
            if (menu instanceof CentrifugeBlockMenu cMenu)
                return ((CentrifugeBlockEntity) cMenu.getInventory()).getBlockPos().equals(getBlockPos());
            return false;
        })) {
            player.closeContainer();
        }
    }

    @Override
    public void setRemoved() {
        AlexsCaves.PROXY.clearSoundCacheFor(this);
        super.setRemoved();
    }

    private void syncWithClient() {
        assert this.level != null;
        this.level.sendBlockUpdated(this.getBlockPos(), this.getBlockState(), this.getBlockState(), 2);
    }

    private void addOutStack(ItemStack outStack, int out) {
        if (ItemStack.isSameItem(this.items.get(out), outStack))
            this.items.get(out).grow(outStack.getCount());
        else
            this.setItem(out, outStack.copy());
    }

    private boolean canFitInResultSlot(ItemStack putIn, int resultSlot) {
        ItemStack currentlyInThere = this.items.get(resultSlot);
        if (currentlyInThere.isEmpty()) {
            return true;
        } else if (!ItemStack.isSameItem(currentlyInThere, putIn)) {
            return false;
        } else if (currentlyInThere.getCount() + putIn.getCount() <= currentlyInThere.getMaxStackSize() && currentlyInThere.getCount() + putIn.getCount() <= currentlyInThere.getMaxStackSize()) {
            return true;
        } else {
            return currentlyInThere.getCount() + putIn.getCount() <= putIn.getMaxStackSize();
        }
    }

    @Override
    public int[] getSlotsForFace(Direction direction) {
        if (direction == Direction.DOWN)
            return SLOTS_FOR_DOWN;
        else if (direction == Direction.UP)
            return SLOTS_FOR_TOP;
        return new int[0];
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction direction) {
        if (spinSpeed > 0 && AlexsCavesEnriched.CONFIG.centrifuge.cantInteractWithActive) return false;
        if (direction != Direction.UP) return false;
        return this.canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
        if (spinSpeed > 0 && AlexsCavesEnriched.CONFIG.centrifuge.cantInteractWithActive) return false;
        if (direction == Direction.UP) return false;
        return slot >= N_INPUT_SLOTS;
    }

    @OnlyIn(Dist.CLIENT)
    public AABB getRenderBoundingBox() {
        BlockPos pos = this.getBlockPos(); // TODO
        return new AABB(pos.offset(-1, -1, -1), pos.offset(2, 2, 2));
    }

    @Override
    public void load(CompoundTag compoundTag) {
        super.load(compoundTag);
        this.items.clear();
        ContainerHelper.loadAllItems(compoundTag, this.items);
        this.loadAdditional(compoundTag);
    }

    private void loadAdditional(CompoundTag compoundTag) {
        this.spinSpeed = compoundTag.getInt("SpinSpeed");
    }

    @Override
    protected void saveAdditional(CompoundTag compoundTag) {
        super.saveAdditional(compoundTag);
        ContainerHelper.saveAllItems(compoundTag, this.items, true);
        compoundTag.putInt("SpinSpeed", this.spinSpeed);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return this.saveWithoutMetadata();
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        super.handleUpdateTag(tag);
        this.load(tag);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket packet) {
        if (packet != null && packet.getTag() != null)
            this.loadAdditional(packet.getTag());
    }

    @Override
    public boolean isEmpty() {
        for (var itemStack : this.items)
            if (!itemStack.isEmpty())
                return false;
        return true;
    }

    @Override
    public int getContainerSize() {
        return this.items.size();
    }

    @Override
    public ItemStack getItem(int slot) {
        return this.items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        return ContainerHelper.removeItem(this.items, slot, count);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(this.items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack itemStack) {
        ItemStack itemstack = this.items.get(slot);
        boolean flag = !itemStack.isEmpty() && ItemStack.isSameItemSameTags(itemstack, itemStack);
        this.items.set(slot, itemStack);
        if (itemStack.getCount() > this.getMaxStackSize())
            itemStack.setCount(this.getMaxStackSize());
        if (slot == 0 && !flag)
            this.setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void setChanged() {
        if (level != null) {
            super.setChanged();
            level.updateNeighbourForOutputSignal(this.getBlockPos(), level.getBlockState(this.getBlockPos()).getBlock());
        }
    }

    // Check if input type is allowed
    public static HashSet<Item> allowedItems = new HashSet<>();

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (spinSpeed > 0 && AlexsCavesEnriched.CONFIG.centrifuge.cantInteractWithActive)
            return false;
        return slot < N_INPUT_SLOTS && inputAllowed(stack.getItem(), Objects.requireNonNull(this.getLevel()));
    }

    @Override
    public void clearContent() {
        this.items.clear();
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("block.alexscavesenriched.centrifuge");
    }

    @Override
    protected AbstractContainerMenu createMenu(int syncId, Inventory playerInventory) {
        return new CentrifugeBlockMenu(syncId, playerInventory, this);
    }

    @Override
    @NotNull
    public <T> LazyOptional<T> getCapability(Capability<T> capability, @javax.annotation.Nullable Direction facing) {
        return !this.remove && facing != null && capability == ForgeCapabilities.ITEM_HANDLER ?
                this.handlers[facing.ordinal()].cast() : super.getCapability(capability, facing);
    }
}
