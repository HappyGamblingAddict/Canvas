package io.canvasmc.canvas.threadedregions.sharding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Source-level checks for the item/XP full-tick admission guards in
 * {@code RegionShardExecutor} and the ServerLevel/ItemEntity patches that wire
 * them up. The guards live in methods that require a live tick-thread region,
 * so they cannot be reached from a normal JUnit thread; the real geometry and
 * chunk-state helpers are covered by {@link RegionShardExecutorAdmissionTest}.
 *
 * <p>Assertions intentionally target executable guard expressions, so deleting
 * a guard fails the test while reformatting or re-commenting does not.</p>
 */
class RegionShardExecutorAdmissionSourceTest {

    @Test
    void frozenEntitiesAreExcludedFromItemAndXpFullTickSharding() throws IOException {
        final String source = readExecutorSource();
        final String item = extractMethod(source, "public Set<net.minecraft.world.entity.Entity> runItemEntityTicks(");
        final String xp = extractMethod(source, "public Set<net.minecraft.world.entity.Entity> runExperienceOrbTicks(");

        // The executor only sees a caller-supplied predicate; it must apply it
        // both when admitting an entity and again on the shard worker.
        assertEquals(2, count(item, "canTick.test(entity)"), "item must reject frozen entities at admission and on the worker");
        assertEquals(2, count(xp, "canTick.test(orb)"), "XP must reject frozen entities at admission and on the worker");
        assertContains(item, "|| !worldData.hasEntityTickingEntity(entity) || !canTick.test(entity)) continue;");
        assertContains(
            item,
            "|| !worldData.hasEntityTickingEntity(entity) || !canTick.test(entity)\n"
                + "                    || !isEntityInCurrentShard(entity)) return;"
        );
        assertContains(xp, "|| orb.getVehicle() != null || !orb.getPassengers().isEmpty() || !canTick.test(orb)) continue;");
        assertContains(
            xp,
            "|| !worldData.hasEntityTickingEntity(orb) || !canTick.test(orb)\n"
                + "                    || !isEntityInCurrentShard(orb)) return;"
        );

        // The predicate is supplied by ServerLevel as !isEntityFrozen for both
        // the item and the XP call.
        final String safetyPatch = readPatch("0014-Sharding-Safety-and-XP.patch");
        assertEquals(
            2,
            count(safetyPatch, "+                entity -> !handleTickState.isEntityFrozen(entity),"),
            "both full-tick calls must pass the frozen-entity predicate"
        );
        assertContains(safetyPatch, "+                this::tickNonPassenger);");
        assertContains(safetyPatch, "+            final java.util.Set<Entity> shardedXp = io.canvasmc.canvas.threadedregions.sharding.RegionShardExecutor.getInstance().runExperienceOrbTicks(");
        assertOrdered(safetyPatch, "entity -> !handleTickState.isEntityFrozen(entity),", "runExperienceOrbTicks(");

        final String itemPatch = readPatch("0013-Item-Entity-Sharding.patch");
        assertContains(itemPatch, "io.canvasmc.canvas.threadedregions.sharding.RegionShardExecutor.getInstance().runItemEntityTicks(");
    }

    @Test
    void sweptBoxesRatherThanCentersMustFitInsideOneShard() throws IOException {
        final String source = readExecutorSource();
        final String item = extractMethod(source, "public Set<net.minecraft.world.entity.Entity> runItemEntityTicks(");
        final String xp = extractMethod(source, "public Set<net.minecraft.world.entity.Entity> runExperienceOrbTicks(");

        assertContains(item, "final AABB swept = entity.getBoundingBox().move(entity.getDeltaMovement()).inflate(1.0);");
        assertContains(item, "CoordinateUtils.getChunkCoordinate(swept.minX) >> shift");
        assertContains(item, "CoordinateUtils.getChunkCoordinate(swept.maxX) >> shift");
        assertContains(item, "CoordinateUtils.getChunkCoordinate(swept.minZ) >> shift");
        assertContains(item, "CoordinateUtils.getChunkCoordinate(swept.maxZ) >> shift");
        assertContains(item, "if (currentShard < 0 || currentShard != shardIndex(shards, minX, minZ)");
        assertContains(item, "|| currentShard != shardIndex(shards, maxX, maxZ)) continue;");
        assertOrdered(item, "final AABB swept =", "shardIndex(shards, maxX, maxZ)");

        assertContains(xp, "!experienceOrbWithinShard(shards.get(currentShard), mergeBox, shift)");
        assertContains(xp, "!experienceOrbWithinShard(shards.get(currentShard), predictedMergeBox, shift)");
        assertContains(xp, "currentBox.inflate(experienceOrbPlayerFollowRadius(orb))");
        assertContains(xp, "currentBox.move(delta).inflate(experienceOrbPlayerFollowRadius(orb))");

        final String within = extractMethod(source, "private static boolean experienceOrbWithinShard(");
        assertContains(within, "shard.contains(minX, minZ) && shard.contains(minX, maxZ)");
        assertContains(within, "&& shard.contains(maxX, minZ) && shard.contains(maxX, maxZ)");
        assertContains(within, "(int)Math.floor(box.minX) >> 4 >> shift");
        assertContains(within, "(int)Math.floor(box.maxX) >> 4 >> shift");
    }

