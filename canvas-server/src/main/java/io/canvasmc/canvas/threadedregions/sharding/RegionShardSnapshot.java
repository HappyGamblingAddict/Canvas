package io.canvasmc.canvas.threadedregions.sharding;

import io.papermc.paper.threadedregions.RegionizedWorldData;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/** Immutable per-tick block-entity and POI metadata. Never exposes live state. */
public final class RegionShardSnapshot {

    public record BlockEntityEntry(BlockPos pos, BlockEntityType<?> type, boolean removed) {
        public boolean present() {
            return !this.removed;
        }
    }

    public record PoiEntry(BlockPos pos, Holder<PoiType> type) {
        public PoiEntry {
            pos = pos.immutable();
        }
    }

    /**
     * Immutable POI point metadata plus the exact set of loaded POI sections it
     * covers. A point lookup outside {@link #sections()} is unknown rather than
     * absent, so a worker must fail closed instead of treating unloaded data as
     * an empty result.
     */
    public record PoiSnapshot(Level world, Map<BlockPos, PoiEntry> entries, LongSet sections) {
        public PoiSnapshot {
            entries = Map.copyOf(entries);
            sections = LongSets.unmodifiable(new LongOpenHashSet(sections));
        }

        /**
         * @return the captured record, or null when the covered section is known
         *         to have no POI at this position
         * @throws IllegalStateException when this snapshot does not cover the
         *         section, for example because it was not loaded during capture
         */
        public PoiEntry getPoi(final BlockPos pos) {
            if (!this.sections.contains(SectionPos.asLong(pos))) {
                throw new IllegalStateException("POI snapshot does not cover section at " + pos);
            }
            return this.entries.get(pos.immutable());
        }

        public int getEntryCount() {
            return this.entries.size();
        }

        public int getSectionCount() {
            return this.sections.size();
        }

        public static PoiSnapshot capture(final RegionizedWorldData worldData) {
            if (!(worldData.world instanceof ServerLevel level)) return null;
            if (RegionShardExecutor.isShardWorker()
                || io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData() != worldData) {
                throw new IllegalStateException("POI snapshots must be captured on the owning parent region thread");
            }
            final Map<BlockPos, PoiEntry> entries = new HashMap<>();
            final LongSet sections = new LongOpenHashSet();
            level.getPoiManager().canvas$captureLoadedPois(worldData.getChunks(), entries, sections);
            return new PoiSnapshot(level, entries, sections);
        }
    }

    private final Level world;
    private final Map<BlockPos, BlockEntityEntry> blockEntities;
    private final PoiSnapshot pois;

    private RegionShardSnapshot(final Level world, final Map<BlockPos, BlockEntityEntry> blockEntities, final PoiSnapshot pois) {
        this.world = world;
        this.blockEntities = Map.copyOf(blockEntities);
        this.pois = pois;
    }

    public Level world() {
        return this.world;
    }

    public PoiSnapshot pois() {
        return this.pois;
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
        return new RegionShardSnapshot(worldData.world, entries, PoiSnapshot.capture(worldData));
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
