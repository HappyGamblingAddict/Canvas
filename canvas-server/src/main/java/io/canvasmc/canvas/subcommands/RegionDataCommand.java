package io.canvasmc.canvas.subcommands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.canvasmc.canvas.commands.SubCommand;
import io.canvasmc.canvas.threadedregions.sharding.RegionShardExecutor;
import io.papermc.paper.configuration.GlobalConfiguration;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class RegionDataCommand implements SubCommand {

    private static final int TOP_REGIONS = 5;
    private static final int DEFAULT_SCAN_LIMIT = 10;
    private static final int MAX_SCAN_LIMIT = 50;
    private static final SimpleCommandExceptionType UNKNOWN_REGION = new SimpleCommandExceptionType(
        Component.literal("No loaded region exists with that id")
    );
    private static final SimpleCommandExceptionType AMBIGUOUS_REGION = new SimpleCommandExceptionType(
        Component.literal("That region id exists in multiple worlds; use /canvas regiondata scan to identify the world")
    );

    @Override
    public String getDescription() {
        return "Reports region load, previews safe splits, and controls live splitting";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> construct(final LiteralArgumentBuilder<CommandSourceStack> base, final CommandBuildContext buildContext) {
        final LiteralArgumentBuilder<CommandSourceStack> summary = literal("summary");
        summary.executes(RegionDataCommand::summary);

        final LiteralArgumentBuilder<CommandSourceStack> scan = literal("scan");
        scan.executes(context -> scan(context, DEFAULT_SCAN_LIMIT))
            .then(argument("limit", IntegerArgumentType.integer(1, MAX_SCAN_LIMIT))
                .executes(context -> scan(context, IntegerArgumentType.getInteger(context, "limit"))));

        final LiteralArgumentBuilder<CommandSourceStack> split = literal("split");
        split.then(argument("id", IntegerArgumentType.integer(0))
            .executes(context -> split(context, false))
            .then(literal("confirm")
                .executes(context -> split(context, true))));

        final LiteralArgumentBuilder<CommandSourceStack> shards = literal("shards");
        shards.then(argument("id", IntegerArgumentType.integer(0))
            .executes(RegionDataCommand::shards));

        final LiteralArgumentBuilder<CommandSourceStack> auto = literal("auto");
        auto.executes(context -> auto(context, null))
            .then(literal("on").executes(context -> auto(context, true)))
            .then(literal("off").executes(context -> auto(context, false)));

        return base.then(summary).then(scan).then(split).then(shards).then(auto);
    }

    @Override
    public String getName() {
        return "regiondata";
    }

    private static int summary(final CommandContext<CommandSourceStack> context) {
        final List<RegionSample> samples = collectSamples();
        final List<RegionSample> active = samples.stream()
            .filter(sample -> sample.chunks > 0 || sample.players > 0 || sample.entities > 0)
            .toList();
        final long zeroSection = samples.stream().filter(sample -> sample.sections == 0).count();
        final long emptyReservations = samples.size() - active.size() - zeroSection;

        final StringBuilder builder = new StringBuilder();
        if (active.isEmpty()) {
            builder.append("No active regions.");
            if (emptyReservations > 0) {
                builder.append(' ').append(emptyReservations).append(" empty section reservations.");
            }
            if (zeroSection > 0) {
                builder.append(' ').append(zeroSection).append(" zero-section regionizer entries.");
            }
        } else {
            final long totalChunks = active.stream().mapToLong(sample -> sample.chunks).sum();
            final long totalPlayers = active.stream().mapToLong(sample -> sample.players).sum();
            final long totalEntities = active.stream().mapToLong(sample -> sample.entities).sum();

            builder.append("Region summary: ")
                .append(active.size()).append(" active regions, ")
                .append(emptyReservations).append(" empty section reservations, ")
                .append(zeroSection).append(" zero-section entries, ")
                .append(totalChunks).append(" chunks, ")
                .append(totalPlayers).append(" players, ")
                .append(totalEntities).append(" entities\n");

            final RegionSample largest = active.stream()
                .max(Comparator.comparingLong((RegionSample sample) -> sample.chunks))
                .orElseThrow();
            final double largestShare = totalChunks == 0L ? 0.0 : (100.0 * largest.chunks) / totalChunks;
            builder.append("Largest region: ").append(largest.describe())
                .append(" - ").append(String.format("%.1f%%", largestShare)).append(" of loaded chunks\n");

            final List<RegionSample> top = new ArrayList<>(active);
            top.sort(Comparator.comparingLong((RegionSample sample) -> sample.chunks).reversed());
            builder.append("Top regions by chunk count:\n");
            final int limit = Math.min(TOP_REGIONS, top.size());
            for (int i = 0; i < limit; i++) {
                builder.append("  ").append(i + 1).append(". ").append(top.get(i).describe()).append('\n');
            }

            final long regionsWithPlayers = active.stream().filter(sample -> sample.players > 0).count();
            builder.append("Regions with local players: ").append(regionsWithPlayers);
        }

        final String report = builder.toString();
        context.getSource().sendSuccess(() -> Component.literal(report), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int scan(final CommandContext<CommandSourceStack> context, final int limit) {
        final GlobalConfiguration.ThreadedRegions config = GlobalConfiguration.get().threadedRegions;
        final io.canvasmc.canvas.GlobalConfiguration.RegionScheduler.AutoSplit auto =
            io.canvasmc.canvas.GlobalConfiguration.getInstance().regionScheduler.autoSplit;
        final List<RegionSample> samples = collectSamples();
        final List<RegionSample> active = samples.stream()
            .filter(sample -> sample.chunks > 0 || sample.players > 0 || sample.entities > 0)
            .toList();
        final long zeroSection = samples.stream().filter(sample -> sample.sections == 0).count();
        final long emptyReservations = samples.size() - active.size() - zeroSection;
        active.sort(Comparator
            .comparingLong((RegionSample sample) -> (long) sample.players * 1_000_000L + sample.entities)
            .thenComparingLong(sample -> sample.chunks)
            .reversed());

        final StringBuilder builder = new StringBuilder();
        builder.append("Live split: ").append(config.enableLiveSplit ? "on" : "off")
            .append(", shards: ").append(config.liveSplitShards)
            .append(", min sections/shard: ").append(config.liveSplitMinSectionsPerShard)
            .append(", static thresholds: ").append(config.maxPlayersPerRegion).append(" players / ")
            .append(config.maxEntitiesPerRegion).append(" entities\n");
        builder.append("Auto split: ").append(auto.enabled ? "on" : "off")
            .append(", trigger mspt: ").append(auto.maxMspt)
            .append(", min load: ").append(auto.minPlayers).append(" players / ")
            .append(auto.minEntities).append(" entities")
            .append(", sustain: ").append(auto.sustainTicks).append(" ticks")
            .append(", cooldown: ").append(auto.cooldownSeconds).append("s")
            .append(", hotspot: ").append(auto.hotspotShare * 100.0D).append("%")
            .append(", clearance: ").append(auto.boundaryClearanceSections).append(" sections")
            .append(", max regions: ").append(auto.maxRegions == 0 ? "unlimited" : auto.maxRegions)
            .append(", player regions: ").append(auto.allowPlayerRegions ? "enabled" : "disabled")
            .append('\n');
        if (active.isEmpty()) {
            builder.append("No active regions.");
            if (emptyReservations > 0) {
                builder.append(' ').append(emptyReservations).append(" empty section reservations.");
            }
            if (zeroSection > 0) {
                builder.append(' ').append(zeroSection).append(" zero-section regionizer entries.");
            }
        } else {
            builder.append("Top ").append(Math.min(limit, active.size())).append(" regions by load:\n");
            for (int i = 0; i < Math.min(limit, active.size()); i++) {
                final RegionSample sample = active.get(i);
                final boolean overloaded = (config.maxPlayersPerRegion > 0 && sample.players > config.maxPlayersPerRegion)
                    || (config.maxEntitiesPerRegion > 0 && sample.entities > config.maxEntitiesPerRegion);
                builder.append("  ").append(i + 1).append(". ").append(sample.describe())
                    .append(sample.ticking ? " [ticking]" : " [idle]")
                    .append(overloaded ? " [over threshold]" : "").append('\n');
            }
            if (emptyReservations > 0 || zeroSection > 0) {
                builder.append("Empty section reservations: ").append(emptyReservations)
                    .append(", zero-section entries: ").append(zeroSection).append('\n');
            }
        }

        final String report = builder.toString();
        context.getSource().sendSuccess(() -> Component.literal(report), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int split(final CommandContext<CommandSourceStack> context, final boolean confirm) throws CommandSyntaxException {
        final int id = IntegerArgumentType.getInteger(context, "id");
        final LocatedRegion located = findRegion(id);
        final ThreadedRegionizer.LiveSplitPreview preview = located.region.regioniser.previewLiveSplit(located.region);

        if (!confirm) {
            final StringBuilder builder = new StringBuilder();
            builder.append("Split preview for region ").append(id).append(" [").append(located.world).append("]:\n")
                .append("  ").append(preview.sections()).append(" sections, ").append(preview.chunks()).append(" chunks, ")
                .append(preview.players()).append(" players, ").append(preview.entities()).append(" entities\n")
                .append("  Proposed shards: ").append(preview.shards()).append("\n")
                .append("  Safety check: ").append(preview.reason()).append('\n');
            if (preview.eligible()) {
                builder.append("Run /canvas regiondata split ").append(id).append(" confirm to ")
                    .append(preview.immediate() ? "split it now." : "queue it for end-of-tick.");
            }
            context.getSource().sendSuccess(() -> Component.literal(builder.toString()), false);
            return Command.SINGLE_SUCCESS;
        }

        if (!preview.eligible()) {
            throw new SimpleCommandExceptionType(Component.literal("Split rejected: " + preview.reason())).create();
        }
        final boolean accepted = located.region.regioniser.requestLiveSplit(located.region);
        if (!accepted) {
            throw new SimpleCommandExceptionType(Component.literal("Split rejected: region changed before the request was accepted")).create();
        }
        context.getSource().sendSuccess(
            () -> Component.literal(preview.immediate()
                ? "Split for region " + id + " completed."
                : "Split for region " + id + " queued. It will run at the end of its next eligible tick; check the console for completion."),
            false
        );
        return Command.SINGLE_SUCCESS;
    }

    private static int shards(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        final int id = IntegerArgumentType.getInteger(context, "id");
        final LocatedRegion located = findRegion(id);
        final GlobalConfiguration.ThreadedRegions config = GlobalConfiguration.get().threadedRegions;
        final int requested = config.entityDespawnShards > 1 ? config.entityDespawnShards
            : (config.entitySchedulerShards > 1 ? config.entitySchedulerShards : 2);
        final List<RegionShardExecutor.Shard> layout = RegionShardExecutor.createShards(located.region, requested);
        final StringBuilder builder = new StringBuilder();
        builder.append("Shard layout for region ").append(id).append(" [").append(located.world).append("]: ")
            .append(layout.size()).append(" shards, strict checks ")
            .append(config.shardStrictChecks ? "on" : "off").append('\n');
        for (final RegionShardExecutor.Shard shard : layout) {
            builder.append("  ").append(shard.index() + 1).append(". sections x=")
                .append(shard.minSectionX()).append("..").append(shard.maxSectionX())
                .append(" z=").append(shard.minSectionZ()).append("..").append(shard.maxSectionZ()).append('\n');
        }
        context.getSource().sendSuccess(() -> Component.literal(builder.toString()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int auto(final CommandContext<CommandSourceStack> context, final Boolean enabled) {
        final GlobalConfiguration.ThreadedRegions config = GlobalConfiguration.get().threadedRegions;
        if (enabled != null) {
            config.enableLiveSplit = enabled;
        }
        final String state = config.enableLiveSplit ? "enabled" : "disabled";
        context.getSource().sendSuccess(
            () -> Component.literal("Automatic live splitting is " + state
                + " (runtime setting; edit threaded-regions.enable-live-split to persist)."),
            false
        );
        return Command.SINGLE_SUCCESS;
    }

    private static List<RegionSample> collectSamples() {
        final List<RegionSample> samples = new ArrayList<>();
        for (final ServerLevel level : MinecraftServer.getServer().getAllLevels()) {
            level.regioniser.computeForAllRegionsUnsynchronised(region -> {
                final var stats = region.getData().getRegionStats();
                double mspt;
                try {
                    mspt = region.getData().getMSPT(io.canvasmc.canvas.region.RegionTickData.Frame._15_SECONDS);
                } catch (final Throwable ignored) {
                    mspt = 0.0D;
                }
                samples.add(new RegionSample(
                    level.dimension().identifier().toString(),
                    region.getId(),
                    level.regioniser.getSectionCount(region),
                    stats.getChunkCount(),
                    stats.getPlayerCount(),
                    stats.getEntityCount(),
                    region.isTicking(),
                    mspt
                ));
            });
        }
        return samples;
    }

    private static LocatedRegion findRegion(final int id) throws CommandSyntaxException {
        final List<LocatedRegion> matches = new ArrayList<>();
        for (final ServerLevel level : MinecraftServer.getServer().getAllLevels()) {
            level.regioniser.computeForAllRegionsUnsynchronised(region -> {
                if (region.getId() == id) {
                    matches.add(new LocatedRegion(level.dimension().identifier().toString(), region));
                }
            });
        }
        if (matches.isEmpty()) {
            throw UNKNOWN_REGION.create();
        }
        if (matches.size() > 1) {
            throw AMBIGUOUS_REGION.create();
        }
        return matches.getFirst();
    }

    private record RegionSample(String world, long id, int sections, int chunks, int players, int entities,
                                boolean ticking, double mspt) {

        private String describe() {
            return "id " + id + " [" + world + "] " + sections + " sections, " + chunks + " chunks, "
                + players + " players, " + entities + " entities, " + String.format("%.1f", mspt) + "ms";
        }
    }

    private record LocatedRegion(
        String world,
        ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region
    ) {}
}
