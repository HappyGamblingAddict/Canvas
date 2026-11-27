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

## Milestone 5: restricted full item ticking

The first full vanilla tick subset is live: item entities without vehicles or
passengers, and only when their predicted movement stays inside one shard.

```yaml
item-entity-shards: 1
item-entity-shard-threshold: 64
```

This mode requires `shard-strict-checks: true`. Items that are removed, frozen,
riding, passenger-carrying, non-ticking, or likely to cross a shard boundary stay
on the serial region loop.

Runtime probe with real item entities:

```text
101 items ticked across 4 workers: 7 ms
repeated ticks: 99, 99, 99, 98, 97 items as merges completed
ownership errors: 0
```

## Milestone 6: safety hardening and XP orbs

The mob-AI audit prompted prerequisite fixes before expanding the whitelist:

- Frozen entities are excluded from full tick sharding.
- Swept bounding boxes, not just entity centers, must stay inside one shard.
- Worker admission rechecks removal, portal, physics, vehicle/passenger, ticking,
  frozen, and shard ownership immediately before execution.
- Item and XP merges are restricted to same-shard targets.
- Duplicate snapshot query hooks were removed.
- Snapshot queries ignore entities removed earlier in the same batch.
- Item/XP movement sweeps reject unloaded/non-FULL chunks and pathological
  velocity/span values, preventing worker-side synchronous chunk loading.

XP orb full ticking is now available:

```yaml
experience-orb-shards: 1
experience-orb-shard-threshold: 64
```

XP admission additionally rejects nearby-player influence, water/lava, collisions,
and merge queries that can see cross-shard or non-ticking orbs. Merge-neighbour
eligibility is solved to a fixed point before dispatch.

Combined runtime probe on the integrated build:

```text
item ticks: repeated worker execution, no chunk-loading/ownership errors
XP spread: 96 executions, 0 errors
XP pairs: 96 executions, 0 errors
XP moving: 32 executions, 0 errors
```

The earlier real-player ViaProxy harness also joined and moved nine chunks with
strict four-shard scheduler/item/despawn settings active; a rerun on the combined
build was inconclusive because ViaProxy raced backend startup.

## Milestone 7: block-entity metadata snapshots

Added an opt-in immutable metadata view for block entities in the parent region:

```yaml
shard-metadata-snapshots: false
```

The snapshot is captured lazily on the parent region thread and reused by the
world-tick shard phases. It stores only immutable positions, block-entity types,
and removal flags; it never exposes live block entities, inventories, NBT, or
ticker references. `RegionShardExecutor.canQueryBlockEntities(world)` distinguishes
an unavailable snapshot from a position that is absent.

Cross-shard live block-entity access is rejected independently of the snapshot
setting. Guards cover `Level`, direct `LevelChunk` access, bucket lookups, map
views, position sets, and iteration. Same-shard access remains live. Low-level
block-entity setters/removers are synchronous APIs, so cross-shard mutations
throw instead of returning before a queued mutation completes. The explicit
`runOrRoute` helper remains available for higher-level operations that can safely
defer the whole mutation and do not wait for its result.

All block-entity tickers and hoppers remain on the parent region thread, with
defense-in-depth worker checks. The executor now drains every worker future
before propagating a failure, and tiny regions reduce their effective shard
count instead of creating overlapping shard cells.

Runtime validation passed on the hardened build with four shards, 64 chests and
64 pigs spread across 289 loaded region chunks:

```text
tasks=111 reads=7104 sameShardLiveReads=66 crossShardRejections=16
unexpectedLiveReads=0 directRejections=5 unexpectedDirectReads=0
routed=1 routeExecutions=1 removedNextTick=true staleSnapshotReads=0
```

The probe forced plugin chunk tickets and loaded the fixture through an
asynchronous chunk-system future, so the region thread was never blocked. It
verified snapshot reads, same-shard live reads, cross-shard live rejection,
bucket/map/iteration bypass rejection, queued whole-operation routing, block
removal on the next region task, and a fresh snapshot without the removed entry.
Serial hopper/ticker soak testing is still pending.

## Milestone 8: structural entity-action drain

Shard workers no longer perform structural entity-lookup moves or removals
against live shared state. Those callbacks are queued per
`RegionizedWorldData`, with duplicate-removal suppression, and drained on the
owning parent region only after every worker future in the batch has finished.
Worker-side entity adds are rejected before they can mutate spawn or lookup
state.

The executor's failure path now waits for all submitted workers instead of
cancelling one and racing the parent drain. Callback failures are accumulated
and propagated after the rest of the queue has drained.

`ServerLevel.tickShardedNonPassenger` is available for future Rank-A-style
canaries, but is not wired to any entity type. It deliberately omits portal
handling, post-tick portal checks, and passenger recursion, and rejects players.

