#!/usr/bin/env bash
# Dry-run applicability check for the ported shard snapshot and structural patches.
#
# The script copies only the files touched by those patches into a temporary
# Git repository. If the generated tree already contains the patches, it peels
# them off first, then applies 0015 and 0016 forward again. The workspace and
# the generated tree are never modified.
#
# Usage:
#   canvas-server/src/test/scripts/check-sharding-patches.sh [generated-source-root]
#
# generated-source-root defaults to canvas-server/src/minecraft/java.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../../../.." && pwd)"
patch_dir="$repo_root/canvas-server/minecraft-patches/features"

source_root="${1:-$repo_root/canvas-server/src/minecraft/java}"
if [[ ! -d "$source_root" ]]; then
    echo "error: generated source root not found: $source_root" >&2
    echo "run './gradlew applyAllPatches' first, or pass the source root explicitly" >&2
    exit 2
fi

patch15="$patch_dir/0009-Shard-Snapshot-And-Callback-Queue.patch"
patch16="$patch_dir/0005-Entity-Despawn-And-Structural-Action-Sharding.patch"
for patch in "$patch15" "$patch16"; do
    if [[ ! -f "$patch" ]]; then
        echo "error: missing patch: $patch" >&2
        exit 2
    fi
done

work="$(mktemp -d "${TMPDIR:-/tmp}/canvas-sharding-patches.XXXXXX")"
trap 'rm -rf "$work"' EXIT

mapfile -t targets < <(
    sed -n 's/^+++ b\///p' "$patch15" "$patch16" | sort -u
)

for target in "${targets[@]}"; do
    if [[ ! -f "$source_root/$target" ]]; then
        echo "error: missing generated source: $source_root/$target" >&2
        exit 2
    fi
    mkdir -p "$work/$(dirname -- "$target")"
    # Normalize line endings so Git patch application is host independent.
    tr -d '\r' < "$source_root/$target" > "$work/$target"
done

cp "$patch15" "$work/0015.patch"
cp "$patch16" "$work/0016.patch"
sed -i 's/\r$//' "$work/0015.patch" "$work/0016.patch"

git -C "$work" init -q

regionized="$work/io/papermc/paper/threadedregions/RegionizedWorldData.java"
has_0015=false
has_0016=false
if grep -q 'canvas\$shardSnapshot' "$regionized"; then has_0015=true; fi
if grep -q 'canvas\$entityLookupCallbacks' "$regionized"; then has_0016=true; fi

if [[ "$has_0016" == true ]]; then
    git -C "$work" apply --reverse --check --whitespace=nowarn "$work/0016.patch"
    git -C "$work" apply --reverse --whitespace=nowarn "$work/0016.patch"
fi
if [[ "$has_0015" == true ]]; then
    git -C "$work" apply --reverse --check --whitespace=nowarn "$work/0015.patch"
    git -C "$work" apply --reverse --whitespace=nowarn "$work/0015.patch"
fi

git -C "$work" apply --check --whitespace=nowarn "$work/0015.patch"
git -C "$work" apply --whitespace=nowarn "$work/0015.patch"
git -C "$work" apply --check --whitespace=nowarn "$work/0016.patch"
git -C "$work" apply --whitespace=nowarn "$work/0016.patch"

grep -q 'canvas\$shardSnapshot' "$regionized"
grep -q 'canvas\$entityLookupCallbacks' "$regionized"
echo "sharding snapshot and structural patches apply cleanly to $source_root"
