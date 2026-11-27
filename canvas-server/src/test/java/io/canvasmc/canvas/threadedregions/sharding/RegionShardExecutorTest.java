package io.canvasmc.canvas.threadedregions.sharding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RegionShardExecutorTest {

    @Test
    void shardLayoutsAreNonOverlappingPartitions() {
        final int[][] extents = {
            {0, 0, 0, 0},
            {0, 0, 0, 7},
            {0, 0, 7, 0},
            {-3, -4, 4, 3},
            {-8, -8, 7, 7},
            {0, 0, 15, 15}
        };
        final int[] requests = {-1, 1, 2, 3, 4, 8, 9};

        for (final int[] extent : extents) {
            for (final int requested : requests) {
                final List<RegionShardExecutor.Shard> shards = RegionShardExecutor.createShards(
                    extent[0], extent[1], extent[2], extent[3], requested
                );
                final int target = Math.max(2, Math.min(8, requested));
                assertFalse(shards.isEmpty(), "layout must not be empty");
                assertTrue(shards.size() <= target, "effective shard count must not exceed the requested maximum");

                for (int i = 0; i < shards.size(); i++) {
                    final RegionShardExecutor.Shard shard = shards.get(i);
                    assertEquals(i, shard.index(), "shard indices must be ordered");
                    assertTrue(shard.minSectionX() <= shard.maxSectionX(), "X bounds must be ordered");
                    assertTrue(shard.minSectionZ() <= shard.maxSectionZ(), "Z bounds must be ordered");
                }

                for (int x = extent[0]; x <= extent[2]; x++) {
                    for (int z = extent[1]; z <= extent[3]; z++) {
                        final long owners = shards.stream()
                            .filter(shard -> shard.contains(x, z))
                            .count();
                        assertEquals(1, owners, "every in-bounds section must belong to exactly one shard");
                        assertTrue(
                            RegionShardExecutor.shardIndex(shards, x, z) >= 0,
                            "every in-bounds section must resolve to a shard"
                        );
                    }
                }
            }
        }
    }

    @Test
    void shardIndexRejectsPositionsOutsideTheLayout() {
        final List<RegionShardExecutor.Shard> shards = RegionShardExecutor.createShards(-2, -2, 1, 1, 4);
        assertEquals(-1, RegionShardExecutor.shardIndex(shards, -3, 0));
        assertEquals(-1, RegionShardExecutor.shardIndex(shards, 2, 0));
        assertEquals(-1, RegionShardExecutor.shardIndex(shards, 0, -3));
        assertEquals(-1, RegionShardExecutor.shardIndex(shards, 0, 2));
    }

    @Test
    void shardBoundsAreInclusive() {
        final RegionShardExecutor.Shard shard = new RegionShardExecutor.Shard(3, -2, 2, -3, 1);
        assertTrue(shard.contains(-2, -3));
        assertTrue(shard.contains(2, 1));
        assertTrue(shard.contains(-2, 1));
        assertTrue(shard.contains(2, -3));
        assertFalse(shard.contains(-3, 0));
        assertFalse(shard.contains(3, 0));
        assertFalse(shard.contains(0, -4));
        assertFalse(shard.contains(0, 2));
    }
}
