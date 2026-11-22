package io.canvasmc.canvas.subcommands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.canvasmc.canvas.commands.SubCommand;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import static net.minecraft.commands.Commands.literal;

public class RegionDataCommand implements SubCommand {

    private static final int TOP_REGIONS = 5;

    @Override
    public String getDescription() {
        return "Reports region size and load distribution for region sizing analysis";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> construct(final LiteralArgumentBuilder<CommandSourceStack> base, final CommandBuildContext buildContext) {
        final LiteralArgumentBuilder<CommandSourceStack> summary = literal("summary");
        summary.executes(context -> {
            final List<RegionSample> samples = new ArrayList<>();
            for (final ServerLevel level : MinecraftServer.getServer().getAllLevels()) {
                level.regioniser.computeForAllRegionsUnsynchronised(region -> {
                    // RegionStats is backed by atomics and is safe to read off the owning tick thread.
                    final var stats = region.getData().getRegionStats();
                    samples.add(new RegionSample(
                        level.dimension().location().toString(),
                        region.getId(),
                        stats.getChunkCount(),
                        stats.getPlayerCount(),
                        stats.getEntityCount()
                    ));
                });
            }

            final StringBuilder builder = new StringBuilder();
            if (samples.isEmpty()) {
                builder.append("No active regions.");
            } else {
                final long totalChunks = samples.stream().mapToLong(sample -> sample.chunks).sum();
                final long totalPlayers = samples.stream().mapToLong(sample -> sample.players).sum();
                final long totalEntities = samples.stream().mapToLong(sample -> sample.entities).sum();

                builder.append("Region summary: ")
                    .append(samples.size()).append(" regions, ")
                    .append(totalChunks).append(" chunks, ")
                    .append(totalPlayers).append(" players, ")
                    .append(totalEntities).append(" entities\n");

                final RegionSample largest = samples.stream()
                    .max(Comparator.comparingLong((RegionSample sample) -> sample.chunks))
                    .orElseThrow();
                final double largestShare = totalChunks == 0L ? 0.0 : (100.0 * largest.chunks) / totalChunks;
                builder.append("Largest region: ").append(largest.describe())
                    .append(" - ").append(String.format("%.1f%%", largestShare)).append(" of loaded chunks\n");

                final List<RegionSample> top = new ArrayList<>(samples);
                top.sort(Comparator.comparingLong((RegionSample sample) -> sample.chunks).reversed());
                builder.append("Top regions by chunk count:\n");
                final int limit = Math.min(TOP_REGIONS, top.size());
                for (int i = 0; i < limit; i++) {
                    builder.append("  ").append(i + 1).append(". ").append(top.get(i).describe()).append('\n');
                }

                final long regionsWithPlayers = samples.stream().filter(sample -> sample.players > 0).count();
                builder.append("Regions with local players: ").append(regionsWithPlayers);
            }

            final String report = builder.toString();
            context.getSource().sendSuccess(() -> Component.literal(report), false);
            return Command.SINGLE_SUCCESS;
        });
        // TODO - entity list (with filters), player list, region profile, tile entities, mob caps, scheduling info
        return base.then(summary);
    }

    @Override
    public String getName() {
        return "regiondata";
    }

    private record RegionSample(String world, long id, int chunks, int players, int entities) {

        private String describe() {
            return "id " + id + " [" + world + "] " + chunks + " chunks, " + players + " players, " + entities + " entities";
        }
    }
}
