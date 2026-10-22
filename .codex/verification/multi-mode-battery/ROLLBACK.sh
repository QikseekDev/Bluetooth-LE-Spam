#!/bin/sh
set -eu
target=${1:?usage: ROLLBACK.sh TARGET_COPY}
base="$(dirname "$0")/BASELINE.kt"
cp "$base" "$target"
