package net.hellomouse.alexscavesenriched.entity;


import com.github.alexmodguy.alexscaves.AlexsCaves;
import com.github.alexmodguy.alexscaves.client.particle.ACParticleRegistry;
import com.github.alexmodguy.alexscaves.server.block.ACBlockRegistry;
import com.github.alexmodguy.alexscaves.server.block.TremorzillaEggBlock;
import com.github.alexmodguy.alexscaves.server.block.fluid.ACFluidRegistry;
import com.github.alexmodguy.alexscaves.server.entity.living.RaycatEntity;
import com.github.alexmodguy.alexscaves.server.entity.living.TremorzillaEntity;
import com.github.alexmodguy.alexscaves.server.misc.ACDamageTypes;
import com.github.alexmodguy.alexscaves.server.misc.ACMath;
import com.github.alexmodguy.alexscaves.server.misc.ACTagRegistry;
import com.github.alexmodguy.alexscaves.server.potion.ACEffectRegistry;
import net.hellomouse.alexscavesenriched.*;
import net.hellomouse.alexscavesenriched.client.ACEClientMod;
import net.hellomouse.alexscavesenriched.recipe.NuclearTransmutationRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.AbstractGlassBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.PlayMessages;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicInteger;

// Nuclear explosion replaces Alex's cave's nuclear explosion when enabled in config
// (for usual nuclear bomb detonations)
public class NuclearExplosion2Entity extends Entity {
    private boolean spawnedParticle; // Mushroom cloud is a big particle

    // 16^3 subchunk actually
    // full = entire subchunk scheduled to be destroyed
    // partial = would be clipped by explosion
    private final Stack<BlockPos> toDestroyFullChunks;
    private final Stack<BlockPos> toDestroyPartialChunks;
    private final Stack<BlockPos> toDistortChunks;
    private int toDamageRays = 0;
    private float itemDropModifier;
    private boolean loadingChunks;
    private Explosion dummyExplosion;

    private static final EntityDataAccessor<Float> SIZE;
    private static final EntityDataAccessor<Boolean> NO_GRIEFING;
    private static final EntityDataAccessor<Integer> EXPLOSION_STAGE;

    public enum ExplosionState {
        CALCULATE_WHAT_TO_DESTROY, DESTORYING, CALCULATE_WHAT_TO_DISTORT, DISTORTING, CALCULATE_WHAT_TO_DAMAGE, // Secondary block damage like broken windows
        DAMAGING, ACID_SCATTER, DONE
    }

    public static int EXTRA_BLAST_RADIUS_CHUNKS = 3;
    public static int ACID_SCATTER_RADIUS_BLOCKS = 12;
    public static float MAX_FLING_VELOCITY = 20.0F;
    public static int CHUNKS_AFFECTED_RADIUS_MULTIPLIER = 15;

    static {
        SIZE = SynchedEntityData.defineId(NuclearExplosion2Entity.class, EntityDataSerializers.FLOAT);
        NO_GRIEFING = SynchedEntityData.defineId(NuclearExplosion2Entity.class, EntityDataSerializers.BOOLEAN);
        EXPLOSION_STAGE = SynchedEntityData.defineId(NuclearExplosion2Entity.class, EntityDataSerializers.INT);
    }

    private final List<SmeltingRecipe> smeltingRecipes = getCommandSenderWorld().getRecipeManager().getAllRecipesFor(RecipeType.SMELTING);

    public NuclearExplosion2Entity(EntityType<?> entityType, Level level) {
        super(entityType, level);
        this.spawnedParticle = false;
        this.toDestroyPartialChunks = new Stack<>();
        this.toDestroyFullChunks = new Stack<>();
        this.toDistortChunks = new Stack<>();
        this.toDamageRays = 0;
        this.loadingChunks = false;
    }

    public NuclearExplosion2Entity(PlayMessages.SpawnEntity spawnEntity, Level level) {
        this(ACEEntityRegistry.NUCLEAR_EXPLOSION2.get(), level);
        this.setBoundingBox(this.makeBoundingBox());
    }

