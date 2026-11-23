# Region sizing

`/canvas regiondata summary` reports the live tick-region layout: region count,
total chunks / players / entities, the largest region, and the top regions by
chunk count.

## Bounded regions

An earlier attempt added a `maxRegionSections` cap that forced dense areas to
split into several tick regions. It was reverted: in testing, forcing a split
caused `Thread failed main thread check: Adding block without owning region`
and halted the server. Region membership must stay consistent with the chunk
and tick ownership maps, which the forced split did not guarantee.

See the open issue for details and reproduction steps.
