package io.canvasmc.canvas.threadedregions.sharding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Static source checks for the sharded non-passenger tick path and the
 * structural-entity drain patch. They run without a Minecraft server and are
 * intentionally text based because the method is gated by real tick-thread
 * ownership and cannot be reached from a normal JUnit thread.
 */
class ShardedNonPassengerSourceTest {

    @Test
    void shardedNonPassengerRejectsPlayersAndOmitsPortalAndPassengerWork() throws IOException {
        final Path sourceRoot = generatedSourceRoot();
        final Path serverLevel = sourceRoot.resolve("net/minecraft/server/level/ServerLevel.java");
        assumeTrue(Files.isRegularFile(serverLevel), "run applyAllPatches to validate generated sources");
        final String method = extractMethod(
            Files.readString(serverLevel, StandardCharsets.UTF_8).replace("\r\n", "\n"),
            "tickShardedNonPassenger"
        );

        assertTrue(
            method.contains("TickThread.ensureTickThread(\"Cannot tick an entity off-main\")"),
            "the method must retain its tick-thread ownership check"
        );
        assertTrue(
            method.contains("if (entity instanceof Player)"),
            "players must remain on the normal tickNonPassenger path"
        );
        assertTrue(
            method.contains("throw new IllegalStateException(\"Players must use tickNonPassenger\")"),
            "player rejection must be explicit"
        );

        assertTrue(
            method.contains("entity.setOldPosAndRot()")
                && method.contains("entity.tickCount++")
                && method.contains("entity.totalEntityAge++"),
            "the 26.2 equivalent of commonTick must remain in the sharded path"
        );
        assertTrue(
            method.contains("ActivationRange.checkIfActive(entity)"),
            "activation checks must remain in the sharded path"
        );
        assertTrue(method.contains("entity.tick()"), "active entities must still tick");
        assertTrue(method.contains("entity.inactiveTick()"), "inactive entities must still take the inactive path");
        assertTrue(
            method.contains("TickThread.isTickThreadFor(entity)"),
            "the method must re-check ownership after entity.tick()"
        );

        assertFalse(method.contains("portal"), "portal processing must stay on the parent region thread");
        assertFalse(method.contains("tickPassenger"), "passenger recursion must stay on the parent region thread");
        assertFalse(method.contains("getPassengers"), "passenger traversal must stay on the parent region thread");
        assertFalse(method.contains("tickNonPassenger("), "the sharded path must not recurse into the serial path");

        assertOrdered(method, "entity.setOldPosAndRot()", "ActivationRange.checkIfActive(entity)");
        assertOrdered(method, "ActivationRange.checkIfActive(entity)", "entity.tick()");
    }

    @Test
    void structuralDrainPatchQueuesLookupMovesAndRejectsWorkerAdds() throws IOException {
        final String patch = readPatch("0006-Snapshot-Entity-Queries-And-Stale-Callbacks.patch")
            + readPatch("0009-Shard-Snapshot-And-Callback-Queue.patch")
            + readPatch("0005-Entity-Despawn-And-Structural-Action-Sharding.patch");

        assertTrue(
            patch.contains("+                RegionShardExecutor.queueEntityLookupCallback(this::onMove);"),
            "EntityLookup.onMove must be deferred when called by a shard worker"
        );
        assertTrue(
            patch.contains("+        private final java.util.concurrent.atomic.AtomicBoolean canvas$removeQueued ="),
            "structural removals must be deduplicated before they are queued"
        );
        assertTrue(
            patch.contains("+                if (this.canvas$removeQueued.compareAndSet(false, true)) {")
                && patch.contains("+                    RegionShardExecutor.queueEntityLookupCallback(() -> this.onRemove(reason));"),
            "EntityLookup.onRemove must be queued once when called by a shard worker"
        );
        assertTrue(
            patch.contains("+    public final java.util.concurrent.ConcurrentLinkedQueue<Runnable> canvas$entityLookupCallbacks ="),
            "the per-world callback queue must be owned by RegionizedWorldData"
        );
        assertTrue(
            count(patch, "+        RegionShardExecutor.rejectShardWorkerEntityAdd(entity);") >= 1,
            "EntityLookup.addEntity must reject worker-side entity adds"
        );
        assertTrue(
            count(patch, "+        io.canvasmc.canvas.threadedregions.sharding.RegionShardExecutor.rejectShardWorkerEntityAdd(entity);") >= 1,
            "ServerLevel.addEntity must reject worker-side entity adds"
        );
    }

