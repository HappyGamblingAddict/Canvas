package io.canvasmc.canvas.threadedregions.sharding;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import io.papermc.paper.threadedregions.TickRegionScheduler;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class RegionShardPoiSnapshotTest {

    @Test
    void coveredMissingPoiReturnsNullWhileUncoveredSectionFailsClosed() {
        final Level world = mock(Level.class);
        final BlockPos coveredPos = new BlockPos(1, 64, 1);
        final BlockPos missingPos = new BlockPos(2, 64, 2);
        final BlockPos unknownPos = new BlockPos(100, 64, 100);
        final RegionShardSnapshot.PoiEntry entry =
            new RegionShardSnapshot.PoiEntry(coveredPos, holder());

        final RegionShardSnapshot.PoiSnapshot snapshot = new RegionShardSnapshot.PoiSnapshot(
            world,
            Map.of(coveredPos, entry),
            sectionsFor(coveredPos, missingPos)
        );

        assertSame(entry, snapshot.getPoi(coveredPos));
        assertNull(snapshot.getPoi(missingPos), "a covered section without a record is authoritative absence");
        assertThrows(
            IllegalStateException.class,
            () -> snapshot.getPoi(unknownPos),
            "an uncovered section must not be treated as empty"
        );
    }

    @Test
    void snapshotCopiesInputsAndPoiEntriesImmutable() {
        final Level world = mock(Level.class);
        final BlockPos pos = new BlockPos(4, 70, 4);
        final Map<BlockPos, RegionShardSnapshot.PoiEntry> entries = new HashMap<>();
        entries.put(pos, new RegionShardSnapshot.PoiEntry(pos, holder()));
        final LongOpenHashSet sections = new LongOpenHashSet();
        sections.add(SectionPos.asLong(pos));

        final RegionShardSnapshot.PoiSnapshot snapshot = new RegionShardSnapshot.PoiSnapshot(world, entries, sections);
        final RegionShardSnapshot.PoiEntry captured = snapshot.getPoi(pos);

        entries.clear();
        sections.clear();

        assertSame(captured, snapshot.getPoi(pos));
        assertEquals(1, snapshot.getSectionCount());

        final BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(1, 2, 3);
        final RegionShardSnapshot.PoiEntry immutableEntry = new RegionShardSnapshot.PoiEntry(mutable, holder());
        mutable.set(9, 9, 9);
        assertEquals(new BlockPos(1, 2, 3), immutableEntry.pos());
    }

    @Test
    void serialPoiAccessRejectsShardWorkersButAllowsParentThreads() throws Exception {
        assertDoesNotThrow(() -> RegionShardExecutor.ensureSerialPoiAccess("test"));

        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread worker = new TickRegionScheduler.ShardTickThread(() -> {
            try {
                RegionShardExecutor.ensureSerialPoiAccess("test");
            } catch (final Throwable throwable) {
                failure.set(throwable);
            }
        }, "Canvas POI Snapshot Test Worker");
        worker.setDaemon(true);
        worker.start();
        worker.join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(worker.isAlive(), "the test worker must terminate");
        assertInstanceOf(IllegalStateException.class, failure.get());
        assertFalse(RegionShardExecutor.canQueryPois(mock(Level.class)), "parent threads have no worker POI snapshot");
    }

    @SuppressWarnings("unchecked")
    private static Holder<PoiType> holder() {
        return (Holder<PoiType>)mock(Holder.class);
    }

    private static LongOpenHashSet sectionsFor(final BlockPos... positions) {
        final LongOpenHashSet sections = new LongOpenHashSet();
        for (final BlockPos position : positions) {
            sections.add(SectionPos.asLong(position));
        }
        return sections;
    }
}
