package io.canvasmc.canvas.threadedregions.sharding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Structural checks for the pure shard-layout math. These do not require a
 * server, a region, or a worker pool.
 */
class RegionShardLayoutStructuralTest {

    @Test
    void shardRectanglesNeverOverlapForLargeOrOffsetRegions() {
        final int[][] extents = {
            {-32, -32, 31, 31},
            {-17, 5, 17, 9},
            {100, -200, 163, -137},
            {7, 7, 7, 42},
            {-1, -1, 30, 0}
        };

        for (final int[] extent : extents) {
            for (int requested = 2; requested <= 8; requested++) {
                final List<RegionShardExecutor.Shard> shards = RegionShardExecutor.createShards(
                    extent[0], extent[1], extent[2], extent[3], requested
                );

                assertFalse(shards.isEmpty(), "a non-empty region must produce at least one shard");
                assertTrue(
                    shards.size() <= Math.min(8, requested),
                    "shard count must not exceed the requested/clamped maximum"
                );

                for (int first = 0; first < shards.size(); first++) {
                    for (int second = first + 1; second < shards.size(); second++) {
                        final RegionShardExecutor.Shard firstShard = shards.get(first);
                        final RegionShardExecutor.Shard secondShard = shards.get(second);
                        assertFalse(
                            intersects(firstShard, secondShard),
                            () -> "shards overlap: " + firstShard + " and " + secondShard
                        );
                    }
                }

                for (int x = extent[0]; x <= extent[2]; x++) {
                    for (int z = extent[1]; z <= extent[3]; z++) {
                        final int owner = RegionShardExecutor.shardIndex(shards, x, z);
                        assertTrue(owner >= 0, "in-bounds section must resolve to a shard");
                        assertEquals(
                            1,
                            countContaining(shards, x, z),
                            "in-bounds section must belong to exactly one shard"
                        );
                    }
                }
            }
        }
    }

    @Test
    void outOfLayoutLookupsReturnMinusOneOnEverySideAndCorner() {
        final List<RegionShardExecutor.Shard> shards = RegionShardExecutor.createShards(-2, -2, 1, 1, 4);

        assertEquals(-1, RegionShardExecutor.shardIndex(shards, -3, -3), "north-west corner");
        assertEquals(-1, RegionShardExecutor.shardIndex(shards, -3, 1), "south-west corner");
        assertEquals(-1, RegionShardExecutor.shardIndex(shards, 1, -3), "north-east corner");
        assertEquals(-1, RegionShardExecutor.shardIndex(shards, 2, 2), "south-east corner");
        assertEquals(-1, RegionShardExecutor.shardIndex(shards, Integer.MIN_VALUE, 0), "extreme west");
        assertEquals(-1, RegionShardExecutor.shardIndex(shards, Integer.MAX_VALUE, 0), "extreme east");
        assertEquals(-1, RegionShardExecutor.shardIndex(shards, 0, Integer.MIN_VALUE), "extreme north");
        assertEquals(-1, RegionShardExecutor.shardIndex(shards, 0, Integer.MAX_VALUE), "extreme south");
    }

    @Test
    void emptyLayoutNeverOwnsASection() {
        final List<RegionShardExecutor.Shard> shards = RegionShardExecutor.createShards(0, 0, 0, 0, 2);

        assertEquals(1, shards.size(), "a single-section region must still get one shard");
        assertEquals(-1, RegionShardExecutor.shardIndex(List.of(), 0, 0));
    }

    @Test
    void smallRegionsDoNotCreateOverlappingCellsToReachTheRequestedCount() {
        final List<RegionShardExecutor.Shard> shards = RegionShardExecutor.createShards(0, 0, 2, 0, 8);

        assertEquals(3, shards.size(), "a 3x1 section region cannot be split into eight non-empty cells");
        for (int i = 0; i < shards.size(); i++) {
            final RegionShardExecutor.Shard shard = shards.get(i);
            assertEquals(i, shard.index());
            assertEquals(i, shard.minSectionX());
            assertEquals(i, shard.maxSectionX());
            assertEquals(0, shard.minSectionZ());
            assertEquals(0, shard.maxSectionZ());
        }
    }

    private static boolean intersects(final RegionShardExecutor.Shard first, final RegionShardExecutor.Shard second) {
        return first.minSectionX() <= second.maxSectionX()
            && second.minSectionX() <= first.maxSectionX()
            && first.minSectionZ() <= second.maxSectionZ()
            && second.minSectionZ() <= first.maxSectionZ();
    }

    private static int countContaining(final List<RegionShardExecutor.Shard> shards, final int x, final int z) {
        int count = 0;
        for (final RegionShardExecutor.Shard shard : shards) {
            if (shard.contains(x, z)) count++;
        }
        return count;
    }
}
