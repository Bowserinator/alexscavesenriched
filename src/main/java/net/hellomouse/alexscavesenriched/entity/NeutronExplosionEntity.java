package net.hellomouse.alexscavesenriched.entity;


import com.github.alexmodguy.alexscaves.client.ClientProxy;
import com.github.alexmodguy.alexscaves.server.entity.living.RaycatEntity;
import com.github.alexmodguy.alexscaves.server.entity.living.TremorzillaEntity;
import com.github.alexmodguy.alexscaves.server.misc.ACDamageTypes;
import com.github.alexmodguy.alexscaves.server.misc.ACMath;
import com.github.alexmodguy.alexscaves.server.misc.ACSoundRegistry;
import com.github.alexmodguy.alexscaves.server.misc.ACTagRegistry;
import com.github.alexmodguy.alexscaves.server.potion.ACEffectRegistry;
import net.hellomouse.alexscavesenriched.ACEEntityRegistry;
import net.hellomouse.alexscavesenriched.ACEParticleRegistry;
import net.hellomouse.alexscavesenriched.ACERecipeRegistry;
import net.hellomouse.alexscavesenriched.AlexsCavesEnriched;
import net.hellomouse.alexscavesenriched.client.ACEClientMod;
import net.hellomouse.alexscavesenriched.recipe.NeutronKillRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.PlayMessages;
import org.jetbrains.annotations.NotNull;

import java.util.Stack;

public class NeutronExplosionEntity extends Entity {
    private boolean spawnedParticle; // Mushroom cloud is a big particle

    // 16^3 subchunk actually
    // full = entire subchunk scheduled to be destroyed
    // partial = would be clipped by explosion
    private final Stack<BlockPos> toDestroyFullChunks;
    private final Stack<BlockPos> toDestroyPartialChunks;
    private boolean loadingChunks;

    private static final EntityDataAccessor<Float> SIZE;
    private static final EntityDataAccessor<Boolean> NO_GRIEFING;

    public enum ExplosionState {
        CALCULATE_WHAT_TO_DESTROY, DESTORYING, DONE
    }
    private ExplosionState explosionState = ExplosionState.CALCULATE_WHAT_TO_DESTROY;

    static {
        SIZE = SynchedEntityData.defineId(NeutronExplosionEntity.class, EntityDataSerializers.FLOAT);
        NO_GRIEFING = SynchedEntityData.defineId(NeutronExplosionEntity.class, EntityDataSerializers.BOOLEAN);
    }

    public NeutronExplosionEntity(EntityType<?> entityType, Level level) {
        super(entityType, level);
        this.spawnedParticle = false;
        this.toDestroyPartialChunks = new Stack<>();
        this.toDestroyFullChunks = new Stack<>();
        this.loadingChunks = false;
    }

    public NeutronExplosionEntity(PlayMessages.SpawnEntity spawnEntity, Level level) {
        this(ACEEntityRegistry.NEUTRON_EXPLOSION.get(), level);
        this.setBoundingBox(this.makeBoundingBox());
    }

    public static void tryTransmuteBlock(Level world, BlockPos blockpos) {
        var currentState = world.getBlockState(blockpos);
        var recipes = world.getRecipeManager().getAllRecipesFor(ACERecipeRegistry.NEUTRON_KILL_TYPE.get());
        for (NeutronKillRecipe recipe : recipes) {
            if (recipe.matches(currentState) && (recipe.getChance() == 1.0F || world.getRandom().nextFloat() <= recipe.getChance())) {
                // Don't replace fluids though
                if (recipe.getIsTag() && recipe.getInputLocation().equals(
                        ResourceLocation.parse("minecraft:replaceable")) && !currentState.getFluidState().isEmpty())
                    continue;

                currentState = recipe.getOutput().defaultBlockState();
                world.setBlockAndUpdate(blockpos, currentState);
                break;
            }
        }
    }

