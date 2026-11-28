package io.canvasmc.canvas.threadedregions.sharding;

import io.papermc.paper.configuration.GlobalConfiguration;
import io.papermc.paper.threadedregions.EntityScheduler;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import io.papermc.paper.threadedregions.TickRegions;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * Experimental intra-region worker pool. Chunk and region ownership remain
 * unchanged; workers carry the parent region/world context.
 */
public final class RegionShardExecutor {

    private static final RegionShardExecutor INSTANCE = new RegionShardExecutor();
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private final ExecutorService executor = Executors.newFixedThreadPool(8, runnable -> {
        final TickRegionScheduler.ShardTickThread thread =
            new TickRegionScheduler.ShardTickThread(runnable, "Canvas Region Shard Worker #" + THREAD_IDS.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private RegionShardExecutor() {
    }

    public static RegionShardExecutor getInstance() {
        return INSTANCE;
    }

    public record Shard(int index, int minSectionX, int maxSectionX, int minSectionZ, int maxSectionZ) {
        public boolean contains(final int sectionX, final int sectionZ) {
            return sectionX >= this.minSectionX && sectionX <= this.maxSectionX
                && sectionZ >= this.minSectionZ && sectionZ <= this.maxSectionZ;
        }
    }

    public record EntitySnapshotEntry(net.minecraft.world.entity.Entity entity, net.minecraft.world.phys.AABB bounds, boolean removed) {}

    public record EntityQuerySnapshot(Level world, EntitySnapshotEntry[] entries) {}

    public record TickSnapshot(Level world, EntityQuerySnapshot entities, RegionShardSnapshot metadata) {}

    public static void beginTick(final RegionizedWorldData worldData) {
        worldData.canvas$shardSnapshot = null;
        worldData.canvas$shardSnapshotActive = true;
    }

    public static void endTick(final RegionizedWorldData worldData) {
        worldData.canvas$shardSnapshotActive = false;
        worldData.canvas$shardSnapshot = null;
    }

    private static TickSnapshot captureTickSnapshot(final RegionizedWorldData worldData) {
        if (isShardWorker() || TickRegionScheduler.getCurrentRegionizedWorldData() != worldData) {
            throw new IllegalStateException("Shard snapshots must be captured on the owning parent region thread");
        }
        final net.minecraft.world.entity.Entity[] locals = worldData.getLocalEntitiesCopy();
        final EntitySnapshotEntry[] entries = new EntitySnapshotEntry[locals.length];
        for (int i = 0; i < locals.length; i++) {
            final net.minecraft.world.entity.Entity entity = locals[i];
            entries[i] = new EntitySnapshotEntry(entity, entity.getBoundingBox(), entity.isRemoved());
        }
        final RegionShardSnapshot metadata = GlobalConfiguration.get().threadedRegions.shardMetadataSnapshots
            ? RegionShardSnapshot.capture(worldData) : null;
        return new TickSnapshot(worldData.world, new EntityQuerySnapshot(worldData.world, entries), metadata);
    }

    public static boolean canQueryEntities(final Level world, final AABB box) {
        final Thread thread = Thread.currentThread();
        return thread instanceof TickRegionScheduler.ShardTickThread shardThread
            && shardThread.currentEntitySnapshot != null
            && shardThread.currentEntitySnapshot.world() == world;
    }

    public static boolean queryEntities(
        final Level world,
        final net.minecraft.world.entity.Entity except,
        final AABB box,
        final Predicate<? super net.minecraft.world.entity.Entity> predicate,
        final List<?> into,
        final int maxCount
    ) {
        final Thread thread = Thread.currentThread();
        if (!(thread instanceof TickRegionScheduler.ShardTickThread shardThread)) return false;
        final EntityQuerySnapshot snapshot = shardThread.currentEntitySnapshot;
        if (snapshot == null || snapshot.world() != world) return false;
        for (final EntitySnapshotEntry entry : snapshot.entries()) {
            if (into.size() >= maxCount) break;
            final net.minecraft.world.entity.Entity entity = entry.entity();
            if (entity == except || entity.isRemoved() || entry.removed() || !entry.bounds().intersects(box)) continue;
            if (predicate != null && !predicate.test(entity)) continue;
            @SuppressWarnings("unchecked")
            final List<Object> output = (List<Object>)into;
            output.add(entity);
        }
        return true;
    }

    public static boolean isEntityInCurrentShard(final net.minecraft.world.entity.Entity entity) {
        final Thread thread = Thread.currentThread();
        if (!(thread instanceof TickRegionScheduler.ShardTickThread)) return true;
        final TickRegionScheduler.ShardTickThread shardThread = (TickRegionScheduler.ShardTickThread)thread;
        if (shardThread.currentShard == null || entity == null) return true;
        final net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel)entity.level();
        if (shardThread.currentTickingWorldRegionizedData == null
            || shardThread.currentTickingWorldRegionizedData.world != level
            || shardThread.currentTickingRegion == null
            || level.regioniser.getRegionAtUnsynchronised(entity.chunkPosition().x(), entity.chunkPosition().z())
                != shardThread.currentTickingRegion) return false;
        final int shift = level.regioniser.sectionChunkShift;
        return shardThread.currentShard.contains(entity.chunkPosition().x() >> shift, entity.chunkPosition().z() >> shift);
    }

    public static boolean canQueryBlockEntities(final Level world) {
        final RegionShardSnapshot snapshot = getCurrentMetadataSnapshot();
        return snapshot != null && snapshot.world() == world;
    }

    public static RegionShardSnapshot.BlockEntityEntry canvas$getBlockEntitySnapshot(final Level world, final net.minecraft.core.BlockPos pos) {
        return canQueryBlockEntities(world) ? getCurrentMetadataSnapshot().getBlockEntity(pos) : null;
    }

    public static boolean canQueryPois(final Level world) {
        final RegionShardSnapshot snapshot = getCurrentMetadataSnapshot();
        return snapshot != null && snapshot.world() == world
            && snapshot.pois() != null && snapshot.pois().world() == world;
    }

    /**
     * Returns the captured POI at {@code pos}, or null when the captured
     * section is known to have no POI there. The lookup throws when the
     * snapshot does not cover the section so that shard workers never fall
     * back to loading live POI data.
     */
    public static RegionShardSnapshot.PoiEntry canvas$getPoiSnapshot(final Level world, final net.minecraft.core.BlockPos pos) {
        if (!canQueryPois(world)) {
            ensureSerialPoiAccess("POI point lookup");
            return null;
        }
        return getCurrentMetadataSnapshot().pois().getPoi(pos);
    }

    /** Rejects live POI access on shard workers; parent region behavior is unchanged. */
    public static void ensureSerialPoiAccess(final String operation) {
        if (isShardWorker()) {
            throw new IllegalStateException("Shard workers must not access live POI state: " + operation);
        }
    }

    public static void ensureSerialPoiAccess() {
        ensureSerialPoiAccess("live POI access");
    }

    public static RegionShardSnapshot getCurrentMetadataSnapshot() {
        final Thread thread = Thread.currentThread();
        return thread instanceof TickRegionScheduler.ShardTickThread shardThread
            ? shardThread.currentMetadataSnapshot : null;
    }

    public static boolean isShardWorker() {
        return Thread.currentThread() instanceof TickRegionScheduler.ShardTickThread;
    }

    public static void queueEntityLookupCallback(final Runnable callback) {
        final Thread thread = Thread.currentThread();
        if (!(thread instanceof TickRegionScheduler.ShardTickThread shardThread)) {
            throw new IllegalStateException("Entity lookup callbacks may only be queued by shard workers");
        }
        if (shardThread.currentTickingWorldRegionizedData == null) {
            throw new IllegalStateException("Shard worker has no owning region for entity lookup callback");
        }
        shardThread.currentTickingWorldRegionizedData.canvas$entityLookupCallbacks.add(callback);
    }

    public static void rejectShardWorkerEntityAdd(final net.minecraft.world.entity.Entity entity) {
        if (isShardWorker()) {
            throw new IllegalStateException("Shard workers must not add entities directly: " + entity.getUUID());
        }
    }

    private static void drainEntityLookupCallbacks(final RegionizedWorldData worldData) {
        if (isShardWorker() || TickRegionScheduler.getCurrentRegionizedWorldData() != worldData) {
            throw new IllegalStateException("Entity lookup callbacks must drain on the owning parent region thread");
        }
        Throwable failure = null;
        Runnable callback;
        while ((callback = worldData.canvas$entityLookupCallbacks.poll()) != null) {
            try {
                callback.run();
            } catch (final Throwable callbackFailure) {
                if (failure == null) {
                    failure = callbackFailure;
                } else {
                    failure.addSuppressed(callbackFailure);
                }
            }
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new RuntimeException("Entity lookup callback failed", failure);
    }

    public static boolean isChunkCrossShardLiveAccess(final Level world, final int chunkX, final int chunkZ) {
        final Thread thread = Thread.currentThread();
        if (!(thread instanceof TickRegionScheduler.ShardTickThread shardThread)) return false;
        if (!(world instanceof net.minecraft.server.level.ServerLevel level)
            || shardThread.currentTickingRegion == null
            || shardThread.currentTickingWorldRegionizedData == null
            || shardThread.currentShard == null
            || shardThread.currentTickingWorldRegionizedData.world != world) return true;
        if (level.regioniser.getRegionAtUnsynchronised(chunkX, chunkZ) != shardThread.currentTickingRegion) return true;
        final int shift = level.regioniser.sectionChunkShift;
        return !shardThread.currentShard.contains(chunkX >> shift, chunkZ >> shift);
    }

    public static boolean isCrossShardLiveAccess(final Level world, final net.minecraft.core.BlockPos pos) {
        return isChunkCrossShardLiveAccess(world, pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * Runs an action immediately when it belongs to this shard, otherwise queues
     * it on the parent region. Workers must never wait for the queued result.
     *
     * @return true when the action ran synchronously; false when it was queued
     */
    public static boolean runOrRoute(final Level world, final net.minecraft.core.BlockPos pos, final Runnable action) {
        if (!(world instanceof net.minecraft.server.level.ServerLevel level)) {
            throw new IllegalArgumentException("Region routing requires ServerLevel");
        }
        if (!isShardWorker()) {
            if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThread()) {
                throw new IllegalStateException("Region routing requires a tick thread");
            }
            if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(world, pos)) {
                action.run();
                return true;
            }
        } else {
            final TickRegionScheduler.ShardTickThread shardThread =
                (TickRegionScheduler.ShardTickThread)Thread.currentThread();
            if (shardThread.currentTickingWorldRegionizedData != null
                && shardThread.currentTickingWorldRegionizedData.world != world) {
                throw new IllegalStateException("Cannot route a shard mutation to another world");
            }
            if (!isCrossShardLiveAccess(world, pos)) {
                action.run();
                return true;
            }
        }
        final net.minecraft.core.BlockPos routePos = pos.immutable();
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(
            level, routePos.getX() >> 4, routePos.getZ() >> 4,
            () -> {
                if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(level, routePos)) action.run();
            });
        return false;
    }

    public static List<Shard> createShards(
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region,
        final int requested
    ) {
        final int[] bounds = region.regioniser.getSectionBounds(region);
        return createShards(bounds[0], bounds[1], bounds[2], bounds[3], requested);
    }

    static List<Shard> createShards(final int minX, final int minZ, final int maxX, final int maxZ, final int requested) {
        final int target = Math.max(2, Math.min(8, requested));
        final int spanX = Math.max(1, maxX - minX + 1);
        final int spanZ = Math.max(1, maxZ - minZ + 1);
        int columns = 1;
        int rows = 1;
        int bestCapacity = 0;
        int bestBalance = Integer.MAX_VALUE;
        for (int candidateColumns = 1; candidateColumns <= Math.min(target, spanX); candidateColumns++) {
            final int candidateRows = Math.min(spanZ, (target + candidateColumns - 1) / candidateColumns);
            final int capacity = candidateColumns * candidateRows;
            if (capacity > target) continue;
            final int balance = Math.abs(candidateColumns - candidateRows);
            if (capacity > bestCapacity || capacity == bestCapacity && balance < bestBalance) {
                columns = candidateColumns;
                rows = candidateRows;
                bestCapacity = capacity;
                bestBalance = balance;
            }
        }
        final int shardCount = columns * rows;
        final List<Shard> shards = new ArrayList<>(shardCount);
        for (int i = 0; i < shardCount; i++) {
            final int row = i / columns;
            final int column = i % columns;
            final int x0 = minX + (column * spanX) / columns;
            final int x1 = minX + ((column + 1) * spanX) / columns - 1;
            final int z0 = minZ + (row * spanZ) / rows;
            final int z1 = minZ + ((row + 1) * spanZ) / rows - 1;
            shards.add(new Shard(i, x0, Math.max(x0, x1), z0, Math.max(z0, z1)));
        }
        return shards;
    }

    /** @return true when executed by the shard pool, false for the serial fallback */
    public boolean tickEntitySchedulers(
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region,
        final RegionizedWorldData worldData,
        final EntityScheduler[] schedulers,
        final int requestedShards,
        final int threshold
    ) {
        if (region == null || requestedShards <= 1 || schedulers.length < Math.max(1, threshold)) return false;
        final List<Shard> shards = createShards(region, requestedShards);
        @SuppressWarnings("unchecked")
        final List<Runnable>[] partitions = new List[shards.size()];
        for (int i = 0; i < partitions.length; i++) partitions[i] = new ArrayList<>();
        final int shift = region.regioniser.sectionChunkShift;
        for (final EntityScheduler scheduler : schedulers) {
            final net.minecraft.world.entity.Entity handle = scheduler.entity.getHandleRaw();
            if (handle == null) continue;
            final int sectionX = handle.getBlockX() >> 4 >> shift;
            final int sectionZ = handle.getBlockZ() >> 4 >> shift;
            final int shard = shardIndex(shards, sectionX, sectionZ);
            if (shard < 0) return false;
            partitions[shard].add(() -> runScheduler(scheduler));
        }
        return runPartitions(region, worldData, shards, partitions);
    }

    /** Runs a per-entity phase, for example checkDespawn, without moving ownership. */
    public boolean runEntityTasks(
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region,
        final RegionizedWorldData worldData,
        final net.minecraft.world.entity.Entity[] entities,
        final int requestedShards,
        final int threshold,
        final Consumer<net.minecraft.world.entity.Entity> task
    ) {
        if (region == null || requestedShards <= 1 || entities.length < Math.max(1, threshold)) return false;
        final List<Shard> shards = createShards(region, requestedShards);
        @SuppressWarnings("unchecked")
        final List<Runnable>[] partitions = new List[shards.size()];
        for (int i = 0; i < partitions.length; i++) partitions[i] = new ArrayList<>();
        final int shift = region.regioniser.sectionChunkShift;
        for (final net.minecraft.world.entity.Entity entity : entities) {
            if (entity == null) continue;
            final int sectionX = entity.getBlockX() >> 4 >> shift;
            final int sectionZ = entity.getBlockZ() >> 4 >> shift;
            final int shard = shardIndex(shards, sectionX, sectionZ);
            if (shard < 0) return false;
            partitions[shard].add(() -> task.accept(entity));
        }
        return runPartitions(region, worldData, shards, partitions);
    }

    /**
     * Ticks a restricted full-tick subset: item entities without vehicles or
     * passengers whose predicted movement remains inside one shard. Returns the
     * entities actually dispatched so the serial loop can skip them.
     */
    public Set<net.minecraft.world.entity.Entity> runItemEntityTicks(
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region,
        final RegionizedWorldData worldData,
        final net.minecraft.world.entity.Entity[] entities,
        final int requestedShards,
        final int threshold,
        final Predicate<net.minecraft.world.entity.Entity> canTick,
        final Consumer<net.minecraft.world.entity.Entity> tick
    ) {
        final Set<net.minecraft.world.entity.Entity> processed = ConcurrentHashMap.newKeySet();
        if (region == null || requestedShards <= 1 || entities.length < Math.max(1, threshold)
            || !GlobalConfiguration.get().threadedRegions.shardStrictChecks) return processed;
        final List<Shard> shards = createShards(region, requestedShards);
        @SuppressWarnings("unchecked")
        final List<Runnable>[] partitions = new List[shards.size()];
        for (int i = 0; i < partitions.length; i++) partitions[i] = new ArrayList<>();
        final int shift = region.regioniser.sectionChunkShift;
        for (final net.minecraft.world.entity.Entity entity : entities) {
            if (!(entity instanceof net.minecraft.world.entity.item.ItemEntity) || entity.isRemoved()
                || entity.portalProcess != null || entity.noPhysics
                || !entityChunksFull((net.minecraft.server.level.ServerLevel)worldData.world,
                    entity.getBoundingBox(), entity.getDeltaMovement())
                || entity.getVehicle() != null || !entity.getPassengers().isEmpty()
                || !worldData.hasEntityTickingEntity(entity) || !canTick.test(entity)) continue;
            final AABB swept = entity.getBoundingBox().move(entity.getDeltaMovement()).inflate(1.0);
            final int minX = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkCoordinate(swept.minX) >> shift;
            final int maxX = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkCoordinate(swept.maxX) >> shift;
            final int minZ = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkCoordinate(swept.minZ) >> shift;
            final int maxZ = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkCoordinate(swept.maxZ) >> shift;
            final int currentShard = shardIndex(shards, entity.chunkPosition().x() >> shift, entity.chunkPosition().z() >> shift);
            if (currentShard < 0 || currentShard != shardIndex(shards, minX, minZ)
                || currentShard != shardIndex(shards, maxX, maxZ)) continue;
            partitions[currentShard].add(() -> {
                if (entity.isRemoved() || entity.portalProcess != null || entity.noPhysics
                    || entity.getVehicle() != null || !entity.getPassengers().isEmpty()
                    || !worldData.hasEntityTickingEntity(entity) || !canTick.test(entity)
                    || !isEntityInCurrentShard(entity)) return;
                tick.accept(entity);
                processed.add(entity);
            });
        }
        if (!processed.isEmpty() || hasTasks(partitions)) runPartitions(region, worldData, shards, partitions);
        return processed;
    }

    private static boolean hasTasks(final List<Runnable>[] partitions) {
        for (final List<Runnable> partition : partitions) {
            if (!partition.isEmpty()) return true;
        }
        return false;
    }

    /**
     * Ticks a conservative ExperienceOrb subset. Returns exactly the entities
     * dispatched so ServerLevel's serial loop can skip them.
     *
     * <p>In addition to the item-entity rules, XP requires FULL chunks across
     * its movement sweep, no nearby player/lava/water/collision state, merge
     * query containment inside one shard, and a merge-neighbour fixed point.
     * Those checks prevent worker-side chunk loading and cross-shard or
     * non-ticking merge targets.</p>
     */
    public Set<net.minecraft.world.entity.Entity> runExperienceOrbTicks(
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region,
        final RegionizedWorldData worldData,
        final net.minecraft.world.entity.Entity[] entities,
        final int requestedShards,
        final int threshold,
        final Predicate<net.minecraft.world.entity.Entity> canTick,
        final Consumer<net.minecraft.world.entity.Entity> tick
    ) {
        final Set<net.minecraft.world.entity.Entity> processed = ConcurrentHashMap.newKeySet();
        if (region == null || requestedShards <= 1 || entities.length < Math.max(1, threshold)
            || !GlobalConfiguration.get().threadedRegions.shardStrictChecks) return processed;

        final net.minecraft.world.level.Level level = worldData.world;
        final net.minecraft.server.level.ServerLevel serverLevel = (net.minecraft.server.level.ServerLevel)level;
        final List<Shard> shards = createShards(region, requestedShards);
        @SuppressWarnings("unchecked")
        final List<Runnable>[] partitions = new List[shards.size()];
        for (int i = 0; i < partitions.length; i++) partitions[i] = new ArrayList<>();
        final int shift = region.regioniser.sectionChunkShift;

        final EntitySnapshotEntry[] snapshot = new EntitySnapshotEntry[entities.length];
        int snapshotLength = 0;
        for (final net.minecraft.world.entity.Entity entity : entities) {
            if (entity == null) continue;
            snapshot[snapshotLength++] = new EntitySnapshotEntry(
                entity, entity.getBoundingBox(), entity.isRemoved()
            );
        }

        final IdentityHashMap<net.minecraft.world.entity.ExperienceOrb, ExperienceOrbShardCandidate> candidates =
            new IdentityHashMap<>();
        for (int i = 0; i < snapshotLength; i++) {
            final EntitySnapshotEntry entry = snapshot[i];
            if (!(entry.entity() instanceof net.minecraft.world.entity.ExperienceOrb orb)) continue;
            if (entry.removed() || !orb.isAlive() || !worldData.hasEntityTickingEntity(orb)
                || orb.portalProcess != null || orb.noPhysics
                || orb.getVehicle() != null || !orb.getPassengers().isEmpty() || !canTick.test(orb)) continue;

            final net.minecraft.world.phys.AABB currentBox = orb.getBoundingBox();
            final net.minecraft.world.phys.Vec3 delta = orb.getDeltaMovement();
            if (!entityChunksFull(serverLevel, currentBox, delta)
                || experienceOrbHasNearbyPlayer(orb, snapshot, snapshotLength)
                || orb.isEyeInFluid(net.minecraft.tags.FluidTags.WATER)
                || orb.isInLava()
                || !level.noCollision(currentBox)) continue;

            final int currentShard = shardIndex(shards,
                ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkX(orb.position()) >> shift,
                ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkZ(orb.position()) >> shift);
            final int predictedShard = shardIndex(shards,
                ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkCoordinate(orb.getX() + delta.x) >> shift,
                ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkCoordinate(orb.getZ() + delta.z) >> shift);
            // ExperienceOrb.scanForMerges uses a 0.9 block query with fast orbs
            // and 0.5 otherwise; using the smaller box here would let a worker
            // merge an orb that was never admitted to its partition.
            final double mergeRadius = serverLevel.canvasConfig().entities.fastOrbs ? 0.9 : 0.5;
            final net.minecraft.world.phys.AABB mergeBox = currentBox.inflate(mergeRadius + XP_SHARD_SAFETY_MARGIN);
            final net.minecraft.world.phys.AABB predictedMergeBox =
                currentBox.move(delta).inflate(mergeRadius + XP_SHARD_SAFETY_MARGIN);
            if (currentShard < 0 || predictedShard < 0 || currentShard != predictedShard
                || !experienceOrbWithinShard(shards.get(currentShard), mergeBox, shift)
                || !experienceOrbWithinShard(shards.get(currentShard), predictedMergeBox, shift)
                || !experienceOrbWithinShard(shards.get(currentShard),
                    currentBox.inflate(experienceOrbPlayerFollowRadius(orb)), shift)
                || !experienceOrbWithinShard(shards.get(currentShard),
                    currentBox.move(delta).inflate(experienceOrbPlayerFollowRadius(orb)), shift)) continue;
            candidates.put(orb, new ExperienceOrbShardCandidate(orb, currentShard, mergeBox));
        }

        // If A's merge query can see B, B must also be admitted to the same
        // partition. Iterate because rejecting B can in turn invalidate A.
        boolean changed;
        do {
            changed = false;
            for (final ExperienceOrbShardCandidate candidate : candidates.values()) {
                if (!candidate.eligible || !experienceOrbScansForMerges(candidate.orb)) continue;
                for (int i = 0; i < snapshotLength; i++) {
                    final EntitySnapshotEntry entry = snapshot[i];
                    if (!(entry.entity() instanceof net.minecraft.world.entity.ExperienceOrb other)
                        || other == candidate.orb || entry.removed()
                        || !entry.bounds().intersects(candidate.mergeBox)) continue;
                    final ExperienceOrbShardCandidate target = candidates.get(other);
                    if (target == null || !target.eligible || target.shard != candidate.shard) {
                        candidate.eligible = false;
                        changed = true;
                        break;
                    }
                }
            }
        } while (changed);

        for (final ExperienceOrbShardCandidate candidate : candidates.values()) {
            if (!candidate.eligible) continue;
            final net.minecraft.world.entity.ExperienceOrb orb = candidate.orb;
            partitions[candidate.shard].add(() -> {
                // A previous merge in this partition may have removed the orb.
                if (orb.isRemoved() || orb.getVehicle() != null || !orb.getPassengers().isEmpty()
                    || orb.portalProcess != null || orb.noPhysics
                    || !worldData.hasEntityTickingEntity(orb) || !canTick.test(orb)
                    || !isEntityInCurrentShard(orb)) return;
                tick.accept(orb);
                processed.add(orb);
            });
        }
        if (hasTasks(partitions)) runPartitions(region, worldData, shards, partitions);
        return processed;
    }

    private static final double XP_SHARD_SAFETY_MARGIN = 0.25;
    private static final double XP_PLAYER_FOLLOW_RADIUS = 8.0;

    private static final class ExperienceOrbShardCandidate {
        final net.minecraft.world.entity.ExperienceOrb orb;
        final int shard;
        final net.minecraft.world.phys.AABB mergeBox;
        boolean eligible = true;

        ExperienceOrbShardCandidate(
            final net.minecraft.world.entity.ExperienceOrb orb,
            final int shard,
            final net.minecraft.world.phys.AABB mergeBox
        ) {
            this.orb = orb;
            this.shard = shard;
            this.mergeBox = mergeBox;
        }
    }

    private static boolean entityChunksFull(
        final net.minecraft.server.level.ServerLevel level,
        final net.minecraft.world.phys.AABB currentBox,
        final net.minecraft.world.phys.Vec3 delta
    ) {
        if (Math.abs(delta.x) > 8.0 || Math.abs(delta.y) > 8.0 || Math.abs(delta.z) > 8.0) return false;
        final net.minecraft.world.phys.AABB predicted = currentBox.move(delta);
        final double margin = 1.0;
        final int minChunkX = (int)Math.floor(Math.min(currentBox.minX, predicted.minX) - margin) >> 4;
        final int maxChunkX = (int)Math.floor(Math.max(currentBox.maxX, predicted.maxX) + margin) >> 4;
        final int minChunkZ = (int)Math.floor(Math.min(currentBox.minZ, predicted.minZ) - margin) >> 4;
        final int maxChunkZ = (int)Math.floor(Math.max(currentBox.maxZ, predicted.maxZ) + margin) >> 4;
        if (maxChunkX - minChunkX > 16 || maxChunkZ - minChunkZ > 16) return false;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                // Non-loading: reject rather than synchronously loading POI/chunk
                // data from a ShardTickThread during ExperienceOrb.tick().
                final net.minecraft.world.level.chunk.ChunkAccess chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null || !(chunk instanceof net.minecraft.world.level.chunk.LevelChunk levelChunk)
                    || !levelChunk.getFullStatus().isOrAfter(net.minecraft.server.level.FullChunkStatus.FULL)) return false;
            }
        }
        return true;
    }

    private static double experienceOrbPlayerFollowRadius(final net.minecraft.world.entity.ExperienceOrb orb) {
        final net.minecraft.world.phys.Vec3 delta = orb.getDeltaMovement();
        final double speed = Math.abs(delta.x) + Math.abs(delta.z);
        return XP_PLAYER_FOLLOW_RADIUS + speed + XP_SHARD_SAFETY_MARGIN + 1.0;
    }

    private static boolean experienceOrbHasNearbyPlayer(
        final net.minecraft.world.entity.ExperienceOrb orb,
        final EntitySnapshotEntry[] snapshot,
        final int snapshotLength
    ) {
        final net.minecraft.world.phys.Vec3 delta = orb.getDeltaMovement();
        final double radius = experienceOrbPlayerFollowRadius(orb);
        final net.minecraft.world.phys.AABB current = orb.getBoundingBox().inflate(radius);
        final net.minecraft.world.phys.AABB predicted = orb.getBoundingBox().move(delta).inflate(radius);
        for (int i = 0; i < snapshotLength; i++) {
            final EntitySnapshotEntry entry = snapshot[i];
            if (entry.entity() instanceof net.minecraft.world.entity.player.Player && !entry.removed()
                && (entry.bounds().intersects(current) || entry.bounds().intersects(predicted))) return true;
        }
        return false;
    }

    private static boolean experienceOrbScansForMerges(final net.minecraft.world.entity.ExperienceOrb orb) {
        return orb.tickCount % 20 == 1;
    }

    private static boolean experienceOrbWithinShard(
        final Shard shard,
        final net.minecraft.world.phys.AABB box,
        final int shift
    ) {
        final int minX = (int)Math.floor(box.minX) >> 4 >> shift;
        final int maxX = (int)Math.floor(box.maxX) >> 4 >> shift;
        final int minZ = (int)Math.floor(box.minZ) >> 4 >> shift;
        final int maxZ = (int)Math.floor(box.maxZ) >> 4 >> shift;
        return shard.contains(minX, minZ) && shard.contains(minX, maxZ)
            && shard.contains(maxX, minZ) && shard.contains(maxX, maxZ);
    }

    static int shardIndex(final List<Shard> shards, final int sectionX, final int sectionZ) {
        for (final Shard shard : shards) {
            if (shard.contains(sectionX, sectionZ)) return shard.index();
        }
        return -1;
    }

    private static void runScheduler(final EntityScheduler scheduler) {
        final net.minecraft.world.entity.Entity handle = scheduler.entity.getHandleRaw();
        if (handle == null || scheduler.isRetired()
            || !ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(handle)) return;
        scheduler.executeTick();
    }

    private boolean runPartitions(
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region,
        final RegionizedWorldData worldData,
        final List<Shard> shards,
        final List<Runnable>[] partitions
    ) {
        if (!hasTasks(partitions)) return false;
        TickSnapshot tickSnapshot = worldData.canvas$shardSnapshot;
        if (tickSnapshot == null || tickSnapshot.world() != worldData.world) {
            tickSnapshot = captureTickSnapshot(worldData);
            if (worldData.canvas$shardSnapshotActive) worldData.canvas$shardSnapshot = tickSnapshot;
        }
        final EntityQuerySnapshot snapshot = tickSnapshot.entities();
        final RegionShardSnapshot metadataSnapshot = tickSnapshot.metadata();
        final List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < partitions.length; i++) {
            final List<Runnable> partition = partitions[i];
            if (partition.isEmpty()) continue;
            final Shard shard = shards.get(i);
            futures.add(this.executor.submit(() -> this.runShard(region, worldData, shard, snapshot, metadataSnapshot, partition)));
        }
        Throwable failure = null;
        boolean interrupted = false;
        for (final Future<?> future : futures) {
            while (true) {
                try {
                    future.get();
                    break;
                } catch (final InterruptedException interruptedException) {
                    interrupted = true;
                    if (failure == null) failure = interruptedException;
                } catch (final ExecutionException failed) {
                    if (failure == null) failure = failed.getCause();
                    break;
                } catch (final java.util.concurrent.CancellationException cancelled) {
                    if (failure == null) failure = cancelled;
                    break;
                }
            }
        }
        try {
            drainEntityLookupCallbacks(worldData);
        } catch (final Throwable callbackFailure) {
            if (failure == null) {
                failure = callbackFailure;
            } else {
                failure.addSuppressed(callbackFailure);
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new RuntimeException("Region shard failed", failure);
        return true;
    }

    private void runShard(
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region,
        final RegionizedWorldData worldData,
        final Shard shard,
        final EntityQuerySnapshot snapshot,
        final RegionShardSnapshot metadataSnapshot,
        final List<Runnable> tasks
    ) {
        final Thread thread = Thread.currentThread();
        if (!(thread instanceof TickRegionScheduler.ShardTickThread shardThread)) {
            throw new IllegalStateException("Shard work ran on non-shard thread: " + thread);
        }
        shardThread.currentTickingRegion = region;
        shardThread.currentTickingWorldRegionizedData = worldData;
        shardThread.currentShard = shard;
        shardThread.currentEntitySnapshot = snapshot;
        shardThread.currentMetadataSnapshot = metadataSnapshot;
        try {
            for (final Runnable task : tasks) task.run();
        } finally {
            shardThread.currentMetadataSnapshot = null;
            shardThread.currentEntitySnapshot = null;
            shardThread.currentShard = null;
            shardThread.currentTickingRegion = null;
            shardThread.currentTickingWorldRegionizedData = null;
        }
    }
}
