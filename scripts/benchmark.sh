#!/usr/bin/env bash
#
# Runs the JMH throughput benchmark at 1, 4 and 8 threads. Takes about 12 minutes.
# JMH prints a results table after each thread count, and saves JSON under bench/build/jmh/.
#
# Extra arguments go straight to JMH, for example:
#   scripts/benchmark.sh -p cache=kaffeine,caffeine -p workload=read_100

set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew :bench:jmhJar -q --console=plain
mkdir -p bench/build/jmh

for threads in 1 4 8; do
  java -jar bench/build/libs/bench-jmh.jar GetPutBenchmark \
    -t "$threads" -f 1 -wi 3 -w 2s -i 5 -r 2s \
    -rf json -rff "bench/build/jmh/threads-$threads.json" "$@"
done
