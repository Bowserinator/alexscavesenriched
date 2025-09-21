package net.hellomouse.alexscavesenriched.entity;

import com.github.alexmodguy.alexscaves.client.ClientProxy;
import com.github.alexmodguy.alexscaves.server.misc.ACMath;
import com.github.alexmodguy.alexscaves.server.misc.ACSoundRegistry;
import com.github.alexmodguy.alexscaves.server.misc.ACTagRegistry;
import net.hellomouse.alexscavesenriched.*;
import net.hellomouse.alexscavesenriched.client.ACEClientMod;
import net.hellomouse.alexscavesenriched.client.particle.BlackHoleSmokeParticle;
import net.hellomouse.alexscavesenriched.client.sound.BlackHoleSound;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.PlayMessages;
import org.jetbrains.annotations.NotNull;

import java.util.Stack;

public class BlackHoleEntity extends Entity {
    private static final int DEFAULT_DURATION = 40 * 60;
    private static final int EXPLOSION_DURATION = 20 * 45;
    private static final float DEFAULT_SIZE = 10;

    private boolean spawnedSound = false;
    private boolean spawnedParticle = false;

    // 16^3 subchunk actually
    // full = entire subchunk scheduled to be destroyed
    // partial = would be clipped by explosion
    private final Stack<BlockPos> toDestroyFullChunks;
    private final Stack<BlockPos> toDestroyPartialChunks;
    private boolean loadingChunks;
    public enum ExplosionState {CALCULATE_WHAT_TO_DESTROY, DESTORYING, DONE}
    private ExplosionState explosionState = ExplosionState.CALCULATE_WHAT_TO_DESTROY;

    // For collecting items
    private boolean gotWaterBucket = false;
    private boolean gotLavaBucket = false;

    private static final EntityDataAccessor<Float> SIZE;
    private static final EntityDataAccessor<Float> CURRENT_SIZE;
    private static final EntityDataAccessor<Integer> DECAY_DURATION_LEFT;
    private static final EntityDataAccessor<Boolean> NONDECAY;
    private static final EntityDataAccessor<Boolean> NO_GRIEFING;
    private static final EntityDataAccessor<Boolean> EXPLODING;
    private static final EntityDataAccessor<Integer> EXPLOSION_SIZE;

    static {
        SIZE = SynchedEntityData.defineId(BlackHoleEntity.class, EntityDataSerializers.FLOAT);
        NO_GRIEFING = SynchedEntityData.defineId(BlackHoleEntity.class, EntityDataSerializers.BOOLEAN);
        CURRENT_SIZE = SynchedEntityData.defineId(BlackHoleEntity.class, EntityDataSerializers.FLOAT);
        DECAY_DURATION_LEFT = SynchedEntityData.defineId(BlackHoleEntity.class, EntityDataSerializers.INT);
        NONDECAY = SynchedEntityData.defineId(BlackHoleEntity.class, EntityDataSerializers.BOOLEAN);
        EXPLODING = SynchedEntityData.defineId(BlackHoleEntity.class, EntityDataSerializers.BOOLEAN);
        EXPLOSION_SIZE = SynchedEntityData.defineId(BlackHoleEntity.class, EntityDataSerializers.INT);
    }

    public BlackHoleEntity(EntityType<?> entityType, Level level) {
        super(entityType, level);
        this.toDestroyPartialChunks = new Stack<>();
        this.toDestroyFullChunks = new Stack<>();
        this.loadingChunks = false;
    }

    public BlackHoleEntity(PlayMessages.SpawnEntity spawnEntity, Level level) {
        this(ACEEntityRegistry.BLACK_HOLE.get(), level);
        this.setBoundingBox(this.makeBoundingBox());
    }

    public @NotNull Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    @OnlyIn(Dist.CLIENT)
    public void clientTick() {
        if (!spawnedSound) {
            spawnedSound = true;
            var sound = new BlackHoleSound(this, ACESounds.BLACKHOLE, SoundSource.AMBIENT);
            Minecraft.getInstance().getSoundManager().queueTickingSound(sound);
        }
    }

    @Override
    public void tick() {
        if (this.isExplosive()) {
            this.explodeTick();
        } else {
            this.postExplodeTick();
        }
    }

