package net.hellomouse.alexscavesenriched.entity;


import com.github.alexmodguy.alexscaves.AlexsCaves;
import com.github.alexmodguy.alexscaves.client.particle.ACParticleRegistry;
import com.github.alexmodguy.alexscaves.server.block.ACBlockRegistry;
import com.github.alexmodguy.alexscaves.server.block.TremorzillaEggBlock;
import com.github.alexmodguy.alexscaves.server.entity.living.RaycatEntity;
import com.github.alexmodguy.alexscaves.server.entity.living.TremorzillaEntity;
import com.github.alexmodguy.alexscaves.server.misc.ACDamageTypes;
import com.github.alexmodguy.alexscaves.server.misc.ACMath;
import com.github.alexmodguy.alexscaves.server.misc.ACTagRegistry;
import com.github.alexmodguy.alexscaves.server.potion.ACEffectRegistry;
import net.hellomouse.alexscavesenriched.*;
import net.hellomouse.alexscavesenriched.client.ACEClientMod;
import net.hellomouse.alexscavesenriched.recipe.NuclearTransmutationRecipe;
import net.minecraft.block.*;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.SmeltingRecipe;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.*;
import net.minecraft.world.BlockStateRaycastContext;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionTypes;
import net.minecraft.world.explosion.Explosion;
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

    private static final TrackedData<Float> SIZE;
    private static final TrackedData<Boolean> NO_GRIEFING;
    private static final TrackedData<Integer> EXPLOSION_STAGE;

    public enum ExplosionState {
        CALCULATE_WHAT_TO_DESTROY, DESTORYING, CALCULATE_WHAT_TO_DISTORT, DISTORTING, CALCULATE_WHAT_TO_DAMAGE, // Secondary block damage like broken windows
        DAMAGING, ACID_SCATTER, DONE
    }

    public static int EXTRA_BLAST_RADIUS_CHUNKS = 3;
    public static int ACID_SCATTER_RADIUS_BLOCKS = 12;
    public static float MAX_FLING_VELOCITY = 20.0F;
    public static int CHUNKS_AFFECTED_RADIUS_MULTIPLIER = 15;

    public NuclearExplosion2Entity(EntityType<?> entityType, World level) {
        super(entityType, level);
        this.spawnedParticle = false;
        this.toDestroyPartialChunks = new Stack<>();
        this.toDestroyFullChunks = new Stack<>();
        this.toDistortChunks = new Stack<>();
        this.toDamageRays = 0;
        this.loadingChunks = false;
    }

    public NuclearExplosion2Entity(PlayMessages.SpawnEntity spawnEntity, World level) {
        this(ACEEntityRegistry.NUCLEAR_EXPLOSION2.get(), level);
        this.setBoundingBox(this.calculateBoundingBox());
    }

    public @NotNull Packet<ClientPlayPacketListener> createSpawnPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    public static boolean anyChunkVertexOutsideSphere(BlockPos chunkPos, float blockRadius, BlockPos center) {
        var radiusSquared = blockRadius * blockRadius * 0.2F; // because alex's caves scales radius^2 by random factor
        for (int i = 0; i <= 16; i += 16)
            for (int j = 0; j <= 16; j += 16)
                for (int k = 0; k <= 16; k += 16)
                    if (chunkPos.add(i, j, k).getSquaredDistance(center) > radiusSquared)
                        return true;
        return false;
    }

    // For sorting purposes, we bias sorting to prefer horizontal directional more initially
    public static double chunkBlockPosToDis(BlockPos blockPos, BlockPos center) {
        int dx = Math.abs(center.getX() - blockPos.getX());
        int dy = Math.abs(center.getY() - blockPos.getY());
        int dz = Math.abs(center.getZ() - blockPos.getZ());
        return dz + dx + dy * 2;
    }

    @OnlyIn(Dist.CLIENT)
    public void clientTick() {
        ACEClientMod.setNukeSky(ACEClientMod.NukeSkyType.NUKE, 1F - age / (this.getSize() > 3 ? 600F : 200F));
    }

    @Override
    public void tick() {
        super.tick();
        this.clientTick();

        int chunksAffected = getChunksAffected();
        int radius = chunksAffected * CHUNKS_AFFECTED_RADIUS_MULTIPLIER;
        if (!spawnedParticle) {
            spawnedParticle = true;
            int particleY = (int) Math.ceil(this.getY());
            while (particleY > getEntityWorld().getBottomY() && particleY > this.getY() - radius / 2F && isDestroyable(getEntityWorld().getBlockState(BlockPos.ofFloored(this.getX(), particleY, this.getZ()))))
                particleY--;
            getEntityWorld().addImportantParticle(ACParticleRegistry.MUSHROOM_CLOUD.get(), true, this.getX(), particleY + 2, this.getZ(), this.getSize() * 2.5F, 0.0F, 0);

            // Shock wave
            final float SHOCKWAVE_VEL = 0.5F;
            if (getSize() > 3) {
                for (float theta = 0.0F; theta < 2 * Math.PI; theta += 0.05F) {
                    getEntityWorld().addImportantParticle(ACEParticleRegistry.NUKE_BLAST.get(), true,
                            this.getX(), this.getY(), this.getZ(),
                            SHOCKWAVE_VEL * Math.cos(theta), 0.0F, SHOCKWAVE_VEL * Math.sin(theta));
                }
            }
        }
        if (age > 40 && getExplosionState() == ExplosionState.DONE) {
            this.remove(RemovalReason.DISCARDED);
            return;
        }

        if (!getEntityWorld().isClient && !isNoGriefing()) {
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
        Box killBox = this.getBoundingBox().expand(radius + radius * 0.5F, radius * 0.6, radius + radius * 0.5F);
        float flingStrength = getSize() * 0.33F;
        float maximumDistance = radius + radius * 0.5F + 1;
        for (LivingEntity entity : this.getEntityWorld().getNonSpectatingEntities(LivingEntity.class, killBox)) {
            float dist = entity.distanceTo(this);
            float damage = calculateDamage(dist, maximumDistance);
            Vec3d vec3 = entity.getPos().subtract(this.getPos()).add(0, 0.3, 0).normalize();
            float playerFling = entity instanceof PlayerEntity ? 0.5F * flingStrength : flingStrength;

            if (damage > 0) {
                if (entity instanceof RaycatEntity) {
                    damage = 0;
                } else if (entity.getType().isIn(ACTagRegistry.RESISTS_RADIATION)) {
                    damage *= 0.25F;
                    playerFling *= 0.1F;
                    if (entity instanceof TremorzillaEntity) {
                        playerFling = 0;
                        damage = 0;
                    }
                }
                if (damage > 0)
                    entity.damage(ACDamageTypes.causeNukeDamage(getEntityWorld().getRegistryManager()), damage);
                if (AlexsCavesEnriched.CONFIG.nuclear.letNukeKillWither && entity instanceof WitherEntity)
                    entity.damage(ACDamageTypes.causeIntentionalGameDesign(getEntityWorld().getRegistryManager()), damage);
            }
            if (entity instanceof PlayerEntity player && ((player.isCreative() && player.getAbilities().flying) || player.isSpectator()))
                playerFling = 0;

            entity.setVelocity(vec3.multiply(Math.min(damage * 0.1F * playerFling, MAX_FLING_VELOCITY)));
            entity.addStatusEffect(new StatusEffectInstance(ACEffectRegistry.IRRADIATED.get(), 48000, getSize() <= 1.5F ? 1 : 2, false, false, true));
        }
    }

    private void gatherChunksToAffect() {
        int chunksAffected = getChunksAffected();
        int radius = chunksAffected * CHUNKS_AFFECTED_RADIUS_MULTIPLIER;
        toDestroyPartialChunks.clear();
        toDestroyFullChunks.clear();
        toDistortChunks.clear();

        BlockPos center = this.getBlockPos();
        for (int i = -chunksAffected; i <= chunksAffected; i++)
            for (int j = -chunksAffected; j <= chunksAffected; j++)
                for (int k = -chunksAffected; k <= chunksAffected; k++) {
                    var chunkPos = center.add(i * 16, j * 16, k * 16);
                    if (chunkPos.getSquaredDistance(center) < Math.pow(Math.max(0, 16 * (chunksAffected - 4)), 2) ||
                            !anyChunkVertexOutsideSphere(chunkPos, radius, center))
                        toDestroyFullChunks.push(chunkPos);
                    else {
                        toDistortChunks.push(chunkPos);
                        toDestroyPartialChunks.push(chunkPos);
                    }
                }

        toDestroyFullChunks.sort((blockPos1, blockPos2) -> Double.compare(
                chunkBlockPosToDis(blockPos2, this.getBlockPos()), chunkBlockPosToDis(blockPos1, this.getBlockPos())));
        toDestroyPartialChunks.sort((blockPos1, blockPos2) -> Double.compare(
                chunkBlockPosToDis(blockPos2, this.getBlockPos()), chunkBlockPosToDis(blockPos1, this.getBlockPos())));
        toDistortChunks.sort((blockPos1, blockPos2) ->
                Double.compare(blockPos2.getManhattanDistance(this.getBlockPos()), blockPos1.getManhattanDistance(this.getBlockPos())));
    }

    private void loadChunksAround(boolean load) {
        loadChunksInRadius(getEntityWorld(), this, new ChunkPos(this.getBlockPos()), load, this.getChunksAffected() + getExtraChunks());
    }

    public static void loadChunksInRadius(World level, Entity owner, ChunkPos chunkPos, boolean load, int radius) {
        if (level instanceof ServerWorld serverLevel) {
            int dist = Math.max(radius, serverLevel.getServer().getPlayerManager().getViewDistance() / 2);
            for (int i = -dist; i <= dist; ++i)
                for (int j = -dist; j <= dist; ++j)
                    ForgeChunkManager.forceChunk(serverLevel, AlexsCavesEnriched.MODID, owner, chunkPos.x + i, chunkPos.z + j, load, load);
        }
    }

    private float calculateDamage(float dist, float max) {
        float revert = (max - dist) / max;
        float baseDmg = this.getSize() <= 1.5F ? 100.0F : 100.0F + (this.getSize() - 1.5F) * 400.0F;
        return revert * baseDmg;
    }

    private final List<SmeltingRecipe> smeltingRecipes = getEntityWorld().getRecipeManager().listAllOfType(RecipeType.SMELTING);
    private final HashMap<Block, Optional<Block>> cachedSmeltingResults = new HashMap<>();

    private Optional<Block> getResultingSmeltedBlock(BlockState blockState) {
        var block = blockState.getBlock();
        if (cachedSmeltingResults.containsKey(block)) {
            return cachedSmeltingResults.get(block);
        } else {
            if (blockState.isAir()) return Optional.empty();
            for (var recipe : smeltingRecipes) {
                var ingredients = recipe.getIngredients();
                if (ingredients.size() == 1 && ingredients.get(0).test(block.asItem().getDefaultStack())) {
                    var result = Block.getBlockFromItem(recipe.getOutput(getEntityWorld().getRegistryManager()).getItem());
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
        BlockPos.Mutable carve = new BlockPos.Mutable();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                for (int y = -radius; y <= radius; y++) {
                    carve.set(blockPos.getX() + x, blockPos.getY() + y, blockPos.getZ() + z);
                    if (blockPos.getY() + y < getEntityWorld().getBottomY() || carve.getSquaredDistance(blockPos) > radiusSqr)
                        continue;
                    var current_block = getEntityWorld().getBlockState(carve);
                    var result = getResultingSmeltedBlock(current_block);
                    result.ifPresent(block -> getEntityWorld().setBlockState(carve, block.getDefaultState(), 2));
                }
            }
        }
    }

    private void tryTransmuteBlock(BlockPos blockpos) {
        var currentState = getEntityWorld().getBlockState(blockpos);
        var recipes = getEntityWorld().getRecipeManager().listAllOfType(ACERecipeRegistry.NUCLEAR_TRANSMUTATION_TYPE.get());
        for (NuclearTransmutationRecipe recipe : recipes) {
            if (recipe.matches(currentState) && (recipe.getChance() == 1.0F || getEntityWorld().getRandom().nextFloat() <= recipe.getChance())) {
                currentState = recipe.getOutput().getDefaultState();
                getEntityWorld().setBlockState(blockpos, currentState);
                break;
            }
        }
    }

    public void tryPlaceGlowingAir(BlockPos blockPos) {
//        final int EVERY = 3;
//        if (blockPos.getX() % EVERY != 0 || blockPos.getZ() % EVERY != 0 || blockPos.getY() % EVERY != 0)
//            return;
        if (getEntityWorld().getBlockState(blockPos).isAir())
            getEntityWorld().setBlockState(blockPos, ACEBlockRegistry.NUKE_GLOWING_AIR.get().getDefaultState(), 3);
    }

    private void explodeBlock(BlockPos carve) {
        BlockState state = getEntityWorld().getBlockState(carve);
        if ((!state.isAir() || !state.getFluidState().isEmpty()) && isDestroyable(state)) {
            if (state.isOf(ACBlockRegistry.TREMORZILLA_EGG.get()) && state.getBlock() instanceof TremorzillaEggBlock tremorzillaEggBlock) {
                tremorzillaEggBlock.spawnDinosaurs(getEntityWorld(), carve, state);
            } else if (AlexsCaves.COMMON_CONFIG.nukesSpawnItemDrops.get() && random.nextFloat() < this.itemDropModifier && state.getFluidState().isEmpty()) {
                getEntityWorld().breakBlock(carve, true);
            } else {
                state.onBlockExploded(getEntityWorld(), carve, dummyExplosion);
            }
        }
        if (AlexsCavesEnriched.CONFIG.nuclear.irradiateAir)
            tryPlaceGlowingAir(carve);
    }

    private void destroyFullChunk() {
        BlockPos chunkCorner = toDestroyFullChunks.pop();
        if (AlexsCaves.COMMON_CONFIG.nukeMaxBlockExplosionResistance.get() <= 0) return;

        BlockPos.Mutable carve = new BlockPos.Mutable();
        carve.set(chunkCorner);
        this.preExplodeUpdate();

        // Go slightly outside of chunk bounds so fluids don't flow back into the hole
        int maxDeltaY = Math.min(getEntityWorld().getTopY() - chunkCorner.getY(), 16);
        for (int x = -1; x < 17; x++)
            for (int z = -1; z < 17; z++)
                for (int y = maxDeltaY; y >= 0; y--) {
                    if (chunkCorner.getY() + y < getEntityWorld().getBottomY()) break;
                    carve.set(chunkCorner.getX() + x, chunkCorner.getY() + y, chunkCorner.getZ() + z);
                    this.explodeBlock(carve);
                }
    }

    private void destroyChunk(int radius) {
        BlockPos chunkCorner = toDestroyPartialChunks.pop();
        if (AlexsCaves.COMMON_CONFIG.nukeMaxBlockExplosionResistance.get() <= 0) return;

        BlockPos.Mutable carve = new BlockPos.Mutable();
        BlockPos.Mutable carveBelow = new BlockPos.Mutable();
        carve.set(chunkCorner);
        carveBelow.set(chunkCorner);
        this.preExplodeUpdate();

        int maxDeltaY = Math.min(getEntityWorld().getTopY() - chunkCorner.getY(), 16);
        for (int x = -1; x < 17; x++)
            for (int z = -1; z < 17; z++)
                for (int y = maxDeltaY; y >= 0; y--) {
                    if (chunkCorner.getY() + y < getEntityWorld().getBottomY()) break;

                    boolean canSetToFire = false;
                    carve.set(chunkCorner.getX() + x, chunkCorner.getY() + y, chunkCorner.getZ() + z);
                    double yDist = ACMath.smin(0.6F - Math.abs(this.getBlockPos().getY() - carve.getY()) / (float) radius, 0.6F, 0.2F);
                    double distToCenter = carve.getSquaredDistance(this.getBlockPos().getX(), carve.getY() - 1, this.getBlockPos().getZ());
                    double targetRadius = yDist * radius * radius;

                    if (distToCenter <= targetRadius) {
                        float widthSimplexNoise1 = (ACMath.sampleNoise3D(carve.getX(), carve.getY(), carve.getZ(), radius) - 0.5F) * 0.45F + 0.55F;
                        targetRadius += yDist * (widthSimplexNoise1 * radius) * radius;
                        if (distToCenter <= targetRadius) {
                            BlockState state = getEntityWorld().getBlockState(carve);
                            if ((!state.isAir() || !state.getFluidState().isEmpty()) && isDestroyable(state)) {
                                carveBelow.set(carve.getX(), carve.getY() - 1, carve.getZ());
                                canSetToFire = true;
                                this.explodeBlock(carve);
                            }
                        }
                    }

                    if (canSetToFire && !getEntityWorld().getBlockState(carveBelow).isAir()) {
                        if (random.nextFloat() < 0.15)
                            getEntityWorld().setBlockState(carveBelow.up(), Blocks.FIRE.getDefaultState());
                    }
                }
    }

    private void distortChunk(int radius) {
        BlockPos chunkCorner = toDistortChunks.pop();
        if (AlexsCaves.COMMON_CONFIG.nukeMaxBlockExplosionResistance.get() <= 0) return;

        BlockPos.Mutable carve = new BlockPos.Mutable();
        BlockPos.Mutable carveAbove = new BlockPos.Mutable();
        carve.set(chunkCorner);
        carveAbove.set(chunkCorner);
        double radius_2 = Math.pow(radius + 3, 2.0);

        final int MAX_SHOCKWAVE_DELTA = 5;
        int maxDeltaY = Math.min(getEntityWorld().getTopY() - chunkCorner.getY(), 16);
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                for (int y = maxDeltaY; y >= 0; y--) {
                    if (chunkCorner.getY() + y < getEntityWorld().getBottomY()) break;

                    carve.set(chunkCorner.getX() + x, chunkCorner.getY() + y, chunkCorner.getZ() + z);
                    double distToCenter = carve.getSquaredDistance(this.getBlockPos().getX(), carve.getY() - 1, this.getBlockPos().getZ());
                    if (distToCenter > radius_2) continue;

                    // Block transmutation
                    tryTransmuteBlock(carve);
                    BlockState state = getEntityWorld().getBlockState(carve);

                    // Break breakable blocks if possible
                    if (!state.isAir() && (state.isReplaceable() || state.isIn(BlockTags.LEAVES) || state.isIn(AlexsCavesEnriched.WEAK_PLANTS_TAG))) {
                        this.getEntityWorld().removeBlock(carve, true);
                        continue;
                    }
                    // Burn flammable blocks if possible
                    if (state.isFlammable(getEntityWorld(), carve, Direction.UP))
                        getEntityWorld().setBlockState(carve.up(), Blocks.FIRE.getDefaultState(), 3);

                    // Shockwave blast
                    if (Math.cos(distToCenter / 300.0) < 0) // cos(x^2)
                        continue;

                    if (!state.isAir() && !state.isReplaceable() && isDestroyable(state) &&
                            !(state.getBlock() instanceof BlockEntityProvider)) {
                        int blastHeight = 1 + (int) (getEntityWorld().getRandom().nextFloat() * (int) (Math.min(1.0, distToCenter / (radius * radius)) * MAX_SHOCKWAVE_DELTA * 2));
                        blastHeight = Math.min(MAX_SHOCKWAVE_DELTA, blastHeight);
                        carveAbove.set(carve.getX(), Math.min(carve.getY() + blastHeight, getEntityWorld().getTopY()), carve.getZ());
                        BlockState aboveState = getEntityWorld().getBlockState(carveAbove);
                        if (aboveState.isAir() || !aboveState.getFluidState().isEmpty() || aboveState.isReplaceable()) {
                            if (state.canPlaceAt(this.getEntityWorld(), carveAbove.offset(Direction.Axis.Y, 1))) {
                                this.getEntityWorld().removeBlock(carve, true);
                                this.getEntityWorld().setBlockState(carveAbove, state);
                            }
                        }
                    }
                }
    }

    private void castDamageRay(int radius) { // this radius is secondary blast radius
        if (AlexsCaves.COMMON_CONFIG.nukeMaxBlockExplosionResistance.get() <= 0) return;

        this.toDamageRays--;
        var center = new Vec3d(this.getBlockPos().getX(), this.getBlockPos().getY(), this.getBlockPos().getZ());
        var direction = new Vec3d(getEntityWorld().getRandom().nextFloat() - 0.5F, 0.0F, getEntityWorld().getRandom().nextFloat() - 0.5F).normalize();
        var ctx = new BlockStateRaycastContext(
                center.add(direction.multiply(radius - 16 * getExtraChunks())),
                center.add(direction.multiply(radius)),
                block -> false);

        AtomicInteger resistance_quota = new AtomicInteger((int)(600 * getSize() / 6.0F));
        AtomicInteger blocks_affected = new AtomicInteger(0);

        BlockView.raycast(ctx.getStart(), ctx.getEnd(), ctx.getStatePredicate(), (_ctx, pos) -> {
            final int MAX_DELTAY = 32;
            int deltaY = -MAX_DELTAY;
            while (pos.getY() - deltaY > getEntityWorld().getBottomY() && deltaY < MAX_DELTAY) {
                var state = getEntityWorld().getBlockState(pos.offset(Direction.DOWN, deltaY));
                if (!state.isAir() && isDestroyable(state))
                    break;
                deltaY++;
            }
            if (deltaY == MAX_DELTAY)
                return null;
            pos = pos.offset(Direction.DOWN, deltaY);

            BlockState state = getEntityWorld().getBlockState(pos);
            resistance_quota.addAndGet((int) -state.getBlock().getBlastResistance());
            if (resistance_quota.get() < 0 || blocks_affected.get() > getExtraChunks() * 16 + 32)
                return true;
            blocks_affected.incrementAndGet();

            // Break some weak blocks
            if (state.isReplaceable() || state.getBlock() instanceof AbstractGlassBlock || state.getBlock().getBlastResistance() <= 1.0)
                this.getEntityWorld().removeBlock(pos, true);
            // Burn flammable blocks if possible
            if (state.isFlammable(getEntityWorld(), pos, Direction.UP))
                getEntityWorld().setBlockState(pos.up(), Blocks.FIRE.getDefaultState(), 3);

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
        BlockPos.Mutable carve = new BlockPos.Mutable();
        for (int i = 0; i < 16; i++) {
            carve.set(
                    this.getX() + getEntityWorld().getRandom().nextBetween(-ACID_SCATTER_RADIUS_BLOCKS, ACID_SCATTER_RADIUS_BLOCKS),
                    this.getY(),
                    this.getZ() + getEntityWorld().getRandom().nextBetween(-ACID_SCATTER_RADIUS_BLOCKS, ACID_SCATTER_RADIUS_BLOCKS)
            );

            int deltaY = 0;
            while (this.getY() - deltaY > getEntityWorld().getBottomY() && deltaY < radius + 32) {
                var state = getEntityWorld().getBlockState(carve.offset(Direction.DOWN, deltaY));
                if (!state.isAir() && !state.isReplaceable())
                    break;
                deltaY++;
            }
            BlockPos place = carve.offset(Direction.DOWN, deltaY - 1);
            if (getEntityWorld().getDimensionKey() != DimensionTypes.THE_NETHER) {
                getEntityWorld().setBlockState(place, ACBlockRegistry.ACID.get().getDefaultState());
            } else {
                getEntityWorld().setBlockState(place, ACBlockRegistry.UNREFINED_WASTE.get().getDefaultState());
            }

            if (i == 0 && AlexsCavesEnriched.CONFIG.nuclear.irradiationTime > 0) {
                AreaEffectCloudEntity areaEffectCloudEntity = new AreaEffectCloudEntity(this.getEntityWorld(), this.getX(), this.getY() - deltaY + 1.2f, this.getZ());
                areaEffectCloudEntity.setParticleType(ACParticleRegistry.GAMMAROACH.get());
                areaEffectCloudEntity.setColor(0);
                areaEffectCloudEntity.addEffect(new StatusEffectInstance(ACEffectRegistry.IRRADIATED.get(), AlexsCavesEnriched.CONFIG.nuclear.irradiationPotionTime, 4));
                areaEffectCloudEntity.setRadius((float)radius);
                areaEffectCloudEntity.setDuration(AlexsCavesEnriched.CONFIG.nuclear.irradiationTime);
                areaEffectCloudEntity.setRadiusGrowth(-areaEffectCloudEntity.getRadius() / (float) areaEffectCloudEntity.getDuration());
                this.getEntityWorld().spawnEntity(areaEffectCloudEntity);
            }
        }
    }

    private void preExplodeUpdate() {
        this.itemDropModifier = 0.025F / Math.min(1, this.getSize());
        if (this.dummyExplosion == null)
            this.dummyExplosion = new Explosion(getEntityWorld(), null, this.getX(), this.getY(), this.getZ(), 10.0F, List.of());
    }

    private boolean isDestroyable(BlockState state) {
        return (!state.isIn(ACTagRegistry.NUKE_PROOF) && state.getBlock().getBlastResistance() < (float) AlexsCaves.COMMON_CONFIG.nukeMaxBlockExplosionResistance.get()) || state.isOf(ACBlockRegistry.TREMORZILLA_EGG.get());
    }

    public void remove(@NotNull RemovalReason removalReason) {
        if (!this.getEntityWorld().isClient && this.loadingChunks) {
            this.loadingChunks = false;
            this.loadChunksAround(false);
        }
        super.remove(removalReason);
    }

    private int getChunksAffected() {
        return (int) Math.ceil(this.getSize());
    }

    private int getExtraChunks() {
        return (int)Math.ceil(this.getSize() / AlexsCaves.COMMON_CONFIG.nukeExplosionSizeModifier.get().floatValue() * EXTRA_BLAST_RADIUS_CHUNKS);
    }

    protected void initDataTracker() {
        this.dataTracker.startTracking(SIZE, 1.0F);
        this.dataTracker.startTracking(NO_GRIEFING, false);
        this.dataTracker.startTracking(EXPLOSION_STAGE, 0);
    }

    public float getSize() {
        return this.dataTracker.get(SIZE);
    }

    public void setSize(float f) {
        this.dataTracker.set(SIZE, f);
    }

    public ExplosionState getExplosionState() { return ExplosionState.values()[this.dataTracker.get(EXPLOSION_STAGE)]; }

    public void setExplosionStage(ExplosionState state) {
        this.dataTracker.set(EXPLOSION_STAGE, state.ordinal());
    }
    public void setExplosionStage(int state) {
        this.dataTracker.set(EXPLOSION_STAGE, state < ExplosionState.values().length ? state : 0);
    }

    public boolean isNoGriefing() {
        return this.dataTracker.get(NO_GRIEFING);
    }

    public void setNoGriefing(boolean noGriefing) {
        this.dataTracker.set(NO_GRIEFING, noGriefing);
    }

    protected void readCustomDataFromNbt(NbtCompound compoundTag) {
        this.loadingChunks = compoundTag.getBoolean("WasLoadingChunks");
        this.setSize(compoundTag.getFloat("Size"));
        this.setNoGriefing(compoundTag.getBoolean("NoGriefing"));
        this.setExplosionStage(compoundTag.getInt("ExplosionStage"));
        gatherChunksToAffect();
    }

    protected void writeCustomDataToNbt(NbtCompound compoundTag) {
        compoundTag.putBoolean("WasLoadingChunks", this.loadingChunks);
        compoundTag.putFloat("Size", this.getSize());
        compoundTag.putBoolean("NoGriefing", this.isNoGriefing());
        compoundTag.putInt("ExplosionStage", this.getExplosionState().ordinal());
    }

    static {
        SIZE = DataTracker.registerData(NuclearExplosion2Entity.class, TrackedDataHandlerRegistry.FLOAT);
        NO_GRIEFING = DataTracker.registerData(NuclearExplosion2Entity.class, TrackedDataHandlerRegistry.BOOLEAN);
        EXPLOSION_STAGE = DataTracker.registerData(NuclearExplosion2Entity.class, TrackedDataHandlerRegistry.INTEGER);
    }
}