    public @NotNull Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    @OnlyIn(Dist.CLIENT)
    public void clientTick() {
        ACEClientMod.setNukeSky(ACEClientMod.NukeSkyType.NEUTRON, 1F - tickCount / 100F);
    }

    @Override
    public void tick() {
        super.tick();
        var clientSide = this.level().isClientSide;
        if (clientSide) {
            this.clientTick();
        }
        int chunksAffected = getChunksAffected();
        int radius = chunksAffected * 15;
        if (!spawnedParticle) {
            spawnedParticle = true;
            if (clientSide) {
                ClientProxy.renderNukeFlashFor = 8;
            }
            playSound(ACSoundRegistry.NUCLEAR_EXPLOSION_RINGING.get(), 100, 50);

            getCommandSenderWorld().addAlwaysVisibleParticle(ACEParticleRegistry.NEUTRON_BLAST.get(), true,
                    this.getX(), this.getY(), this.getZ(),
                0F, 0F, 0F);
        }
        if (tickCount > 40 && explosionState == ExplosionState.DONE) {
            this.remove(RemovalReason.DISCARDED);
            return;
        }

        if (!getCommandSenderWorld().isClientSide && !isNoGriefing()) {
            if (!loadingChunks && !this.isRemoved()) {
                loadingChunks = true;
                loadChunksAround(true);
            }

            final int CHUNKS_TO_PROCESS_PER_TICK = 12;
            if (explosionState == ExplosionState.CALCULATE_WHAT_TO_DESTROY) {
                explosionState = ExplosionState.DESTORYING;
                BlockPos center = this.blockPosition();
                for (int i = -chunksAffected; i <= chunksAffected; i++)
                    for (int j = -chunksAffected; j <= chunksAffected; j++)
                        for (int k = -chunksAffected; k <= chunksAffected; k++) {
                            var chunkPos = center.offset(i * 16, j * 16, k * 16);
                            if (chunkPos.distSqr(center) < Math.pow(Math.max(0, 16 * (chunksAffected - 4)), 2) ||
                                    !NuclearExplosion2Entity.anyChunkVertexOutsideSphere(chunkPos, radius, center))
                                toDestroyFullChunks.push(chunkPos);
                            else
                                toDestroyPartialChunks.push(chunkPos);
                        }

                toDestroyFullChunks.sort((blockPos1, blockPos2) -> Double.compare(
                        NuclearExplosion2Entity.chunkBlockPosToDis(blockPos2, this.blockPosition()),
                        NuclearExplosion2Entity.chunkBlockPosToDis(blockPos1, this.blockPosition())));
                toDestroyPartialChunks.sort((blockPos1, blockPos2) -> Double.compare(
                        NuclearExplosion2Entity.chunkBlockPosToDis(blockPos2, this.blockPosition()),
                        NuclearExplosion2Entity.chunkBlockPosToDis(blockPos1, this.blockPosition())));
            } else if (explosionState == ExplosionState.DESTORYING) {
                int chunkToDestroyBudget = CHUNKS_TO_PROCESS_PER_TICK; // Chunks can destroy per tick
                while (chunkToDestroyBudget > 0 && !toDestroyFullChunks.isEmpty()) {
                    destroyChunk(radius, toDestroyFullChunks, false);
                    chunkToDestroyBudget--;
                }
                while (chunkToDestroyBudget > 0 && !toDestroyPartialChunks.isEmpty()) {
                    destroyChunk(radius, toDestroyPartialChunks, true);
                    chunkToDestroyBudget--;
                }
                if (toDestroyPartialChunks.isEmpty() && toDestroyFullChunks.isEmpty())
                    explosionState = ExplosionState.DONE;
            }
        }

        // Damage entities
        AABB killBox = this.getBoundingBox().inflate(radius * 1.1F, radius * 1.1F, radius * 1.1F);
        for (LivingEntity entity : this.getCommandSenderWorld().getEntitiesOfClass(LivingEntity.class, killBox)) {
            entity.addEffect(new MobEffectInstance(ACEffectRegistry.IRRADIATED.get(),
                    AlexsCavesEnriched.CONFIG.neutron.irradiationPotionTime,
                    AlexsCavesEnriched.CONFIG.neutron.irradiationPotionPower,
                    false, false, true));

            if (tickCount <= 2) {
                float damage = AlexsCavesEnriched.CONFIG.neutron.burstDamage;
                if (entity instanceof RaycatEntity || entity instanceof TremorzillaEntity)
                    damage = 0;
                else if (entity.getType().is(ACTagRegistry.RESISTS_RADIATION))
                    damage *= 0.25F;
                if (damage > 0)
                    entity.hurt(ACDamageTypes.causeNukeDamage(getCommandSenderWorld().registryAccess()), damage);
            }
        }
    }