    private void suckEntity(Entity entity, float powerMultiplier) {
        if (entity instanceof Player player && ((player.isCreative() && player.getAbilities().flying) || player.isSpectator()))
            return;
        Vec3 dir = this.position().subtract(entity.position());
        double dis = Math.max(0.1, dir.length() - this.getCurrentSize());
        double suckPower = powerMultiplier * Math.min(0.5F / dis, 1F);
        suckPower = Math.min(10F, suckPower);
        entity.addDeltaMovement(dir.normalize().scale(suckPower));
    }

    @OnlyIn(Dist.CLIENT)
    public void explodeClientTick() {
        ACEClientMod.setNukeSky(ACEClientMod.NukeSkyType.BLACK_HOLE, 1F - (float) tickCount / (EXPLOSION_DURATION * 2F));
    }

    protected void explodeTick() {
        var clientSide = this.level().isClientSide;
        if (clientSide) {
            this.explodeClientTick();
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
        }

        this.setCurrentSize(Math.min(this.getCurrentSize() + 1, this.getExplosionSize() * 5));

        if (getCommandSenderWorld().isClientSide) {
            if (explosionState != ExplosionState.DONE || tickCount < EXPLOSION_DURATION - BlackHoleSmokeParticle.DEFAULT_AGE * 2) {
                for (int i = 0; i < 5; i++) {
                    Vec3 center = this.position().add(0, this.getCurrentSize() * 0.5, 0);
                    final double VEL = 7;
                    Vec3 delta = new Vec3(
                            this.level().random.nextFloat() - 0.5,
                            this.level().random.nextFloat() - 0.5,
                            this.level().random.nextFloat() - 0.5
                    ).normalize().scale(BlackHoleSmokeParticle.DEFAULT_AGE * VEL * 2 + this.getCurrentSize() * 2F);
                    Vec3 delta2 = delta.normalize().scale(-VEL * 0.5);
                    this.level().addParticle(ACEParticleRegistry.BLACK_HOLE_SMOKE.get(),
                            center.x + delta.x, center.y + delta.y, center.z + delta.z,
                            delta2.x, delta2.y, delta2.z);
                }
            }

            this.clientTick();
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
                if (toDestroyPartialChunks.isEmpty() && toDestroyFullChunks.isEmpty()) {
                    explosionState = ExplosionState.DONE;
                }
            } else if (explosionState == ExplosionState.DONE && tickCount > EXPLOSION_DURATION) {
                this.setIsExplosive(false);
            }
        }

        // Damage entities
        AABB killBox = this.getBoundingBox().inflate(0.5);
        for (var entity : this.getCommandSenderWorld().getEntitiesOfClass(Entity.class, killBox)) {
            entity.hurt(ACEDamageSources.causeBlackHoleDamage(getCommandSenderWorld().registryAccess()), 500);
            if (!(entity instanceof LivingEntity) && !(entity instanceof BlackHoleEntity))
                entity.remove(RemovalReason.KILLED);
        }

