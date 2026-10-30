#!/usr/bin/env bash
# Stores what .github/workflows/release.yml needs but the repository doesn't have as
# GitHub Actions secrets: the signing key from android/keystore.properties, and the
# captcha weights and slot table (gzipped, base64, split under GitHub's 48 KB limit).
# Run again whenever the model or the key changes. Nothing is printed.
set -euo pipefail
cd "$(dirname "$0")/.."

props=android/keystore.properties
get() { sed -n "s/^$1=//p" "$props" | tr -d '\r'; }
store=$(get storeFile)
pass=$(get passwordFile)
case "$store" in /* | [A-Za-z]:*) ;; *) store="android/app/$store" ;; esac
case "$pass" in /* | [A-Za-z]:*) ;; *) pass="android/app/$pass" ;; esac

private=(
  android/core/src/main/resources/dev/medriid/davit/vtop/captcha_model.bin
  android/core/src/main/resources/dev/medriid/davit/vtop/slot_times.txt
)
for f in "$store" "$pass" "${private[@]}"; do [ -s "$f" ] || { echo "Missing $f" >&2; exit 1; }; done

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

base64 -w0 "$store" | gh secret set DAVIT_KEYSTORE
tr -d '\r\n' < "$pass" | gh secret set DAVIT_KEYSTORE_PASSWORD
get keyAlias | gh secret set DAVIT_KEY_ALIAS

tar -cz "${private[@]}" | base64 -w0 > "$tmp/private"
split -b 45000 -d -a 1 "$tmp/private" "$tmp/part"
parts=("$tmp"/part*)
[ "${#parts[@]}" -le 6 ] || { echo "The private files need ${#parts[@]} parts; release.yml reads 6" >&2; exit 1; }
n=0
for part in "${parts[@]}"; do
  n=$((n + 1))
  gh secret set "DAVIT_PRIVATE_$n" < "$part"
done
for i in $(seq $((n + 1)) 6); do gh secret delete "DAVIT_PRIVATE_$i" > /dev/null 2>&1 || true; done
echo "Set the signing key and $n private part(s)."
