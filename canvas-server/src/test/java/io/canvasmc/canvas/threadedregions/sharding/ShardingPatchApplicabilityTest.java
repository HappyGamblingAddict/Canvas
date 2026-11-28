package io.canvasmc.canvas.threadedregions.sharding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Dry-run validation for the shard snapshot/callback queue and structural
 * action sharding patches as ported to the 26.2 feature-patch series.
 *
 * <p>The generated Minecraft tree is not checked in. When a generated tree is
 * present, this test copies the affected files to a temporary directory, peels
 * off whichever of the two patches are already applied, and checks and applies
 * them in order. When the starting tree already contains both patches it also
 * compares the re-applied result. The production workspace is never modified.
 * Without a generated tree only the static patch-order checks run.</p>
 */
class ShardingPatchApplicabilityTest {

    private static final String PATCH_SNAPSHOT = "0009-Shard-Snapshot-And-Callback-Queue.patch";
    private static final String PATCH_STRUCTURAL = "0005-Entity-Despawn-And-Structural-Action-Sharding.patch";
    private static final String PATCH_0015 = PATCH_SNAPSHOT;
    private static final String PATCH_0016 = PATCH_STRUCTURAL;

    @Test
    void snapshotPatchOwnsTheFieldsAndStructuralPatchAddsTheShardedTickMethod() throws IOException {
        final String snapshotPatch = Files.readString(patchPath(PATCH_SNAPSHOT), StandardCharsets.UTF_8);
        final String structuralPatch = Files.readString(patchPath(PATCH_STRUCTURAL), StandardCharsets.UTF_8);

        assertTrue(
            snapshotPatch.contains("+    public transient io.canvasmc.canvas.threadedregions.sharding.RegionShardExecutor.TickSnapshot canvas$shardSnapshot;"),
            "the snapshot patch must add the shard snapshot field"
        );
        assertTrue(
            snapshotPatch.contains("+    public boolean canvas$shardSnapshotActive;"),
            "the snapshot patch must add the snapshot-active flag"
        );
        assertTrue(
            snapshotPatch.contains("+    public final java.util.concurrent.ConcurrentLinkedQueue<Runnable> canvas$entityLookupCallbacks ="),
            "the snapshot patch must own the per-region callback queue"
        );

        assertTrue(
            structuralPatch.contains("+    public void tickShardedNonPassenger(final Entity entity) {"),
            "the structural patch must add ServerLevel.tickShardedNonPassenger"
        );
        assertTrue(
            structuralPatch.contains("+        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(\"Cannot tick an entity off-main\");"),
            "the sharded tick method must retain its ownership check"
        );
        assertFalse(
            structuralPatch.contains("canvas$shardSnapshot"),
            "the structural patch must not re-add fields owned by the snapshot patch"
        );
    }

    @Test
    void finalShardingPatchRoundTripsAgainstGeneratedSources() throws Exception {
        final Path sourceRoot = generatedSourceRoot();
        assumeTrue(Files.isDirectory(sourceRoot), "run applyAllPatches to validate generated sources");

        final Path finalPatch = patchPath("0021-Adapt-sharding-patches-to-26.2-entity-and-collection.patch");
        final Set<String> targets = targetPaths(finalPatch);
        assertFalse(targets.isEmpty(), "the patch must declare target files");

        final Path workspace = Files.createTempDirectory("canvas-sharding-patch-roundtrip-");
        try {
            for (final String target : targets) {
                final Path source = sourceRoot.resolve(target);
                assertTrue(Files.isRegularFile(source), "missing generated source for " + target);
                final Path copy = workspace.resolve(target);
                Files.createDirectories(copy.getParent());
                Files.writeString(copy, normalize(Files.readString(source, StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
            }

            final Path patchCopy = workspace.resolve("final.patch");
            Files.writeString(patchCopy, normalize(Files.readString(finalPatch, StandardCharsets.UTF_8)), StandardCharsets.UTF_8);

            runGit(workspace, "init", "-q");
            runGit(workspace, "apply", "--reverse", "--check", "--whitespace=nowarn", patchCopy.toString());
            runGit(workspace, "apply", "--reverse", "--whitespace=nowarn", patchCopy.toString());
            runGit(workspace, "apply", "--check", "--whitespace=nowarn", patchCopy.toString());
            runGit(workspace, "apply", "--whitespace=nowarn", patchCopy.toString());

            for (final String target : targets) {
                assertEquals(
                    normalize(Files.readString(sourceRoot.resolve(target), StandardCharsets.UTF_8)),
                    normalize(Files.readString(workspace.resolve(target), StandardCharsets.UTF_8)),
                    "round-tripping the final sharding patch changed " + target
                );
            }
        } finally {
            deleteRecursively(workspace);
        }
    }

    private static void runGit(final Path directory, final String... arguments) throws IOException, InterruptedException {
        final GitResult result = git(directory, arguments);
        assertEquals(
            0,
            result.exitCode,
            () -> "git command failed: " + String.join(" ", arguments) + "\n" + result.output
        );
    }

    private static GitResult git(final Path directory, final String... arguments) throws IOException, InterruptedException {
        final List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(directory.toString());
        command.addAll(List.of(arguments));

        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output;
        try (InputStream input = process.getInputStream()) {
            output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("git command timed out: " + String.join(" ", command));
        }
        return new GitResult(process.exitValue(), output);
    }

    private record GitResult(int exitCode, String output) {
    }

    private static Set<String> targetPaths(final Path patch) throws IOException {
        final Set<String> targets = new LinkedHashSet<>();
        for (final String line : Files.readAllLines(patch, StandardCharsets.UTF_8)) {
            if (line.startsWith("+++ b/")) {
                targets.add(line.substring("+++ b/".length()));
            }
        }
        return targets;
    }

    private static Path patchPath(final String name) {
        final Path root = repositoryRoot().resolve("canvas-server/minecraft-patches");
        final Path feature = root.resolve("features").resolve(name);
        return Files.isRegularFile(feature) ? feature : root.resolve("base").resolve(name);
    }

    private static Path generatedSourceRoot() {
        final Path root = repositoryRoot();
        final Path canvasMinecraft = root.resolve("canvas-server/src/minecraft/java");
        if (Files.isDirectory(canvasMinecraft)) return canvasMinecraft;
        final Path paperServer = root.resolve("paper-server/src/minecraft/java");
        if (Files.isDirectory(paperServer)) return paperServer;
        return root.resolve("paper-server/src/main/java");
    }

    private static String normalize(final String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static void deleteRecursively(final Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
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
