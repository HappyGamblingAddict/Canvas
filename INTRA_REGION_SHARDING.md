# Intra-region sharding (experimental)

This branch explores BetterMultiThreaded-style parallel ticking **inside** one
Folia region. Unlike live region splitting, sharding does not move chunks or
change region ownership.

## Milestone 1: entity scheduler sharding

The first implementation partitions `EntityScheduler` heartbeats across a
worker pool while keeping the parent region/world context. Vanilla entity and
block ticking are unchanged.

Configuration under `threaded-regions`:

```yaml
entity-scheduler-shards: 1
entity-scheduler-shard-threshold: 128
```

- `entity-scheduler-shards` is clamped to `1..8`; `1` disables sharding.
- Regions below `entity-scheduler-shard-threshold` schedulers use the normal
  serial loop.
- Shard workers are daemon threads and carry the parent region/world data so
  existing Folia ownership checks continue to apply.
- Failures are propagated back to the region thread rather than silently lost.

## Runtime probe

An isolated probe created 124 entity schedulers in one region and invoked the
shard executor directly:

```text
2 shards: 124 executions, 3 ms
4 shards: 124 executions, 2 ms
workers: Canvas Region Shard Worker #1 ... #6
ownership errors: 0
```

The small workload is not a performance benchmark; it verifies worker dispatch,
thread context, scheduler execution, and ownership checks.

## Milestone 2: parallel despawn checks

The worker pool now also supports arbitrary per-entity phases. The first vanilla
phase is `checkDespawn`, which runs in parallel while actual entity ticking stays
serial.

```yaml
entity-despawn-shards: 1
entity-despawn-shard-threshold: 128
```

Only entities in the region's ticking list are passed to the phase. Removed or
frozen entities are skipped, and the serial tick loop skips `checkDespawn` when
the parallel phase ran.

Runtime probe with 399 entities:

```text
2 shards: 399 executions, 5 ms
4 shards: 399 executions, 3 ms
ownership errors: 0
```

## Next milestones

1. Add stricter shard-aware ownership checks for cross-shard entity queries.
2. Shard a safe subset of full vanilla entity ticks (no passengers/vehicles).
3. Add routing for cross-shard `getEntities`, POI, and block-entity access.
4. Expand to general entity AI and block-entity ticking only after integration
   tests with real players and representative plugins.

## Safety

This feature is default-off. Entity scheduler tasks remain sequential per entity,
but different entities in the same region may now execute concurrently. Plugins
that assume a region is globally single-threaded must be tested before enabling
it.
