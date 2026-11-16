#!/bin/sh
# Copies ObsSynth FM-1's simulator core into the app (app/src/main/cpp/cores/obssynth-fm1): tests/sim_core.c and
# sim_host.h, the firmware's sources and HAL headers, and the generated tables (tools/build_sim.sh --host-only first).
# Usage: tools/update_core.sh ../obssynth-fm1
set -e
src="${1:?the obssynth-fm1 checkout}"
dst="$(dirname "$0")/../app/src/main/cpp/cores/obssynth-fm1"
mkdir -p "$dst/tests" "$dst/firmware/src" "$dst/firmware/hal" "$dst/gen"
cp "$src/tests/sim_core.c" "$src/tests/sim_host.h" "$dst/tests/"
cp "$src"/firmware/src/*.c "$dst/firmware/src/"
cp "$src"/firmware/hal/*.h "$dst/firmware/hal/"
cp "$src"/build/sim/gen/*.h "$dst/gen/"
cp "$src/LICENSE" "$dst/LICENSE"
git -C "$src" rev-parse --short HEAD > "$dst/COMMIT"
cp "$src/VERSION.txt" "$dst/VERSION"
echo "core: $(cat "$dst/VERSION") at $(cat "$dst/COMMIT")"