    // For sorting purposes, we bias sorting to prefer horizontal directional more initially
    public static double chunkBlockPosToDis(BlockPos blockPos, BlockPos center) {
        int dx = Math.abs(center.getX() - blockPos.getX());
        int dy = Math.abs(center.getY() - blockPos.getY());
        int dz = Math.abs(center.getZ() - blockPos.getZ());
        return dz + dx + dy * 2;
    }

    public static boolean anyChunkVertexOutsideSphere(BlockPos chunkPos, float blockRadius, BlockPos center) {
        var radiusSquared = blockRadius * blockRadius * 0.2F; // because alex's caves scales radius^2 by random factor
        for (int i = 0; i <= 16; i += 16)
            for (int j = 0; j <= 16; j += 16)
                for (int k = 0; k <= 16; k += 16)
                    if (chunkPos.offset(i, j, k).distSqr(center) > radiusSquared)
                        return true;
        return false;
    }

    public static void loadChunksInRadius(Level level, Entity owner, ChunkPos chunkPos, boolean load, int radius) {
        if (level instanceof ServerLevel serverLevel) {
            int dist = Math.max(radius, serverLevel.getServer().getPlayerList().getViewDistance() / 2);
            for (int i = -dist; i <= dist; ++i)
                for (int j = -dist; j <= dist; ++j)
                    ForgeChunkManager.forceChunk(serverLevel, AlexsCavesEnriched.MODID, owner, chunkPos.x + i, chunkPos.z + j, load, load);
        }
    }

    public @NotNull Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    @OnlyIn(Dist.CLIENT)
    public void clientTick() {
        ACEClientMod.setNukeSky(ACEClientMod.NukeSkyType.NUKE, 1F - tickCount / (this.getSize() > 3 ? 600F : 200F));
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            this.clientTick();
        }
        int chunksAffected = getChunksAffected();
        int radius = chunksAffected * CHUNKS_AFFECTED_RADIUS_MULTIPLIER;
        if (!spawnedParticle) {
            spawnedParticle = true;
            int particleY = (int) Math.ceil(this.getY());
            while (particleY > getCommandSenderWorld().getMinBuildHeight() && particleY > this.getY() - radius / 2F && isDestroyable(getCommandSenderWorld().getBlockState(BlockPos.containing(this.getX(), particleY, this.getZ()))))
                particleY--;
            getCommandSenderWorld().addAlwaysVisibleParticle(ACParticleRegistry.MUSHROOM_CLOUD.get(), true, this.getX(), particleY + 2, this.getZ(), this.getSize() * 2.5F, 0.0F, 0);

            // Shock wave
            final float SHOCKWAVE_VEL = 0.5F;
            if (getSize() > 3) {
                for (float theta = 0.0F; theta < 2 * Math.PI; theta += 0.05F) {
                    getCommandSenderWorld().addAlwaysVisibleParticle(ACEParticleRegistry.NUKE_BLAST.get(), true,
                            this.getX(), this.getY(), this.getZ(),
                            SHOCKWAVE_VEL * Math.cos(theta), 0.0F, SHOCKWAVE_VEL * Math.sin(theta));
                }
            }
        }
        if (tickCount > 40 && getExplosionState() == ExplosionState.DONE) {
            this.remove(RemovalReason.DISCARDED);
            return;
        }

