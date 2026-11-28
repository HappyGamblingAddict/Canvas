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
passes all 17 tests, including POI snapshot and structural drain checks, from
a clean generated tree (`applyAllPatches` then `compileTestJava`).

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

### Region-boundary movement block

The local real-client test also reproduced a separate movement bug. With
`max-region-sections: 100`, a client flying east stopped at the first section
boundary and was rubber-banded every tick. `PlayerFailMoveEvent` reported
`CLIPPED_INTO_BLOCK` at the same position, and `/canvas regiondata summary`
showed four active regions plus 96 empty section reservations.

The cause is the collision ownership check in `CollisionUtil`: chunks that are
not owned by the player's current region are treated as solid (`chunkRad = 4`).
When the region-size cap splits the halo into separate regions, the boundary
becomes an invisible wall before the region transfer can happen.

With `max-region-sections: 0` on the same preview JAR, the same 144-block /
9-chunk flight passed and the server reported one region with zero empty
reservations. Spawn has `max-region-sections: 0` staged in
`paper-global.yml` (restart required).

`0021-Region-Boundary-Collision.patch` fixes the root cause instead of hiding
it. `CollisionUtil` now uses an exact section-level ownership check
(`chunkRad 0`) and skips chunks owned by another region rather than inserting a
solid `getBoxForChunk(...)` AABB. With `max-region-sections: 4`, the local
harness dropped from **579** `CLIPPED_INTO_BLOCK` rejects to **0**, and the bot
crossed the first boundary (x≈62.5 → x≈124.5). The flight still stops at the
next boundary because of the separate player-transfer race above; that fix is
still required before live splits with players are safe.

Tradeoff: block collisions inside a neighbouring region's section are ignored
for the short window before handoff, so a player can briefly clip through
blocks there. The alternative is the hard wall. `checkOnly` also reports those
chunks as free. This is acceptable for a region boundary that the player is
about to cross, but it should be covered by the staging soak.

### Region caps versus the player halo

Removing the collision wall was not enough. With `max-region-sections: 4` the
bot still stopped at the next boundary with `MOVED_INTO_UNLOADED_CHUNK` and
then `out of region teleport accept`. The cause is the empty halo, not the
teleport packet:

- A player's chunk loader can only send chunks owned by the player's region
  (`RegionizedPlayerChunkLoader` breaks when `isTickThreadFor` fails).
- Folia keeps a region crossable by giving it an empty halo. New chunks load
  into that halo, extend the region, and merge it with a neighbour at
  `region-section-merge-radius` before the player reaches the boundary.
- The section cap created a fresh region for the new halo sections
  (`ThreadedRegionizer.addChunk`), so the player's region lost the halo and the
  boundary was only two chunks away. The regions then could not merge because
  the cap would be exceeded again.

`0022-Player-Safe-Region-Caps.patch` makes the cap apply only to player-free
regions. `addChunk` no longer creates a separate capped region when any nearby
region has players, and `onRegionRelease` does not split a region that still
contains a player. A player region may grow past the cap; load-aware auto-split,
which refuses player regions by default, is the only safe way to reduce it.

Local result with `max-region-sections: 4`, collision fix + player-safe caps:
one region (196 sections, 289 chunks), the real client flew the full 144 blocks
/ 9 chunks, no wall, no disconnect. The region grew to 196 sections, which also
shows why the old section cap was fragmenting spawn halos so aggressively.

### Load-aware auto-split

`region-scheduler.auto-split` in `canvas-server.yml` now drives splitting from
real region load instead of a section count:

- 15-second region MSPT (`getMSPT`) is the primary trigger.
- The player/entity load gates prevent a tiny region from splitting just because
  it had one slow tick.
- Overload must be sustained for `sustain-ticks`, then a cooldown and a global
  one-split-at-a-time guard apply.
- A section holding `hotspot-share` of the players is logged and skipped;
  region splitting cannot divide one crowded section.
- The proposed boundary must be at least `boundary-clearance-sections` away
  from every player section.