    private void loadChunksAround(boolean load) {
        NuclearExplosion2Entity.loadChunksInRadius(getCommandSenderWorld(), this, new ChunkPos(this.blockPosition()), load, this.getChunksAffected());
    }

    private void destroyChunk(int radius, Stack<BlockPos> stack, boolean checkSphere) {
        BlockPos chunkCorner = stack.pop();
        BlockPos.MutableBlockPos carve = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos carveBelow = new BlockPos.MutableBlockPos();
        carve.set(chunkCorner);
        carveBelow.set(chunkCorner);

        int maxDeltaY = Math.min(getCommandSenderWorld().getMaxBuildHeight() - chunkCorner.getY(), 16);
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                for (int y = maxDeltaY; y >= 0; y--) {
                    if (chunkCorner.getY() + y < getCommandSenderWorld().getMinBuildHeight()) break;
                    carve.set(chunkCorner.getX() + x, chunkCorner.getY() + y, chunkCorner.getZ() + z);

                    if (checkSphere) {
                        double yDist = ACMath.smin(0.6F - Math.abs(this.blockPosition().getY() - carve.getY()) / (float) radius, 0.6F, 0.2F);
                        double distToCenter = carve.distToLowCornerSqr(this.blockPosition().getX(), carve.getY() - 1, this.blockPosition().getZ());
                        double targetRadius = yDist * radius * radius;

                        if (distToCenter <= targetRadius) {
                            float widthSimplexNoise1 = (ACMath.sampleNoise3D(carve.getX(), carve.getY(), carve.getZ(), radius) - 0.5F) * 0.45F + 0.55F;
                            targetRadius += yDist * (widthSimplexNoise1 * radius) * radius;
                            if (distToCenter >= targetRadius)
                                continue;
                        } else {
                            continue;
                        }
                    }
                    BlockState state = getCommandSenderWorld().getBlockState(carve);
                    if (!state.isAir() || !state.getFluidState().isEmpty())
                        tryTransmuteBlock(getCommandSenderWorld(), carve);
                }
    }

    private int getChunksAffected() {
        return (int)Math.ceil(this.getSize());
    }

    public void remove(@NotNull RemovalReason removalReason) {
        if (!this.getCommandSenderWorld().isClientSide && this.loadingChunks) {
            this.loadingChunks = false;
            this.loadChunksAround(false);
        }
        super.remove(removalReason);
    }

    protected void defineSynchedData() {
        this.entityData.define(SIZE, 1.0F);
        this.entityData.define(NO_GRIEFING, false);
    }

    public float getSize() {
        return this.entityData.get(SIZE);
    }

    public void setSize(float f) {
        this.entityData.set(SIZE, f);
    }

    public boolean isNoGriefing() {
        return this.entityData.get(NO_GRIEFING);
    }

    public void setNoGriefing(boolean noGriefing) {
        this.entityData.set(NO_GRIEFING, noGriefing);
    }

    protected void readAdditionalSaveData(CompoundTag compoundTag) {
        this.loadingChunks = compoundTag.getBoolean("WasLoadingChunks");
    }

    protected void addAdditionalSaveData(CompoundTag compoundTag) {
        compoundTag.putBoolean("WasLoadingChunks", this.loadingChunks);
    }
}
