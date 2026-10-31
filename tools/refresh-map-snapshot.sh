#!/usr/bin/env bash
# Refreshes the campus map snapshot shipped inside the app (android/app/src/main/assets/campus-map).
# The app downloads the map from Jeman's vit-map itself and keeps it on the phone; the snapshot is
# only its fallback for a first open when vit-map can't be reached. Run before a release.
set -euo pipefail
cd "$(dirname "$0")/.."
dir=android/app/src/main/assets/campus-map
mkdir -p "$dir"
for f in campus geo graph; do
  curl -fsS --max-time 60 "https://vit-map.vercel.app/data/$f.json" -o "$dir/$f.json.part"
  mv "$dir/$f.json.part" "$dir/$f.json"
done
date -u +%Y-%m-%d > "$dir/taken"
ls -l "$dir"