    @Test
    void itemAndXpSweepsRejectUnloadedOrNonFullChunksWithoutWorkerSideLoading() throws IOException {
        final String source = readExecutorSource();
        final String item = extractMethod(source, "public Set<net.minecraft.world.entity.Entity> runItemEntityTicks(");
        final String xp = extractMethod(source, "public Set<net.minecraft.world.entity.Entity> runExperienceOrbTicks(");
        final String full = extractMethod(source, "private static boolean entityChunksFull(");

        assertContains(item, "|| !entityChunksFull((net.minecraft.server.level.ServerLevel)worldData.world,");
        assertContains(item, "entity.getBoundingBox(), entity.getDeltaMovement())");
        assertContains(xp, "if (!entityChunksFull(serverLevel, currentBox, delta)");

        // getChunkNow is the non-loading lookup; getChunk/getChunkOrLoad would
        // synchronously load chunks from a shard worker.
        assertContains(full, "level.getChunkSource().getChunkNow(chunkX, chunkZ)");
        assertFalse(full.contains("getChunkOrLoad"), "a shard worker must never load chunks synchronously");
        assertFalse(full.contains(".getChunk("), "only the non-loading getChunkNow lookup is allowed");
        assertContains(full, "if (chunk == null || !(chunk instanceof net.minecraft.world.level.chunk.LevelChunk levelChunk)");
        assertContains(full, "|| !levelChunk.getFullStatus().isOrAfter(net.minecraft.server.level.FullChunkStatus.FULL)) return false;");
        assertOrdered(full, "getChunkSource().getChunkNow(chunkX, chunkZ)", "getFullStatus()");
    }

    @Test
    void xpAdmissionRejectsPlayersWaterLavaCollisionAndOutsideMergeQueries() throws IOException {
        final String source = readExecutorSource();
        final String xp = extractMethod(source, "public Set<net.minecraft.world.entity.Entity> runExperienceOrbTicks(");

        assertContains(xp, "|| experienceOrbHasNearbyPlayer(orb, snapshot, snapshotLength)");
        assertContains(xp, "|| orb.isEyeInFluid(net.minecraft.tags.FluidTags.WATER)");
        assertContains(xp, "|| orb.isInLava()");
        assertContains(xp, "|| !level.noCollision(currentBox)) continue;");
        assertOrdered(xp, "!level.noCollision(currentBox)) continue;", "candidates.put(orb,");
        assertOrdered(xp, "experienceOrbHasNearbyPlayer(orb, snapshot, snapshotLength)", "candidates.put(orb,");

        final String player = extractMethod(source, "private static boolean experienceOrbHasNearbyPlayer(");
        assertContains(player, "entry.entity() instanceof net.minecraft.world.entity.player.Player && !entry.removed()");
        assertContains(player, "(entry.bounds().intersects(current) || entry.bounds().intersects(predicted))");
        final String radius = extractMethod(source, "private static double experienceOrbPlayerFollowRadius(");
        assertContains(radius, "XP_PLAYER_FOLLOW_RADIUS + speed + XP_SHARD_SAFETY_MARGIN + 1.0");
        assertEquals(8.0, readConstant(source, "XP_PLAYER_FOLLOW_RADIUS"), 0.0);
    }

    @Test
    void pathologicalVelocityAndSpanValuesAreRejectedBeforeChunkLookups() throws IOException {
        final String full = extractMethod(readExecutorSource(), "private static boolean entityChunksFull(");

        assertContains(full, "if (Math.abs(delta.x) > 8.0 || Math.abs(delta.y) > 8.0 || Math.abs(delta.z) > 8.0) return false;");
        assertContains(full, "final net.minecraft.world.phys.AABB predicted = currentBox.move(delta);");
        assertContains(full, "final double margin = 1.0;");
        assertContains(full, "(int)Math.floor(Math.min(currentBox.minX, predicted.minX) - margin) >> 4");
        assertContains(full, "if (maxChunkX - minChunkX > 16 || maxChunkZ - minChunkZ > 16) return false;");
        assertOrdered(full, "Math.abs(delta.x) > 8.0", "final net.minecraft.world.phys.AABB predicted =");
        assertOrdered(full, "maxChunkX - minChunkX > 16", "getChunkSource().getChunkNow(chunkX, chunkZ)");
    }