- `max-regions` stops a split cascade.
- `allow-player-regions` defaults to `false` for this preview: splitting a
  region with a player exposed a pending-teleport transfer race that could
  disconnect the player. Entity-only regions can split automatically.
- `threaded-regions.max-region-sections` is now a safety cap, default `0`
  (unlimited). The old player/entity static thresholds are fallback overrides
  and no longer fragment regions when auto-split is enabled.

A local trigger test split a 100-section region into two regions while a client
was connected, but the client was disconnected on its next movement by
`out of region teleport accept`. Three follow-up fixes are now in the branch:

- `0020-Live-Split-Teleport-Accept.patch` accepts a teleport when the current
  thread owns the destination chunk, even if the 3x3 halo straddles a new
  boundary. `CollisionUtil` already skips neighbour chunks owned by another
  region, so destination ownership is the real invariant.
- `0024-EntityLookup-Stale-Callback.patch` ignores a shard entity-lookup
  callback queued before a split and replayed after the entity was re-added by
  the destination region. Without it, a stale callback could halt the server
  with `Cannot move entity off-main` on chunk -2147483648.
- The `/canvas regiondata scan` immutable-list crash is fixed (the command now
  copies the stream result into an `ArrayList` before sorting).

With the accept fix the client is no longer disconnected by a split, but a
**live split of a region that still contains a player is still not safe**:
after a 210-section region split, the bot survived and stayed connected, but
was pinned at the new boundary with a repeating
`MOVED_INTO_UNLOADED_CHUNK`. The player chunk loader had lost the boundary
halo, so the destination chunk was never sent; destination-chunk ownership in
the accept path cannot fix that. This is why `allow-player-regions` stays
`false`. Entity-only regions are the supported automatic split path; the
verification harness split 100 sections -> 2 and then 54 -> 2 with 1,152 pigs,
zero players, all entities retained, and no exceptions.

The safe production configuration therefore remains one region for any area
with players, plus a separate lobby instance or queue for population pressure.

### Production gate

A read-only production audit (2026-11-27) confirmed that spawn currently boots
Canvas 26.2, not 26.3; the only 26.3 instance is the lobby at `18fb1b5`.
Preview.2 is built but not deployed. The full 59-jar spawn plugin set has not
been booted on 26.3, so Worlds/Essentials/CoreProtect/HuskSync compatibility is
an open gate, not a pass.

Safe production configuration until the staging gate passes:

```yaml
threaded-regions:
  enable-live-split: false
  max-region-sections: 0
  max-players-per-region: 0
  max-entities-per-region: 0
```

Keep every `*-shards: 1`, `shard-strict-checks: false`, and
`shard-metadata-snapshots: false`. Do not enable player-region auto-split, do
not lower the player/entity caps, and do not repoint the proxy to a 26.3
instance before the real-world/plugin soak passes. The full checklist is in
`production-readiness-2026-11-27.md` in the workspace root.

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
6. Fix the player-region live-split transfer race (pending teleport accept),
   then enable `allow-player-regions` by default and validate with a real
   client. Add region merging when load drops and an entity-only split test.

## Safety

This feature is default-off. Entity scheduler tasks remain sequential per entity,
but different entities in the same region may now execute concurrently. Plugins
that assume a region is globally single-threaded must be tested before enabling
it.

### Preview 3 release

`v26.3-sharding-preview.3` was built from `ec0fb20` and published at
https://github.com/HappyGamblingAddict/Canvas/releases/tag/v26.3-sharding-preview.3

Jar SHA-256: `de12731c67892ef482a57704f52f0ab699b104d0b151819ef4469e5d85b876c7`

It contains `0020` (destination-chunk teleport accept), `0021` (boundary
collision), `0022` (player-safe caps), `0023` (stale entity callbacks), and the
`regiondata scan` fix. On the actual Paperclip jar: the safe-config client
flight and probe passed, entity-only splitting completed 100 -> 2 and 54 -> 2
with 1,152 pigs retained and zero exceptions, and `scan` ran cleanly.

Live player-region splitting is still gated off. Preview 3 is not deployed;
production spawn remains Canvas 26.2.
