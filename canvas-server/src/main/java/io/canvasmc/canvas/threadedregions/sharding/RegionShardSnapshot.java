package io.canvasmc.canvas.threadedregions.sharding;

import io.papermc.paper.threadedregions.RegionizedWorldData;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/** Immutable per-tick block-entity metadata. Never exposes live block entities. */
public final class RegionShardSnapshot {

    public record BlockEntityEntry(BlockPos pos, BlockEntityType<?> type, boolean removed) {
        public boolean present() {
            return !this.removed;
        }
    }

    private final Level world;
    private final Map<BlockPos, BlockEntityEntry> blockEntities;

    private RegionShardSnapshot(final Level world, final Map<BlockPos, BlockEntityEntry> blockEntities) {
        this.world = world;
        this.blockEntities = Map.copyOf(blockEntities);
    }

    public Level world() {
        return this.world;
    }

    public static RegionShardSnapshot capture(final RegionizedWorldData worldData) {
        if (RegionShardExecutor.isShardWorker()
            || io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData() != worldData) {
            throw new IllegalStateException("Block entity snapshots must be captured on the owning parent region thread");
        }
        final Map<BlockPos, BlockEntityEntry> entries = new HashMap<>();
        for (final LevelChunk chunk : worldData.getChunks()) {
            chunk.canvas$iterateOverBlockEntities(blockEntity -> {
                final BlockPos pos = blockEntity.getBlockPos().immutable();
                entries.put(pos, new BlockEntityEntry(pos, blockEntity.getType(), blockEntity.isRemoved()));
            });
        }
        for (final BlockEntity blockEntity : worldData.capturedBlockEntities.values()) {
            final BlockPos pos = blockEntity.getBlockPos().immutable();
            entries.put(pos, new BlockEntityEntry(pos, blockEntity.getType(), blockEntity.isRemoved()));
        }
        return new RegionShardSnapshot(worldData.world, entries);
    }

    /**
     * Returns metadata captured at the start of the region world tick, or null
     * when the position was absent. Callers must use
     * {@link RegionShardExecutor#canQueryBlockEntities(Level)} to distinguish an
     * unavailable snapshot from an absent entry. Removed entries are retained as
     * tombstones with {@link BlockEntityEntry#removed()} set.
     */
    public BlockEntityEntry getBlockEntity(final BlockPos pos) {
        return this.blockEntities.get(pos);
    }

    public int getBlockEntityCount() {
        return this.blockEntities.size();
    }
}