    @Test
    void xpMergeTargetsMustBeSameShardAndPartOfTheSolvedFixedPoint() throws IOException {
        final String source = readExecutorSource();
        final String xp = extractMethod(source, "public Set<net.minecraft.world.entity.Entity> runExperienceOrbTicks(");

        assertContains(xp, "final IdentityHashMap<net.minecraft.world.entity.ExperienceOrb, ExperienceOrbShardCandidate> candidates =");
        final double mergeRadius = readAssignment(xp, "final double mergeRadius = ");
        final double safetyMargin = readConstant(source, "XP_SHARD_SAFETY_MARGIN");
        assertTrue(
            mergeRadius + safetyMargin >= 0.5,
            "the guarded merge AABB must cover ExperienceOrb's default 0.5-block merge query"
        );
        assertContains(xp, "final net.minecraft.world.phys.AABB mergeBox = currentBox.inflate(mergeRadius + XP_SHARD_SAFETY_MARGIN);");
        assertContains(xp, "candidates.put(orb, new ExperienceOrbShardCandidate(orb, currentShard, mergeBox));");
        assertContains(xp, "if (!candidate.eligible || !experienceOrbScansForMerges(candidate.orb)) continue;");
        final String scansForMerges = extractMethod(source, "private static boolean experienceOrbScansForMerges(");
        assertContains(scansForMerges, "orb.tickCount % 20 == 1");
        assertContains(xp, "|| other == candidate.orb || entry.removed()");
        assertContains(xp, "|| !entry.bounds().intersects(candidate.mergeBox)) continue;");
        assertContains(xp, "if (target == null || !target.eligible || target.shard != candidate.shard) {");
        assertContains(xp, "candidate.eligible = false;");
        assertContains(xp, "changed = true;");
        assertContains(xp, "} while (changed);");

        // Rejection is iterative: invalidating B can in turn invalidate A, and
        // only survivors of the fixed point may be dispatched.
        assertOrdered(xp, "boolean changed;", "} while (changed);");
        assertOrdered(xp, "} while (changed);", "if (!candidate.eligible) continue;");
        assertOrdered(xp, "if (!candidate.eligible) continue;", "tick.accept(orb);");
        assertEquals(2, count(xp, "for (final ExperienceOrbShardCandidate candidate : candidates.values())"),
            "one pass solves the fixed point, one pass dispatches eligible candidates");

        // Item merges are restricted the same way, in the patch that touches ItemEntity.
        final String safetyPatch = readPatch("0014-Sharding-Safety-and-XP.patch");
        assertContains(
            safetyPatch,
            "+                    if (!io.canvasmc.canvas.threadedregions.sharding.RegionShardExecutor.isEntityInCurrentShard(entity)) continue;"
        );

        // ExperienceOrb.scanForMerges also re-checks each target on the worker.
        final Path orbPatch = repositoryRoot().resolve(
            "canvas-server/minecraft-patches/sources/net/minecraft/world/entity/ExperienceOrb.java.patch"
        );
        assertTrue(Files.isRegularFile(orbPatch), "missing ExperienceOrb source patch: " + orbPatch);
        assertContains(
            normalize(Files.readString(orbPatch, StandardCharsets.UTF_8)),
            "+                if (!io.canvasmc.canvas.threadedregions.sharding.RegionShardExecutor.isEntityInCurrentShard(orb)) continue;"
        );
    }

    private static void assertContains(final String text, final String needle) {
        assertTrue(text.contains(needle), "missing guard: " + needle);
    }

    private static void assertOrdered(final String text, final String first, final String second) {
        final int firstIndex = text.indexOf(first);
        final int secondIndex = text.lastIndexOf(second);
        assertTrue(firstIndex >= 0, "missing first expression: " + first);
        assertTrue(secondIndex >= 0, "missing second expression: " + second);
        assertTrue(firstIndex < secondIndex, first + " must precede " + second);
    }

    private static int count(final String text, final String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static double readConstant(final String source, final String name) {
        return readAssignment(source, "double " + name + " = ");
    }

    private static double readAssignment(final String source, final String prefix) {
        final int start = source.indexOf(prefix);
        assertTrue(start >= 0, "missing assignment: " + prefix);
        final int from = start + prefix.length();
        final int end = source.indexOf(';', from);
        return Double.parseDouble(source.substring(from, end).trim());
    }

    private static String readExecutorSource() throws IOException {
        final Path source = repositoryRoot().resolve(
            "canvas-server/src/main/java/io/canvasmc/canvas/threadedregions/sharding/RegionShardExecutor.java"
        );
        assertTrue(Files.isRegularFile(source), "missing source: " + source);
        return normalize(Files.readString(source, StandardCharsets.UTF_8));
    }

    private static String readPatch(final String name) throws IOException {
        final Path patches = repositoryRoot().resolve("canvas-server/minecraft-patches");
        final Path patch = patches.resolve("base").resolve(name);
        assertTrue(Files.isRegularFile(patch), "missing patch: " + patch);
        return normalize(Files.readString(patch, StandardCharsets.UTF_8));
    }

    private static String extractMethod(final String source, final String signature) {
        final int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing method: " + signature);

        int depth = 0;
        boolean opened = false;
        for (int index = start; index < source.length(); index++) {
            final char character = source.charAt(index);
            if (character == '{') {
                depth++;
                opened = true;
            } else if (character == '}' && opened && --depth == 0) {
                return source.substring(start, index + 1);
            }
        }
        throw new AssertionError("could not find the end of method: " + signature);
    }

    private static String normalize(final String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))
                && Files.isDirectory(current.resolve("canvas-server"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("could not locate the Canvas repository root from " + System.getProperty("user.dir"));
    }
}
