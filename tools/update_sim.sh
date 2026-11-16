#!/bin/sh
# Copies ObsSynth FM-1's simulator page into the app (app/src/main/assets/sim): a checkout of obssynth-fm1 with
# tools/build_sim.sh run (build/sim). Usage: tools/update_sim.sh ../obssynth-fm1
set -e
src="${1:?the obssynth-fm1 checkout}"/build/sim
dst="$(dirname "$0")/../app/src/main/assets/sim"
for f in "$src"/*.html "$src"/*.js "$src"/*.css "$src"/*.json "$src"/fm1sim.wasm; do cp "$f" "$dst"/; done
cp -r "$src"/img "$dst"/
echo "copied $(git -C "${1}" rev-parse --short HEAD); update SOURCE.txt's commit"