        // Suck in entities
        AABB suckBox = this.getBoundingBox().inflate(radius * 1.1F, radius * 1.1F, radius * 1.1F);
        for (var entity : this.getCommandSenderWorld().getEntitiesOfClass(Entity.class, suckBox)) {
            this.suckEntity(entity, 10F);
        }
    }

    // Explosion stuff
    // -----------------------------

    private int getChunksAffected() {
        return (int)Math.ceil(this.getExplosionSize());
    }

    protected void postExplodeTick() {
        var clientSide = this.level().isClientSide;
        if (clientSide && this.getDecayDurationLeft() > 60) {
            Vec3 center = this.getEyePosition();
            Vec3 delta = new Vec3(
                    this.level().random.nextFloat() - 0.5,
                    this.level().random.nextFloat() - 0.5,
                    this.level().random.nextFloat() - 0.5
            ).normalize().scale(1.5F + this.getCurrentSize());
            Vec3 delta2 = delta.normalize().scale(-0.15);
            this.level().addParticle(ParticleTypes.SMALL_FLAME, center.x + delta.x, center.y + delta.y, center.z + delta.z,
                    delta2.x, delta2.y, delta2.z);
            this.clientTick();
        }

        if (this.getDecayDurationLeft() > 0) {
            if (getCommandSenderWorld().getGameTime() % 2 == 0) {
                var targetSize = (this.getDecayDurationLeft() / (float) DEFAULT_DURATION) * this.getSize();
                if (targetSize < this.getCurrentSize() - 5)
                    this.setCurrentSize(this.getCurrentSize() - 1);
                else
                    this.setCurrentSize(targetSize);
            }
            if (!this.isNonDecaying())
                this.setDecayDurationLeft(this.getDecayDurationLeft() - 1);
        } else {
            if (clientSide) {
                ClientProxy.renderNukeFlashFor = 8;
            }
            Vec3 center = this.getEyePosition();
            for (int i = 0; i < 20; i++) {
                Vec3 delta = new Vec3(
                        this.level().random.nextFloat() - 0.5,
                        this.level().random.nextFloat() - 0.5,
                        this.level().random.nextFloat() - 0.5
                ).normalize().scale(1.5F);
                Vec3 delta2 = delta.normalize().scale(-0.3);
                this.level().addParticle(ParticleTypes.LARGE_SMOKE, center.x, center.y, center.z,
                        delta2.x, delta2.y, delta2.z);
            }
            this.getCommandSenderWorld().explode(null, this.getX(), this.getY(), this.getZ(), 4, Level.ExplosionInteraction.TNT);
            this.remove(RemovalReason.DISCARDED);
            return;
        }

        AABB suckBox = this.getBoundingBox().inflate(20F);
        for (var entity : this.level().getEntities(this, suckBox)) {
            this.suckEntity(entity, 1.8F);
        }

        AABB killBox = this.getBoundingBox().inflate(0.5F);
        for (var entity : this.level().getEntities(this, killBox)) {
            if (entity instanceof Player player && player.isCreative())
                continue;
            if (entity instanceof ItemEntity item) {
                if (item.getItem().is(Items.WATER_BUCKET))
                    gotWaterBucket = true;
                else if (item.getItem().is(Items.LAVA_BUCKET))
                    gotLavaBucket = true;
                else if (item.getItem().is(Items.PHANTOM_MEMBRANE)) {
                    this.setNonDecaying(false);
                    gotWaterBucket = gotLavaBucket = false;
                }

                if (gotWaterBucket && gotLavaBucket)
                    this.setNonDecaying(true);
            }
            entity.setDeltaMovement(0, 0, 0);
            entity.hurt(ACEDamageSources.causeBlackHoleDamage(getCommandSenderWorld().registryAccess()), 500);
            if (!(entity instanceof LivingEntity) && !(entity instanceof BlackHoleEntity))
                entity.remove(RemovalReason.KILLED);
        }
    }

    private void destroyChunk(int radius, Stack<BlockPos> stack, boolean checkSphere) {
        BlockPos chunkCorner = stack.pop();
        BlockPos.MutableBlockPos carve = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos carveBelow = new BlockPos.MutableBlockPos();
        carve.set(chunkCorner);
        carveBelow.set(chunkCorner);

        int maxDeltaY = Math.min(getCommandSenderWorld().getMaxBuildHeight() - chunkCorner.getY(), 17);
        for (int x = -1; x < 17; x++)
            for (int z = -1; z < 17; z++)
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
                    if ((!state.isAir() || !state.getFluidState().isEmpty()) && isDestroyable(state))
                        getCommandSenderWorld().setBlockAndUpdate(carve, Blocks.AIR.defaultBlockState());
                }
    }

    private boolean isDestroyable(BlockState state) {
        return !state.is(ACTagRegistry.NUKE_PROOF); // && state.getBlock().getBlastResistance() < (float) AlexsCaves.COMMON_CONFIG.nukeMaxBlockExplosionResistance.get());
    }

    public void remove(@NotNull RemovalReason removalReason) {
        if (!this.getCommandSenderWorld().isClientSide && this.loadingChunks) {
            this.loadingChunks = false;
            this.loadChunksAround(false);
        }
        super.remove(removalReason);
    }

    // Black hole stuff
    // -----------------------------

    private void loadChunksAround(boolean load) {
        NuclearExplosion2Entity.loadChunksInRadius(getCommandSenderWorld(), this, new ChunkPos(this.blockPosition()), load, this.getChunksAffected());
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        double d0 = this.getBoundingBox().getSize() + this.getCurrentSize();
        if (Double.isNaN(d0)) d0 = 1.0;
        d0 *= 64.0;
        return distance < d0 * d0;
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        return this.getBoundingBox().inflate(this.getCurrentSize() * 2);
    }

    @Override
    protected AABB makeBoundingBox() {
        return this.getDimensions(null).scale(this.getCurrentSize()).makeBoundingBox(this.position());
    }

    protected void defineSynchedData() {
        this.entityData.define(SIZE, DEFAULT_SIZE);
        this.entityData.define(NO_GRIEFING, false);

        this.entityData.define(CURRENT_SIZE, DEFAULT_SIZE);
        this.entityData.define(DECAY_DURATION_LEFT, DEFAULT_DURATION);
        this.entityData.define(NONDECAY, false);
        this.entityData.define(EXPLOSION_SIZE, AlexsCavesEnriched.CONFIG.blackHole.radius);
        this.entityData.define(EXPLODING, false);
    }

    public float getSize() {
        return this.entityData.get(SIZE);
    }

    public void setSize(float f) {
        this.setCurrentSize(f);
        this.entityData.set(SIZE, f);
        this.setBoundingBox(this.makeBoundingBox());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag compoundTag) {
        this.setDecayDurationLeft(compoundTag.getInt("DecayDurationLeft"));
        this.setNonDecaying(compoundTag.getBoolean("NonDecaying"));
        this.setCurrentSize(compoundTag.getFloat("CurrentSize"));
        this.setSize(compoundTag.getFloat("Size"));
        this.setExplosionSize(compoundTag.getInt("ExplosionSize"));
        this.setIsExplosive(compoundTag.getBoolean("IsExplosive"));
        this.loadingChunks = compoundTag.getBoolean("WasLoadingChunks");

        if (this.isExplosive())
            explosionState = ExplosionState.CALCULATE_WHAT_TO_DESTROY; // Reset calculation on reload
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag compoundTag) {
        compoundTag.putInt("DecayDurationLeft", this.getDecayDurationLeft());
        compoundTag.putBoolean("NonDecaying", this.isNonDecaying());
        compoundTag.putFloat("CurrentSize", this.getCurrentSize());
        compoundTag.putFloat("Size", this.getSize());
        compoundTag.putBoolean("IsExplosive", this.isExplosive());
        compoundTag.putInt("ExplosionSize", this.getExplosionSize());
        compoundTag.putBoolean("WasLoadingChunks", this.loadingChunks);
    }

    public int getDecayDurationLeft() {
        return this.entityData.get(DECAY_DURATION_LEFT);
    }

    public void setDecayDurationLeft(int decayDurationLeft) {
        this.entityData.set(DECAY_DURATION_LEFT, decayDurationLeft);
    }

    public float getCurrentSize() {
        return this.entityData.get(CURRENT_SIZE);
    }

    public void setCurrentSize(float currentSize) {
        this.entityData.set(CURRENT_SIZE, currentSize);
    }

    public boolean isNonDecaying() {
        return this.entityData.get(NONDECAY);
    }

    public void setNonDecaying(boolean nonDecaying) {
        this.entityData.set(NONDECAY, nonDecaying);
    }

    public boolean isNoGriefing() {
        return this.entityData.get(NO_GRIEFING);
    }

    public void setNoGriefing(boolean noGriefing) {
        this.entityData.set(NO_GRIEFING, noGriefing);
    }

    public int getExplosionSize() {
        return this.entityData.get(EXPLOSION_SIZE);
    }

    public void setExplosionSize(int currentSize) {
        this.entityData.set(EXPLOSION_SIZE, currentSize);
    }

    public boolean isExplosive() {
        return this.entityData.get(EXPLODING);
    }

    public void setIsExplosive(boolean t) {
        this.entityData.set(EXPLODING, t);
    }
}
