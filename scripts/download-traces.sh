#!/usr/bin/env bash
#
# Downloads the public cache traces used by the simulator into traces/.
# Files that are already there are skipped. Needs curl and zstd.
#
# ARC traces: N. Megiddo and D. S. Modha, "ARC: A Self-Tuning, Low Overhead Replacement Cache",
#   FAST 2003. Mirrored by the moka-rs/cache-trace project.
# LIRS traces: S. Jiang and X. Zhang, "LIRS: An Efficient Low Inter-reference Recency Set
#   Replacement Policy", SIGMETRICS 2002. Mirrored in the Caffeine repository.
#
# Please cite the papers above if you publish results based on these traces.

set -euo pipefail

cd "$(dirname "$0")/.."
mkdir -p traces

ARC_URL="https://raw.githubusercontent.com/moka-rs/cache-trace/main/arc"
LIRS_URL="https://raw.githubusercontent.com/ben-manes/caffeine/master/simulator/src/main/resources/com/github/benmanes/caffeine/cache/simulator/parser/lirs"

for name in OLTP DS1 S3; do
  file="traces/$name.lis"
  if [[ ! -f "$file" ]]; then
    echo "Downloading $name (ARC)"
    # Unpack to a temp file first, so a failed download never leaves a half written trace behind.
    curl -fsSL "$ARC_URL/$name.lis.zst" | zstd -dq -o "$file.part"
    mv "$file.part" "$file"
  fi
done

for name in loop gli multi2 sprite; do
  file="traces/$name.trace.gz"
  if [[ ! -f "$file" ]]; then
    echo "Downloading $name (LIRS)"
    curl -fsSL -o "$file.part" "$LIRS_URL/$name.trace.gz"
    mv "$file.part" "$file"
  fi
done

echo "Traces are ready in traces/"
