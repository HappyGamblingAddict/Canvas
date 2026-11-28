package io.canvasmc.canvas.threadedregions.sharding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.canvasmc.canvas.threadedregions.sharding.RegionShardExecutor.Shard;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/**
 * Unit test for the pure swept-box containment helper behind XP full tick
 * sharding. The helper is invoked reflectively because it is deliberately not
 * part of the executor's API, but it is the guard that decides whether a whole
 * movement sweep fits in one shard.
 *
 * <p>Guards that need live entities or a {@code ServerLevel} are covered by
 * {@link RegionShardExecutorAdmissionSourceTest}: instrumenting Minecraft
 * classes requires bootstrapped registries, which a plain JUnit worker does not
 * have.</p>
 */
class RegionShardExecutorAdmissionTest {

    @Test
    void sweptBoxMustFitEntirelyInsideOneShard() throws Exception {
        final Method withinShard = RegionShardExecutor.class.getDeclaredMethod(
            "experienceOrbWithinShard", Shard.class, AABB.class, int.class
        );
        withinShard.setAccessible(true);

        final List<Shard> shards = RegionShardExecutor.createShards(0, 0, 7, 7, 4);
        assertEquals(4, shards.size(), "the fixture must produce four distinct shards");
        final Shard first = shards.get(0);

        assertTrue(
            (Boolean)withinShard.invoke(null, first, new AABB(33, 64, 33, 47, 65, 47), 0),
            "a box whose whole span lies in one shard must be admitted"
        );
        assertFalse(
            (Boolean)withinShard.invoke(null, first, new AABB(48, 64, 48, 64, 65, 64), 0),
            "a swept box crossing the X shard boundary must be rejected"
        );
        assertFalse(
            (Boolean)withinShard.invoke(null, first, new AABB(33, 64, 33, 64, 65, 64), 0),
            "a box whose center fits but whose swept maximum does not must be rejected"
        );
        assertFalse(
            (Boolean)withinShard.invoke(null, first, new AABB(33, 64, 33, 47, 65, 64), 0),
            "a swept box crossing the Z shard boundary must be rejected"
        );

        // With sectionChunkShift = 1 a section covers two chunks; the same
        // block-distance sweep crosses a different shard boundary.
        final Shard shiftedFirst = RegionShardExecutor.createShards(0, 0, 3, 3, 4).get(0);
        assertTrue(
            (Boolean)withinShard.invoke(null, shiftedFirst, new AABB(33, 64, 33, 63, 65, 63), 1),
            "a box inside a two-chunk section must be admitted"
        );
        assertFalse(
            (Boolean)withinShard.invoke(null, shiftedFirst, new AABB(33, 64, 33, 64, 65, 64), 1),
            "a box crossing a two-chunk section boundary must be rejected"
        );
    }
}