        if (!getCommandSenderWorld().isClientSide && !isNoGriefing()) {
            if (!loadingChunks && !this.isRemoved()) {
                loadingChunks = true;
                loadChunksAround(true);
            }

            final int CHUNKS_TO_PROCESS_PER_TICK = 12;
            // Can't do anything anyways /shrug
            if (getExplosionState() == ExplosionState.CALCULATE_WHAT_TO_DESTROY && AlexsCaves.COMMON_CONFIG.nukeMaxBlockExplosionResistance.get() <= 0)
                setExplosionStage(ExplosionState.DONE);

            if (getExplosionState() == ExplosionState.CALCULATE_WHAT_TO_DESTROY) {
                setExplosionStage(ExplosionState.DESTORYING);
                gatherChunksToAffect();
            } else if (getExplosionState() == ExplosionState.DESTORYING) {
                int chunkToDestroyBudget = CHUNKS_TO_PROCESS_PER_TICK; // Chunks can destroy per tick
                while (chunkToDestroyBudget > 0 && !toDestroyFullChunks.isEmpty()) {
                    destroyFullChunk();
                    chunkToDestroyBudget--;
                }
                while (chunkToDestroyBudget > 0 && !toDestroyPartialChunks.isEmpty()) {
                    destroyChunk(radius);
                    chunkToDestroyBudget--;
                }

                if (toDestroyPartialChunks.isEmpty() && toDestroyFullChunks.isEmpty())
                    setExplosionStage(ExplosionState.CALCULATE_WHAT_TO_DISTORT);
            } else if (getExplosionState() == ExplosionState.CALCULATE_WHAT_TO_DISTORT) {
                // Shock wave effects
                setExplosionStage(ExplosionState.DISTORTING);
            } else if (getExplosionState() == ExplosionState.DISTORTING) {
                int chunkToDistortBudget = CHUNKS_TO_PROCESS_PER_TICK;
                while (chunkToDistortBudget > 0 && !toDistortChunks.isEmpty()) {
                    distortChunk(radius);
                    chunkToDistortBudget--;
                }
                if (toDistortChunks.isEmpty())
                    setExplosionStage(ExplosionState.ACID_SCATTER);
            } else if (getExplosionState() == ExplosionState.ACID_SCATTER) {
                scatterAcid(radius);
                setExplosionStage(ExplosionState.CALCULATE_WHAT_TO_DAMAGE);
            } else if (getExplosionState() == ExplosionState.CALCULATE_WHAT_TO_DAMAGE) {
                setExplosionStage(ExplosionState.DAMAGING);
                this.toDamageRays = (chunksAffected + getExtraChunks()) * 24;
            } else if (getExplosionState() == ExplosionState.DAMAGING) {
                int chunkToDamageBudget = CHUNKS_TO_PROCESS_PER_TICK;
                while (chunkToDamageBudget > 0 && this.toDamageRays > 0) {
                    castDamageRay(radius + getExtraChunks() * 16);
                    chunkToDamageBudget--;
                }
                if (this.toDamageRays <= 0)
                    setExplosionStage(ExplosionState.DONE);
            }
        }

