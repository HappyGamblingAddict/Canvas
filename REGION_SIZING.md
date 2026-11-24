# Region sizing and live splitting

Canvas keeps sparse areas in large tick regions and splits dense areas so they
can tick on more worker cores. Region size is based on **actual load**, not just
area.

## Configuration

Under `threaded-regions` in `config/paper-global.yml`:

```yaml
max-region-sections: 100
max-players-per-region: 30
max-entities-per-region: 300
enable-live-split: true
live-split-shards: 2
live-split-min-sections-per-shard: 2
```

- `max-region-sections` is the hard spatial safety cap.
- `max-players-per-region` and `max-entities-per-region` prevent busy regions from
  merging and trigger a split when a loaded region exceeds the threshold.
- `enable-live-split` controls automatic splitting of already-ticking regions.
- `live-split-shards` may be `2`, `4`, or `8` (values are clamped to `2..8`). The
  default remains `2`; higher values are opt-in because more boundaries mean more
  cross-region routing.
- `live-split-min-sections-per-shard` prevents tiny shards. The splitter stops
  early if it cannot create another shard of at least this size.

Automatic splits run at the **end of the owning region's tick**, never mid-tick.
There is a per-region cooldown and a global cooldown so cascading splits are
spaced out. Regions that are merging, idle, or too small are not split.

## Commands

All commands are under `/canvas regiondata` and require the normal Canvas command
permission.

- `summary` — total regions/chunks/players/entities and the largest regions.
- `scan [limit]` — ranks loaded regions by player/entity load, shows whether each
  is ticking, and marks regions over the configured thresholds.
- `split <id>` — **dry-run preview**. Shows the region load, proposed shard count,
  and the safety-check result. This does not change anything.
- `split <id> confirm` — splits an idle region immediately, or queues a ticking
  region for its next eligible end-of-tick. The command refuses merging,
  transient, or undersized regions.
- `auto [on|off]` — shows or changes automatic live splitting at runtime. With no
  argument it only reports the current state. Edit the YAML value to persist the
  setting across restarts.

Example:

```text
/canvas regiondata scan 10
/canvas regiondata split 42
/canvas regiondata split 42 confirm
/canvas regiondata auto off
```

## Safety notes

Live splitting uses the same full data-transfer path as Folia's normal region
split. Only after the transfer completes are the new regions activated. The dead
region's stale entity/chunk lists are cleared, and split callbacks tolerate
entries whose chunk section is no longer present.

With very aggressive thresholds and many rapid successive splits, a transient
mob-AI thread warning can still occur while chunk ownership changes. The global
cooldown and conservative two-shard default keep this rare in normal operation.
Set `enable-live-split: false` to retain only merge prevention and split-on-release
if your plugin workload cannot tolerate cross-region boundaries.
