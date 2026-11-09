#!/usr/bin/env bash
#
# Runs the hit ratio simulator on every trace, at the cache sizes used in bench/results/hit-ratio.
# Run scripts/download-traces.sh first. The two big traces (DS1 and S3) take several minutes each.

set -euo pipefail
cd "$(dirname "$0")/.."

simulate() {
  ./gradlew :bench:run -q --console=plain --args="$1 $2"
}

# LIRS traces, at sizes spread over each trace's number of unique keys
simulate traces/loop.trace.gz 250,500,750,1000
simulate traces/gli.trace.gz 250,500,1000,2000
simulate traces/multi2.trace.gz 500,1000,2000,3000
simulate traces/sprite.trace.gz 100,250,500,1000

# ARC traces, at the cache sizes used in the ARC paper
simulate traces/OLTP.lis 1000,2000,5000,10000,15000
simulate traces/S3.lis 100000,200000,400000,800000
simulate traces/DS1.lis 1000000,2000000,4000000,8000000
