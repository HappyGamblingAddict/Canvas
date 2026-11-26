package io.canvasmc.canvas.threadedregions.sharding;

import io.papermc.paper.threadedregions.EntityScheduler;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import io.papermc.paper.threadedregions.TickRegions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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

    public static List<Shard> createShards(
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region,
        final int requested
    ) {
        final int target = Math.max(2, Math.min(8, requested));
        final int[] bounds = region.regioniser.getSectionBounds(region);
        final int minX = bounds[0], minZ = bounds[1], maxX = bounds[2], maxZ = bounds[3];
        final int spanX = Math.max(1, maxX - minX + 1);
        final int spanZ = Math.max(1, maxZ - minZ + 1);
        final int columns = (int)Math.ceil(Math.sqrt(target));
        final int rows = (int)Math.ceil((double)target / columns);
        final List<Shard> shards = new ArrayList<>(target);
        for (int i = 0; i < target; i++) {
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
            partitions[shardIndex(shards, sectionX, sectionZ)].add(() -> runScheduler(scheduler));
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
            partitions[shardIndex(shards, sectionX, sectionZ)].add(() -> task.accept(entity));
        }
        return runPartitions(region, worldData, shards, partitions);
    }

    private static int shardIndex(final List<Shard> shards, final int sectionX, final int sectionZ) {
        for (final Shard shard : shards) {
            if (shard.contains(sectionX, sectionZ)) return shard.index();
        }
        return shards.size() - 1;
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
        final List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < partitions.length; i++) {
            final List<Runnable> partition = partitions[i];
            if (partition.isEmpty()) continue;
            final Shard shard = shards.get(i);
            futures.add(this.executor.submit(() -> this.runShard(region, worldData, shard, partition)));
        }
        for (final Future<?> future : futures) {
            try {
                future.get();
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for region shards", interrupted);
            } catch (final ExecutionException failed) {
                final Throwable cause = failed.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new RuntimeException("Region shard failed", cause);
            }
        }
        return true;
    }

    private void runShard(
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region,
        final RegionizedWorldData worldData,
        final Shard shard,
        final List<Runnable> tasks
    ) {
        final Thread thread = Thread.currentThread();
        if (!(thread instanceof TickRegionScheduler.ShardTickThread shardThread)) {
            throw new IllegalStateException("Shard work ran on non-shard thread: " + thread);
        }
        shardThread.currentTickingRegion = region;
        shardThread.currentTickingWorldRegionizedData = worldData;
        shardThread.currentShard = shard;
        try {
            for (final Runnable task : tasks) task.run();
        } finally {
            shardThread.currentShard = null;
            shardThread.currentTickingRegion = null;
            shardThread.currentTickingWorldRegionizedData = null;
        }
    }
}