    @Test
    void generatedServerLevelMatchesThePatchBackedMethod() throws IOException {
        final Path sourceRoot = generatedSourceRoot();
        final Path serverLevel = sourceRoot.resolve("net/minecraft/server/level/ServerLevel.java");
        assumeTrue(Files.isRegularFile(serverLevel), "run applyAllPatches to validate generated sources");

        final String source = Files.readString(serverLevel, StandardCharsets.UTF_8);
        assumeTrue(
            source.contains("public void tickShardedNonPassenger(final Entity entity)"),
            "0016 is not applied to the generated source tree yet"
        );

        final String method = extractMethod(source, "tickShardedNonPassenger");
        assertTrue(method.contains("if (entity instanceof Player)"));
        assertTrue(method.contains("throw new IllegalStateException(\"Players must use tickNonPassenger\")"));
        assertFalse(method.contains("portal"));
        assertFalse(method.contains("tickPassenger"));
        assertFalse(method.contains("getPassengers"));
    }

    private static void assertOrdered(final String text, final String first, final String second) {
        assertOrdered(text, first, second, first + " must precede " + second);
    }

    private static void assertOrdered(final String text, final String first, final String second, final String message) {
        final int firstIndex = text.indexOf(first);
        final int secondIndex = text.lastIndexOf(second);
        assertTrue(firstIndex >= 0, "missing: " + first);
        assertTrue(secondIndex >= 0, "missing: " + second);
        assertTrue(firstIndex < secondIndex, message);
    }

    private static String extractShardedNonPassengerMethod(final String patch) {
        final String marker = "+    public void tickShardedNonPassenger(final Entity entity) {";
        final int start = patch.indexOf(marker);
        assertTrue(start >= 0, "0016 must add ServerLevel.tickShardedNonPassenger");

        final int end = patch.indexOf("\n+    }\n", start);
        assertTrue(end > start, "could not find the end of ServerLevel.tickShardedNonPassenger");

        final StringBuilder method = new StringBuilder();
        for (final String line : patch.substring(start, end).split("\\R")) {
            method.append(line.startsWith("+") ? line.substring(1) : line).append('\n');
        }
        return method.toString();
    }

    private static String extractMethod(final String source, final String name) {
        final String signature = "public void " + name + "(final Entity entity) {";
        final int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing method: " + name);

        int depth = 0;
        for (int index = start; index < source.length(); index++) {
            final char character = source.charAt(index);
            if (character == '{') depth++;
            if (character == '}' && --depth == 0) {
                return source.substring(start, index + 1);
            }
        }
        throw new AssertionError("could not find the end of method: " + name);
    }

    private static String readPatch(final String name) throws IOException {
        final Path patch = repositoryRoot().resolve(
            "canvas-server/minecraft-patches/features/" + name
        );
        assertTrue(Files.isRegularFile(patch), "missing patch: " + patch);
        return Files.readString(patch, StandardCharsets.UTF_8).replace("\r\n", "\n");
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

    private static Path generatedSourceRoot() {
        final Path root = repositoryRoot();
        final Path canvasMinecraft = root.resolve("canvas-server/src/minecraft/java");
        if (Files.isDirectory(canvasMinecraft)) return canvasMinecraft;
        final Path paperServer = root.resolve("paper-server/src/minecraft/java");
        if (Files.isDirectory(paperServer)) return paperServer;
        return root.resolve("paper-server/src/main/java");
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
