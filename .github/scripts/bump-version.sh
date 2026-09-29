#!/usr/bin/env bash
# Increments the trailing number of library_version.txt and prints the new version:
#   0.9 -> 0.10
# The full published version is assembled from it in build.gradle.kts.
set -euo pipefail

file=library_version.txt
current=$(tr -d '[:space:]' <"$file")
if [[ ! "$current" =~ ^(.*[^0-9])?([0-9]+)$ ]]; then
  echo "Cannot bump '$current' in $file: it must end with a number" >&2
  exit 1
fi
next="${BASH_REMATCH[1]}$((BASH_REMATCH[2] + 1))"

echo "$next" >"$file"
echo "$next"