        // Damage entities
        AABB killBox = this.getBoundingBox().inflate(radius + radius * 0.5F, radius * 0.6, radius + radius * 0.5F);
        float flingStrength = getSize() * 0.33F;
        float maximumDistance = radius + radius * 0.5F + 1;
        for (LivingEntity entity : this.getCommandSenderWorld().getEntitiesOfClass(LivingEntity.class, killBox)) {
            float dist = entity.distanceTo(this);
            float damage = calculateDamage(dist, maximumDistance);
            Vec3 vec3 = entity.position().subtract(this.position()).add(0, 0.3, 0).normalize();
            float playerFling = entity instanceof Player ? 0.5F * flingStrength : flingStrength;

            if (damage > 0) {
                if (entity instanceof RaycatEntity) {
                    damage = 0;
                } else if (entity.getType().is(ACTagRegistry.RESISTS_RADIATION)) {
                    damage *= 0.25F;
                    playerFling *= 0.1F;
                    if (entity instanceof TremorzillaEntity) {
                        playerFling = 0;
                        damage = 0;
                    }
                }
                if (damage > 0)
                    entity.hurt(ACDamageTypes.causeNukeDamage(getCommandSenderWorld().registryAccess()), damage);
                if (AlexsCavesEnriched.CONFIG.nuclear.letNukeKillWither && entity instanceof WitherBoss)
                    entity.hurt(ACDamageTypes.causeIntentionalGameDesign(getCommandSenderWorld().registryAccess()), damage);
            }
            if (entity instanceof Player player && ((player.isCreative() && player.getAbilities().flying) || player.isSpectator()))
                playerFling = 0;

            entity.setDeltaMovement(vec3.scale(Math.min(damage * 0.1F * playerFling, MAX_FLING_VELOCITY)));
            entity.addEffect(new MobEffectInstance(ACEffectRegistry.IRRADIATED.get(), 48000, getSize() <= 1.5F ? 1 : 2, false, false, true));
        }
    }

    private float calculateDamage(float dist, float max) {
        float revert = (max - dist) / max;
        float baseDmg = this.getSize() <= 1.5F ? 100.0F : 100.0F + (this.getSize() - 1.5F) * 400.0F;
        return revert * baseDmg;
    }

    private void gatherChunksToAffect() {
        int chunksAffected = getChunksAffected();
        int radius = chunksAffected * CHUNKS_AFFECTED_RADIUS_MULTIPLIER;
        toDestroyPartialChunks.clear();
        toDestroyFullChunks.clear();
        toDistortChunks.clear();

        BlockPos center = this.blockPosition();
        for (int i = -chunksAffected; i <= chunksAffected; i++)
            for (int j = -chunksAffected; j <= chunksAffected; j++)
                for (int k = -chunksAffected; k <= chunksAffected; k++) {
                    var chunkPos = center.offset(i * 16, j * 16, k * 16);
                    if (chunkPos.distSqr(center) < Math.pow(Math.max(0, 16 * (chunksAffected - 4)), 2) ||
                            !anyChunkVertexOutsideSphere(chunkPos, radius, center))
                        toDestroyFullChunks.push(chunkPos);
                    else {
                        toDistortChunks.push(chunkPos);
                        toDestroyPartialChunks.push(chunkPos);
                    }
                }

        toDestroyFullChunks.sort((blockPos1, blockPos2) -> Double.compare(
                chunkBlockPosToDis(blockPos2, this.blockPosition()), chunkBlockPosToDis(blockPos1, this.blockPosition())));
        toDestroyPartialChunks.sort((blockPos1, blockPos2) -> Double.compare(
                chunkBlockPosToDis(blockPos2, this.blockPosition()), chunkBlockPosToDis(blockPos1, this.blockPosition())));
        toDistortChunks.sort((blockPos1, blockPos2) ->
                Double.compare(blockPos2.distManhattan(this.blockPosition()), blockPos1.distManhattan(this.blockPosition())));
    }

    private final HashMap<Block, Optional<Block>> cachedSmeltingResults = new HashMap<>();

    private void loadChunksAround(boolean load) {
        loadChunksInRadius(getCommandSenderWorld(), this, new ChunkPos(this.blockPosition()), load, this.getChunksAffected() + getExtraChunks());
    }

    private Optional<Block> getResultingSmeltedBlock(BlockState blockState) {
        var block = blockState.getBlock();
        if (cachedSmeltingResults.containsKey(block)) {
            return cachedSmeltingResults.get(block);
        } else {
            if (blockState.isAir()) return Optional.empty();
            for (var recipe : smeltingRecipes) {
                var ingredients = recipe.getIngredients();
                if (ingredients.size() == 1 && ingredients.get(0).test(block.asItem().getDefaultInstance())) {
                    var result = Block.byItem(recipe.getResultItem(getCommandSenderWorld().registryAccess()).getItem());
                    if (result != Blocks.AIR) {
                        cachedSmeltingResults.put(block, Optional.of(result));
                        return Optional.of(result);
                    } else {
                        cachedSmeltingResults.put(block, Optional.empty());
                        return Optional.empty();
                    }
                }
            }
        }
        return Optional.empty();
    }

    private void smeltBlocks(BlockPos blockPos) {
        final int radius = 1;
        final int radiusSqr = radius * radius;
        BlockPos.MutableBlockPos carve = new BlockPos.MutableBlockPos();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                for (int y = -radius; y <= radius; y++) {
                    carve.set(blockPos.getX() + x, blockPos.getY() + y, blockPos.getZ() + z);
                    if (blockPos.getY() + y < getCommandSenderWorld().getMinBuildHeight() || carve.distSqr(blockPos) > radiusSqr)
                        continue;
                    var current_block = getCommandSenderWorld().getBlockState(carve);
                    var result = getResultingSmeltedBlock(current_block);
                    result.ifPresent(block -> getCommandSenderWorld().setBlock(carve, block.defaultBlockState(), 2));
                }
            }
        }
    }

    private void tryTransmuteBlock(BlockPos blockpos) {
        var currentState = getCommandSenderWorld().getBlockState(blockpos);
        var recipes = getCommandSenderWorld().getRecipeManager().getAllRecipesFor(ACERecipeRegistry.NUCLEAR_TRANSMUTATION_TYPE.get());
        for (NuclearTransmutationRecipe recipe : recipes) {
            if (recipe.matches(currentState) && (recipe.getChance() == 1.0F || getCommandSenderWorld().getRandom().nextFloat() <= recipe.getChance())) {
                currentState = recipe.getOutput().defaultBlockState();
                getCommandSenderWorld().setBlockAndUpdate(blockpos, currentState);
                break;
            }
        }
    }

    public void tryPlaceGlowingAir(BlockPos blockPos) {
//        final int EVERY = 3;
//        if (blockPos.getX() % EVERY != 0 || blockPos.getZ() % EVERY != 0 || blockPos.getY() % EVERY != 0)
//            return;
        if (getCommandSenderWorld().getBlockState(blockPos).isAir())
            getCommandSenderWorld().setBlock(blockPos, ACEBlockRegistry.NUKE_GLOWING_AIR.get().defaultBlockState(), 3);
    }

    private void explodeBlock(BlockPos carve) {
        BlockState state = getCommandSenderWorld().getBlockState(carve);
        if ((!state.isAir() || !state.getFluidState().isEmpty()) && isDestroyable(state)) {
            if (state.is(ACBlockRegistry.TREMORZILLA_EGG.get()) && state.getBlock() instanceof TremorzillaEggBlock tremorzillaEggBlock) {
                tremorzillaEggBlock.spawnDinosaurs(getCommandSenderWorld(), carve, state);
            } else if (AlexsCaves.COMMON_CONFIG.nukesSpawnItemDrops.get() && random.nextFloat() < this.itemDropModifier && state.getFluidState().isEmpty()) {
                getCommandSenderWorld().destroyBlock(carve, true);
            } else {
                state.onBlockExploded(getCommandSenderWorld(), carve, dummyExplosion);
            }
        }
        if (AlexsCavesEnriched.CONFIG.nuclear.irradiateAir)
            tryPlaceGlowingAir(carve);
    }

    private void destroyFullChunk() {
        BlockPos chunkCorner = toDestroyFullChunks.pop();
        if (AlexsCaves.COMMON_CONFIG.nukeMaxBlockExplosionResistance.get() <= 0) return;

        BlockPos.MutableBlockPos carve = new BlockPos.MutableBlockPos();
        carve.set(chunkCorner);
        this.preExplodeUpdate();

        // Go slightly outside of chunk bounds so fluids don't flow back into the hole
        int maxDeltaY = Math.min(getCommandSenderWorld().getMaxBuildHeight() - chunkCorner.getY(), 16);
        for (int x = -1; x < 17; x++)
            for (int z = -1; z < 17; z++)
                for (int y = maxDeltaY; y >= 0; y--) {
                    if (chunkCorner.getY() + y < getCommandSenderWorld().getMinBuildHeight()) break;
                    carve.set(chunkCorner.getX() + x, chunkCorner.getY() + y, chunkCorner.getZ() + z);
                    this.explodeBlock(carve);
                }
    }

    private void destroyChunk(int radius) {
        BlockPos chunkCorner = toDestroyPartialChunks.pop();
        if (AlexsCaves.COMMON_CONFIG.nukeMaxBlockExplosionResistance.get() <= 0) return;

        BlockPos.MutableBlockPos carve = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos carveBelow = new BlockPos.MutableBlockPos();
        carve.set(chunkCorner);
        carveBelow.set(chunkCorner);
        this.preExplodeUpdate();

        int maxDeltaY = Math.min(getCommandSenderWorld().getMaxBuildHeight() - chunkCorner.getY(), 16);
        for (int x = -1; x < 17; x++)
            for (int z = -1; z < 17; z++)
                for (int y = maxDeltaY; y >= 0; y--) {
                    if (chunkCorner.getY() + y < getCommandSenderWorld().getMinBuildHeight()) break;

                    boolean canSetToFire = false;
                    carve.set(chunkCorner.getX() + x, chunkCorner.getY() + y, chunkCorner.getZ() + z);
                    double yDist = ACMath.smin(0.6F - Math.abs(this.blockPosition().getY() - carve.getY()) / (float) radius, 0.6F, 0.2F);
                    double distToCenter = carve.distToLowCornerSqr(this.blockPosition().getX(), carve.getY() - 1, this.blockPosition().getZ());
                    double targetRadius = yDist * radius * radius;

                    if (distToCenter <= targetRadius) {
                        float widthSimplexNoise1 = (ACMath.sampleNoise3D(carve.getX(), carve.getY(), carve.getZ(), radius) - 0.5F) * 0.45F + 0.55F;
                        targetRadius += yDist * (widthSimplexNoise1 * radius) * radius;
                        if (distToCenter <= targetRadius) {
                            BlockState state = getCommandSenderWorld().getBlockState(carve);
                            if ((!state.isAir() || !state.getFluidState().isEmpty()) && isDestroyable(state)) {
                                carveBelow.set(carve.getX(), carve.getY() - 1, carve.getZ());
                                canSetToFire = true;
                                this.explodeBlock(carve);
                            }
                        }
                    }

                    if (canSetToFire && !getCommandSenderWorld().getBlockState(carveBelow).isAir()) {
                        if (random.nextFloat() < 0.15)
                            getCommandSenderWorld().setBlockAndUpdate(carveBelow.above(), Blocks.FIRE.defaultBlockState());
                    }
                }
    }

    private void distortChunk(int radius) {
        BlockPos chunkCorner = toDistortChunks.pop();
        if (AlexsCaves.COMMON_CONFIG.nukeMaxBlockExplosionResistance.get() <= 0) return;

        BlockPos.MutableBlockPos carve = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos carveAbove = new BlockPos.MutableBlockPos();
        carve.set(chunkCorner);
        carveAbove.set(chunkCorner);
        double radius_2 = Math.pow(radius + 3, 2.0);

        final int MAX_SHOCKWAVE_DELTA = 5;
        int maxDeltaY = Math.min(getCommandSenderWorld().getMaxBuildHeight() - chunkCorner.getY(), 16);
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                for (int y = maxDeltaY; y >= 0; y--) {
                    if (chunkCorner.getY() + y < getCommandSenderWorld().getMinBuildHeight()) break;

                    carve.set(chunkCorner.getX() + x, chunkCorner.getY() + y, chunkCorner.getZ() + z);
                    double distToCenter = carve.distToLowCornerSqr(this.blockPosition().getX(), carve.getY() - 1, this.blockPosition().getZ());
                    if (distToCenter > radius_2) continue;

                    // Block transmutation
                    tryTransmuteBlock(carve);
                    BlockState state = getCommandSenderWorld().getBlockState(carve);

                    // Break breakable blocks if possible
                    if (!state.isAir() && (state.canBeReplaced() || state.is(BlockTags.LEAVES) || state.is(AlexsCavesEnriched.WEAK_PLANTS_TAG))) {
                        this.getCommandSenderWorld().removeBlock(carve, true);
                        continue;
                    }
                    // Burn flammable blocks if possible
                    if (state.isFlammable(getCommandSenderWorld(), carve, Direction.UP))
                        getCommandSenderWorld().setBlock(carve.above(), Blocks.FIRE.defaultBlockState(), 3);

                    // Shockwave blast
                    if (Math.cos(distToCenter / 300.0) < 0) // cos(x^2)
                        continue;

                    if (!state.isAir() && !state.canBeReplaced() && isDestroyable(state) &&
                            !(state.getBlock() instanceof EntityBlock)) {
                        int blastHeight = 1 + (int) (getCommandSenderWorld().getRandom().nextFloat() * (int) (Math.min(1.0, distToCenter / (radius * radius)) * MAX_SHOCKWAVE_DELTA * 2));
                        blastHeight = Math.min(MAX_SHOCKWAVE_DELTA, blastHeight);
                        carveAbove.set(carve.getX(), Math.min(carve.getY() + blastHeight, getCommandSenderWorld().getMaxBuildHeight()), carve.getZ());
                        BlockState aboveState = getCommandSenderWorld().getBlockState(carveAbove);
                        if (aboveState.isAir() || !aboveState.getFluidState().isEmpty() || aboveState.canBeReplaced()) {
                            if (state.canSurvive(this.getCommandSenderWorld(), carveAbove.relative(Direction.Axis.Y, 1))) {
                                this.getCommandSenderWorld().removeBlock(carve, true);
                                this.getCommandSenderWorld().setBlockAndUpdate(carveAbove, state);
                            }
                        }
                    }
                }
    }

    private void castDamageRay(int radius) { // this radius is secondary blast radius
        if (AlexsCaves.COMMON_CONFIG.nukeMaxBlockExplosionResistance.get() <= 0) return;

        this.toDamageRays--;
        var center = new Vec3(this.blockPosition().getX(), this.blockPosition().getY(), this.blockPosition().getZ());
        var direction = new Vec3(getCommandSenderWorld().getRandom().nextFloat() - 0.5F, 0.0F, getCommandSenderWorld().getRandom().nextFloat() - 0.5F).normalize();
        var ctx = new ClipBlockStateContext(
                center.add(direction.scale(radius - 16 * getExtraChunks())),
                center.add(direction.scale(radius)),
                block -> false);

        AtomicInteger resistance_quota = new AtomicInteger((int) (600 * getSize() / 6.0F));
        AtomicInteger blocks_affected = new AtomicInteger(0);

        BlockGetter.traverseBlocks(ctx.getFrom(), ctx.getTo(), ctx.isTargetBlock(), (_ctx, pos) -> {
            final int MAX_DELTAY = 32;
            int deltaY = -MAX_DELTAY;
            while (pos.getY() - deltaY > getCommandSenderWorld().getMinBuildHeight() && deltaY < MAX_DELTAY) {
                var state = getCommandSenderWorld().getBlockState(pos.relative(Direction.DOWN, deltaY));
                if (!state.isAir() && isDestroyable(state))
                    break;
                deltaY++;
            }
            if (deltaY == MAX_DELTAY)
                return null;
            pos = pos.relative(Direction.DOWN, deltaY);

            BlockState state = getCommandSenderWorld().getBlockState(pos);
            resistance_quota.addAndGet((int) -state.getBlock().getExplosionResistance());
            if (resistance_quota.get() < 0 || blocks_affected.get() > getExtraChunks() * 16 + 32)
                return true;
            blocks_affected.incrementAndGet();

            // Break some weak blocks
            if (state.canBeReplaced() || state.getBlock() instanceof AbstractGlassBlock || state.getBlock().getExplosionResistance() <= 1.0)
                this.getCommandSenderWorld().removeBlock(pos, true);
            // Burn flammable blocks if possible
            if (state.isFlammable(getCommandSenderWorld(), pos, Direction.UP))
                getCommandSenderWorld().setBlock(pos.above(), Blocks.FIRE.defaultBlockState(), 3);

            // Transmute blocks around ray
            tryTransmuteBlock(new BlockPos(pos.getX() + 1, pos.getY(), pos.getZ()));
            tryTransmuteBlock(new BlockPos(pos.getX() - 1, pos.getY(), pos.getZ()));
            tryTransmuteBlock(new BlockPos(pos.getX(), pos.getY() + 1, pos.getZ()));
            tryTransmuteBlock(new BlockPos(pos.getX(), pos.getY() - 1, pos.getZ()));
            tryTransmuteBlock(new BlockPos(pos.getX(), pos.getY(), pos.getZ() + 1));
            tryTransmuteBlock(new BlockPos(pos.getX(), pos.getY(), pos.getZ() - 1));

            // Smelt blocks
            if (AlexsCavesEnriched.CONFIG.nuclear.smeltBlock)
                smeltBlocks(pos);
            return null;
        }, _ctx -> false);
    }

    private void scatterAcid(int radius) {
        BlockPos.MutableBlockPos carve = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 16; i++) {
            carve.set(
                    this.getX() + getCommandSenderWorld().getRandom().nextIntBetweenInclusive(-ACID_SCATTER_RADIUS_BLOCKS, ACID_SCATTER_RADIUS_BLOCKS),
                    this.getY(),
                    this.getZ() + getCommandSenderWorld().getRandom().nextIntBetweenInclusive(-ACID_SCATTER_RADIUS_BLOCKS, ACID_SCATTER_RADIUS_BLOCKS)
            );

            int deltaY = 0;
            while (this.getY() - deltaY > getCommandSenderWorld().getMinBuildHeight() && deltaY < radius + 32) {
                var state = getCommandSenderWorld().getBlockState(carve.relative(Direction.DOWN, deltaY));
                if (!state.isAir() && !state.canBeReplaced())
                    break;
                deltaY++;
            }
            BlockPos place = carve.relative(Direction.DOWN, deltaY - 1);
            if (getCommandSenderWorld().dimensionTypeId() != BuiltinDimensionTypes.NETHER) {
                getCommandSenderWorld().setBlockAndUpdate(place, ACFluidRegistry.ACID_FLUID_SOURCE.get().defaultFluidState().createLegacyBlock());
            } else {
                getCommandSenderWorld().setBlockAndUpdate(place, ACBlockRegistry.UNREFINED_WASTE.get().defaultBlockState());
            }

            if (i == 0 && AlexsCavesEnriched.CONFIG.nuclear.irradiationTime > 0) {
                AreaEffectCloud areaEffectCloudEntity = new AreaEffectCloud(this.getCommandSenderWorld(), this.getX(), this.getY() - deltaY + 1.2f, this.getZ());
                areaEffectCloudEntity.setParticle(ACParticleRegistry.GAMMAROACH.get());
                areaEffectCloudEntity.setFixedColor(0);
                areaEffectCloudEntity.addEffect(new MobEffectInstance(ACEffectRegistry.IRRADIATED.get(), AlexsCavesEnriched.CONFIG.nuclear.irradiationPotionTime, 4));
                areaEffectCloudEntity.setRadius((float) radius);
                areaEffectCloudEntity.setDuration(AlexsCavesEnriched.CONFIG.nuclear.irradiationTime);
                areaEffectCloudEntity.setRadiusPerTick(-areaEffectCloudEntity.getRadius() / (float) areaEffectCloudEntity.getDuration());
                this.getCommandSenderWorld().addFreshEntity(areaEffectCloudEntity);
            }
        }
    }

    private void preExplodeUpdate() {
        this.itemDropModifier = 0.025F / Math.min(1, this.getSize());
        if (this.dummyExplosion == null)
            this.dummyExplosion = new Explosion(getCommandSenderWorld(), null, this.getX(), this.getY(), this.getZ(), 10.0F, List.of());
    }

    private boolean isDestroyable(BlockState state) {
        return (!state.is(ACTagRegistry.NUKE_PROOF) && state.getBlock().getExplosionResistance() < (float) AlexsCaves.COMMON_CONFIG.nukeMaxBlockExplosionResistance.get()) || state.is(ACBlockRegistry.TREMORZILLA_EGG.get());
    }

    private int getChunksAffected() {
        return (int) Math.ceil(this.getSize());
    }

    private int getExtraChunks() {
        return (int) Math.ceil(this.getSize() / AlexsCaves.COMMON_CONFIG.nukeExplosionSizeModifier.get().floatValue() * EXTRA_BLAST_RADIUS_CHUNKS);
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
        this.entityData.define(EXPLOSION_STAGE, 0);
    }

    public float getSize() {
        return this.entityData.get(SIZE);
    }

    public void setSize(float f) {
        this.entityData.set(SIZE, f);
    }

    public ExplosionState getExplosionState() {
        return ExplosionState.values()[this.entityData.get(EXPLOSION_STAGE)];
    }

    public void setExplosionStage(ExplosionState state) {
        this.entityData.set(EXPLOSION_STAGE, state.ordinal());
    }

    public void setExplosionStage(int state) {
        this.entityData.set(EXPLOSION_STAGE, state < ExplosionState.values().length ? state : 0);
    }

    public boolean isNoGriefing() {
        return this.entityData.get(NO_GRIEFING);
    }

    public void setNoGriefing(boolean noGriefing) {
        this.entityData.set(NO_GRIEFING, noGriefing);
    }

    protected void readAdditionalSaveData(CompoundTag compoundTag) {
        this.loadingChunks = compoundTag.getBoolean("WasLoadingChunks");
        this.setSize(compoundTag.getFloat("Size"));
        this.setNoGriefing(compoundTag.getBoolean("NoGriefing"));
        this.setExplosionStage(compoundTag.getInt("ExplosionStage"));
        gatherChunksToAffect();
    }

    protected void addAdditionalSaveData(CompoundTag compoundTag) {
        compoundTag.putBoolean("WasLoadingChunks", this.loadingChunks);
        compoundTag.putFloat("Size", this.getSize());
        compoundTag.putBoolean("NoGriefing", this.isNoGriefing());
        compoundTag.putInt("ExplosionStage", this.getExplosionState().ordinal());
    }
}
