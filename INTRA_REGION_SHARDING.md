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

## Milestone 3: strict shard checks and layout tooling

Added opt-in strict shard ownership checks:

```yaml
shard-strict-checks: false
```

When enabled, shard workers only pass ownership checks for chunks/entities inside
their assigned shard. The default remains off until cross-shard query routing is
implemented.

Added `/canvas regiondata shards <id>` to preview the spatial shard layout and
whether strict checks are active.

Runtime probe with strict checks enabled:

```text
124 scheduler heartbeats: 0 ms, worker dispatch successful
525 despawn checks: 1 ms, worker dispatch successful
ownership errors: 0
```

## Milestone 4: cross-shard entity query snapshots

Each shard batch now captures an immutable snapshot of the parent region's entity
bounds before workers start. Cross-shard `getEntities` queries are answered from
that snapshot instead of reading live Moonrise slices or blocking the parent
thread.

This gives a consistent one-tick view for AI/plugin queries while preserving
chunk ownership. Queries outside the parent region still return only entities
from that region, matching Folia's normal ownership model.

Runtime probe:

```text
650 cross-shard queries from workers
404,300 entity hits
89 ms total
ownership errors: 0
```

## Next milestones

1. Shard a safe subset of full vanilla entity ticks (no passengers/vehicles).
2. Add POI and block-entity snapshot/routing support.
3. Expand to general entity AI and block-entity ticking only after integration
   tests with real players and representative plugins.

## Safety

This feature is default-off. Entity scheduler tasks remain sequential per entity,
but different entities in the same region may now execute concurrently. Plugins
that assume a region is globally single-threaded must be tested before enabling
it.
