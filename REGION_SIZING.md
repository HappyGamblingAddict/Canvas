# Region sizing: bounded regions

**Status:** implemented. Dense, contiguous settlements are now capped in size and
split into several tick regions instead of growing into one large region.

## Goal

Make a dense settlement produce **smaller tick regions** instead of a single large
one, so its work can make use of more worker threads — without giving up the
lock-free ownership model that makes region threading fast in the first place.

## Why the old behavior happened

The implementation lives in the region-threading patch
(`canvas-server/minecraft-patches/base/0004-Region-Threading.patch`); line numbers
below refer to that file.

* Region membership was decided **purely by spatial connectivity of loaded chunk
  sections**. When a chunk loaded, `ThreadedRegionizer.addChunk()` merged every
  region within the merge radius into one (~lines 9907–10026).
* There was **no maximum region size and no load-aware split**: `onRegionRelease()`
  only split a region when its sections became *spatially disconnected* with
  enough dead sections (~lines 10098–10271).
* A region is ticked by **exactly one worker thread**, so one dense town could only
  ever use one core no matter how many threads were configured.

Players far apart kept disjoint loaded-chunk sets and therefore became separate
regions. A contiguous town never did.

## What changed

A configurable cap, `maxRegionSections` (in `paper-global.yml` under
`threaded-regions`), now bounds the number of chunk sections in a region:

1. **`addChunk()` no longer collapses nearby regions back into one** when the
   merged result would exceed the cap. It attaches the newly loaded sections to a
   nearby region that still has room, or creates a new region, and leaves the
   other nearby regions alone.
2. **`onRegionRelease()` now splits an oversized region even when its sections are
   still connected.** The existing BFS component grouping is capped: once a
   component reaches the limit, the remaining sections form another region. The
   existing split machinery (`preSplit`, `RegionizedWorldData.split`,
   `LevelTicks.split`, `onRegionActive`) handles the rest.
3. The default `gridExponent` was lowered from `4` to `2` (a section is now 4×4
   chunks instead of 16×16). Region granularity is limited by section size, so
   without this the cap would rarely be reached in a normal town.

Defaults: `gridExponent: 2`, `maxRegionSections: 25`. The cap is clamped up to the
section halo size `(2 * emptySectionCreateRadius + 1)^2` so a single chunk load
can never produce an over-cap region. Set `maxRegionSections` to `0` or less to
restore the old unlimited behaviour.

With the defaults, a region holds at most 25 sections of 4×4 chunks — roughly a
20×20 chunk area — instead of one region per contiguous settlement.

## Tuning

* `maxRegionSections` — lower it for smaller regions, raise it for fewer, larger
  regions. `<= 0` disables the cap entirely.
* `gridExponent` — section size is `2^gridExponent` chunks per side. Sections are
  the finest unit the regionizer can split on, so this sets the lower bound on
  region size.
* Changing these requires a restart.

Large numbers of small regions mean more schedulable ticks and more region
overhead. `threads` should be high enough to make the extra parallelism useful.

## Caveats and risks

* The cap is in **sections**, not entities or tick time. A very dense but
  geographically small cluster can still occupy one section and therefore one
  region.
* Splitting along arbitrary section boundaries means entities, block/fluid ticks,
  and redstone may cross region edges. The existing cross-region handling for
  those cases applies, but this change has **not** been validated under a real
  full build or a live server.
* Region code lives in generated sources applied from patches. Do **not**
  hand-edit generated sources; changes belong in the patch files.
* Requires the Java 25 toolchain and `./gradlew applyAllPatches` to build.

## Measurement

`/canvas regiondata summary` reports region count, total chunks / players /
entities, the largest region, and the top regions by chunk count. Use it to see
whether the cap is actually producing smaller regions in your world.
