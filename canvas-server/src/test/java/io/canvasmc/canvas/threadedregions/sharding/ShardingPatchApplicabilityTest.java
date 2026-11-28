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
 * Dry-run validation for the block-entity snapshot (0015) and structural
 * callback drain (0016) patches.
 *
 * <p>The generated Minecraft tree is not checked in. When a generated tree is
 * present, this test copies the affected files to a temporary directory, peels
 * off whichever of the two patches are already applied, and checks and applies
 * them in order. When the starting tree already contains both patches it also
 * compares the re-applied result. The production workspace is never modified.
 * Without a generated tree only the static patch-order checks run.</p>
 */
class ShardingPatchApplicabilityTest {

    private static final String PATCH_0015 = "0015-Block-Entity-Metadata-Snapshots.patch";
    private static final String PATCH_0016 = "0016-Structural-Entity-Action-Drain.patch";

    @Test
    void patch0016ContextBuildsOnTheSnapshotFieldsAddedBy0015() throws IOException {
        final String snapshotPatch = Files.readString(patchPath(PATCH_0015), StandardCharsets.UTF_8);
        final String drainPatch = Files.readString(patchPath(PATCH_0016), StandardCharsets.UTF_8);

        assertTrue(
            snapshotPatch.contains("+    public transient io.canvasmc.canvas.threadedregions.sharding.RegionShardExecutor.TickSnapshot canvas$shardSnapshot;"),
            "0015 must add the shard snapshot fields"
        );
        assertTrue(
            snapshotPatch.contains("+    public boolean canvas$shardSnapshotActive;"),
            "0015 must add the snapshot-active flag"
        );

        // 0016 inserts the callback queue between world and the fields added by
        // 0015, so its context only matches after 0015 has been applied.
        assertTrue(
            drainPatch.contains(" // Canvas - intra-region shard snapshot scope; parent region thread writes only."),
            "0016 must carry 0015's snapshot-field context"
        );
        assertTrue(
            drainPatch.contains(" public transient io.canvasmc.canvas.threadedregions.sharding.RegionShardExecutor.TickSnapshot canvas$shardSnapshot;"),
            "0016 must apply after the 0015 snapshot fields exist"
        );
        assertTrue(
            drainPatch.contains(" public boolean canvas$shardSnapshotActive;"),
            "0016 must apply after the 0015 snapshot-active field exists"
        );
        assertFalse(
            drainPatch.contains("+    public transient io.canvasmc.canvas.threadedregions.sharding.RegionShardExecutor.TickSnapshot canvas$shardSnapshot;"),
            "0016 must not re-add the field owned by 0015"
        );
    }

    @Test
    void patches0015And0016ApplyForwardAgainstGeneratedSources() throws Exception {
        final Path sourceRoot = generatedSourceRoot();
        assumeTrue(Files.isDirectory(sourceRoot), "run applyAllPatches to validate generated sources");

        final Path patch0015 = patchPath(PATCH_0015);
        final Path patch0016 = patchPath(PATCH_0016);
        final Set<String> targets = new LinkedHashSet<>(targetPaths(patch0015));
        targets.addAll(targetPaths(patch0016));
        assertFalse(targets.isEmpty(), "the patches must declare target files");

        final Path workspace = Files.createTempDirectory("canvas-sharding-patch-roundtrip-");
        try {
            for (final String target : targets) {
                final Path source = sourceRoot.resolve(target);
                assertTrue(Files.isRegularFile(source), "missing generated source for " + target);
                final Path copy = workspace.resolve(target);
                Files.createDirectories(copy.getParent());
                Files.writeString(copy, normalize(Files.readString(source, StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
            }

            final Path forward15 = workspace.resolve("0015.patch");
            final Path forward16 = workspace.resolve("0016.patch");
            Files.writeString(forward15, normalize(Files.readString(patch0015, StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
            Files.writeString(forward16, normalize(Files.readString(patch0016, StandardCharsets.UTF_8)), StandardCharsets.UTF_8);

            final Path regionizedWorldData = workspace.resolve("io/papermc/paper/threadedregions/RegionizedWorldData.java");
            final String generatedRegionized = normalize(Files.readString(regionizedWorldData, StandardCharsets.UTF_8));
            final boolean hasSnapshotPatch = generatedRegionized.contains("canvas$shardSnapshot");
            final boolean hasDrainPatch = generatedRegionized.contains("canvas$entityLookupCallbacks");

            runGit(workspace, "init", "-q");

            // A generated tree may be at any point in the patch series: pre-0015,
            // post-0015, or post-0016. Peel off whichever patches are present so
            // the forward applicability check always starts from a known state.
            if (hasDrainPatch) {
                final GitResult reverse = git(workspace, "apply", "--reverse", "--check", "--whitespace=nowarn", forward16.toString());
                assumeTrue(
                    reverse.exitCode == 0,
                    "0016 no longer reverses cleanly (a later patch may overlap):\n" + reverse.output
                );
                runGit(workspace, "apply", "--reverse", "--whitespace=nowarn", forward16.toString());
            }
            if (hasSnapshotPatch) {
                final GitResult reverse = git(workspace, "apply", "--reverse", "--check", "--whitespace=nowarn", forward15.toString());
                assumeTrue(
                    reverse.exitCode == 0,
                    "0015 no longer reverses cleanly (a later patch may overlap):\n" + reverse.output
                );
                runGit(workspace, "apply", "--reverse", "--whitespace=nowarn", forward15.toString());
            }

            runGit(workspace, "apply", "--check", "--whitespace=nowarn", forward15.toString());
            runGit(workspace, "apply", "--whitespace=nowarn", forward15.toString());
            runGit(workspace, "apply", "--check", "--whitespace=nowarn", forward16.toString());
            runGit(workspace, "apply", "--whitespace=nowarn", forward16.toString());

            final String applied = normalize(Files.readString(regionizedWorldData, StandardCharsets.UTF_8));
            assertTrue(applied.contains("canvas$shardSnapshot"), "0015 did not add its snapshot fields");
            assertTrue(applied.contains("canvas$entityLookupCallbacks"), "0016 did not add its callback queue");

            if (hasDrainPatch) {
                for (final String target : targets) {
                    assertEquals(
                        normalize(Files.readString(sourceRoot.resolve(target), StandardCharsets.UTF_8)),
                        normalize(Files.readString(workspace.resolve(target), StandardCharsets.UTF_8)),
                        "re-applying 0015 and 0016 changed " + target
                    );
                }
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
