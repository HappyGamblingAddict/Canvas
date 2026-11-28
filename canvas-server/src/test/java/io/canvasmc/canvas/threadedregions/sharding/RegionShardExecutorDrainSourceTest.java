package io.canvasmc.canvas.threadedregions.sharding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Source-level checks for the executor's failure and callback-drain ordering.
 * They complement {@link RegionShardExecutorDrainTest}, which can only run
 * when the scheduler can initialize without a server.
 */
class RegionShardExecutorDrainSourceTest {

    @Test
    void runPartitionsWaitsForEveryFutureAndNeverCancelsThem() throws IOException {
        final String method = extractMethod(readExecutorSource(), "private boolean runPartitions");

        assertTrue(
            method.contains("for (final Future<?> future : futures)"),
            "every submitted shard future must be visited"
        );
        assertTrue(
            method.contains("while (true)") && method.contains("future.get()"),
            "a future must be driven to a terminal state, including after interruption"
        );
        assertTrue(
            method.contains("catch (final ExecutionException failed)"),
            "worker failures must be collected without abandoning later futures"
        );
        assertTrue(
            method.contains("catch (final java.util.concurrent.CancellationException cancelled)"),
            "cancellation is a terminal state and must be reported"
        );
        assertFalse(method.contains("future.cancel"), "the parent must not cancel remaining workers");
        assertFalse(method.contains(".cancel(true)"), "the parent must not interrupt remaining workers");

        final int futuresLoop = method.indexOf("for (final Future<?> future : futures)");
        final int drain = method.indexOf("drainEntityLookupCallbacks(worldData)");
        assertTrue(futuresLoop >= 0, "missing futures loop");
        assertTrue(drain > futuresLoop, "callbacks must only drain after all worker futures are terminal");
        assertTrue(
            method.contains("failure.addSuppressed(callbackFailure)"),
            "callback failures must be attached to an existing worker failure"
        );
    }

    @Test
    void drainPollsEveryCallbackAndAccumulatesFailures() throws IOException {
        final String method = extractMethod(readExecutorSource(), "private static void drainEntityLookupCallbacks");

        assertTrue(
            method.contains("isShardWorker() || TickRegionScheduler.getCurrentRegionizedWorldData() != worldData"),
            "callback drain must stay on the owning parent region thread"
        );
        assertTrue(
            method.contains("while ((callback = worldData.canvas$entityLookupCallbacks.poll()) != null)"),
            "callbacks must be drained until the queue is empty"
        );
        assertTrue(method.contains("callback.run()"), "each callback must be executed");
        assertTrue(
            method.contains("failure.addSuppressed(callbackFailure)"),
            "later callback failures must be retained"
        );
        assertTrue(
            method.contains("if (failure instanceof RuntimeException runtime) throw runtime;"),
            "runtime failures must propagate after the queue is drained"
        );
        assertTrue(
            method.contains("if (failure instanceof Error error) throw error;"),
            "errors must propagate after the queue is drained"
        );
    }

    private static String readExecutorSource() throws IOException {
        final Path source = repositoryRoot().resolve(
            "canvas-server/src/main/java/io/canvasmc/canvas/threadedregions/sharding/RegionShardExecutor.java"
        );
        assertTrue(Files.isRegularFile(source), "missing source: " + source);
        return Files.readString(source, StandardCharsets.UTF_8).replace("\r\n", "\n");
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