A clean `--rerun-tasks` compile of the generated source tree passes. Runtime
canary tests, mutation stress tests, and portal/passenger edge cases remain
required before enabling it.

## Milestone 9: POI metadata snapshots

Added an opt-in, fail-closed POI point-metadata view to the same per-tick
`RegionShardSnapshot` lifecycle:

- `PoiEntry` stores only an immutable position and POI type holder.
- `PoiSnapshot` stores immutable entries plus the exact set of loaded POI
  sections it covers.
- Capture runs on the owning parent region thread and uses only already-loaded
  `PoiChunk` sections; it never calls `getOrLoad` or loads POI data.
- Worker point reads (`getType`, `exists`) are served from the snapshot only when
  the section is covered. A covered but empty section is authoritative absence;
  an uncovered section fails closed.
- Live POI sections, records, ticket acquisition/release, nearest searches,
  owner state, mutations, and `PoiAccess` search endpoints are rejected on shard
  workers. Parent-thread behavior is unchanged.

Patch `0017-Poi-Metadata-Snapshots.patch` carries the generated-source changes.
Runtime POI validation and mixed villager/plugin tests remain required before
enabling metadata snapshots for production.

## Milestone 10: hopper and block-entity soak

A disposable Canvas server ran four chest -> hopper -> dropper rigs, four
furnaces, and 96 entity-scheduler canaries across 16 ticketed chunks with four
entity-scheduler shards enabled:

```text
result=PASS ticks.soak=240 maxGapMs=765 shard.heartbeats=23136
workers=8 blockEntity.maxConcurrentEvents=1
pulls=16 pushes=16 smelts=1 per rig
exceptions=0 crossShard=0 cleanup.ok=true
```

Hopper and furnace callbacks all ran on the parent `Folia Region Scheduler
Thread #0`; they never ran on a Canvas shard worker. Item conservation and
inventory movement were correct on every rig. The sharding test suite also
passes all 14 tests, including POI snapshot and structural drain checks.

## Milestone 11: local real-client and villager/POI validation

The preview JAR was tested on the local Windows machine (Java 25) instead of
the KVM: a disposable Canvas server, ViaProxy 3.4.14, and a Mineflayer
1.21.11 client, with a four-shard POI/villager probe.

```text
bot:   PASS joined, idle ticks, 144-block/9-chunk move, commands, tick_end
probe: PASS batches=80 tasks=2585 snapshotReads=41360 emptyReads=41360
       liveRejections=320 villagers=32/32 livePois=16/16
       unexpectedLiveReads=0 snapshotMisses=0 serverExceptions=0
       watchdog=0 crossShard=0
```

The probe spawned 16 workstations and 32 villagers across 16 chunks, kept the
villagers ticking with AI, and ran 80 four-shard batches. Every worker read the
captured POI view; live POI searches, `getOrLoad`, uncovered sections, and
direct snapshot misses all failed closed as intended. Villager professions,
entity counts, and POIs survived the run.

### Region-count finding

`/canvas regiondata summary` was counting **empty section reservations** as
regions. On the production spawn server with 3 players it showed 46-54
"regions", but only 1-2 had chunks; the rest had 0 chunks, 0 players, and 0
entities. Automatic live splitting was off and the thresholds were 30 players /
300 entities, so these were not live splits.

Two changes address the misleading count and the one real leak:

- `0018-Prune-Empty-Regions.patch` kills a released region that has no
  sections and no pending merges instead of leaving it `READY` forever.
- `RegionDataCommand` now reports `active regions`, `empty section
  reservations`, and `zero-section entries` separately, and ranks only active
  regions.

After applying both to the local preview server, the same workload reported:

```text
Region summary: 1 active regions, 81 empty section reservations,
0 zero-section entries, 325 chunks, 1 players, 33 entities
```

so the remaining reservations are spatial halos, not extra ticking regions.

## Next milestones

1. Extend real-player/plugin coverage beyond the vanilla probe (production
   plugins, NPCs, portals, teleports, and chunk unload/reload cycles).
2. Add targeted tests for frozen entities, boundary sweeps, unloaded chunks,
   nearby players, water/lava, and high velocity.
3. Evaluate the Rank-A canary entity types from the mob-AI safety matrix using
   `tickShardedNonPassenger`.
4. Fix and validate the clean `applyAllPatches` path in an environment where
   Paper's resource-patch checkout is available.
5. Build and publish a preview with `0018-Prune-Empty-Regions.patch` and the
   active-region reporting, then verify the production spawn count drops to
   the actual active regions.

## Safety

This feature is default-off. Entity scheduler tasks remain sequential per entity,
but different entities in the same region may now execute concurrently. Plugins
that assume a region is globally single-threaded must be tested before enabling
it.
