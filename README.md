# Kaffeine

[![CI](https://github.com/shashankpatil-dev/kaffeine/actions/workflows/ci.yml/badge.svg)](https://github.com/shashankpatil-dev/kaffeine/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/shashankpatil-dev/kaffeine.svg)](https://jitpack.io/#shashankpatil-dev/kaffeine)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

A coroutine-native in-memory cache for Kotlin, built the way Caffeine is built inside.

- **W-TinyLFU eviction**: a small LRU window plus a segmented LRU main region, with a
  Count-Min Sketch deciding which entries are worth keeping, and a hill climber that resizes
  the window to fit the traffic
- **Timing-wheel expiry**: after write, after access, or a lifetime per entry
- **`suspend` loading with request coalescing**: many callers for one missing key share a
  single load, with clear rules for failures, cancellation and invalidation
- **Lock-free reads**: reads leave a note in a striped buffer, and one thread at a time
  applies the notes to the eviction policy
- **Measured against Caffeine** on public cache traces and with JMH

## Install

Kaffeine is published through [JitPack](https://jitpack.io/#shashankpatil-dev/kaffeine)
and needs JDK 17 or newer.

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.shashankpatil-dev:kaffeine:v0.1.0")
}
```

## Usage

```kotlin
val users = kaffeine<Long, User> {
    maximumSize = 10_000
    expireAfterWrite = 10.minutes
    loadContext = Dispatchers.IO   // our loader below calls a blocking JDBC driver
    recordStats = true
}

// One database call per missing id, however many coroutines ask for it at once.
suspend fun findUser(id: Long): User = users.get(id) { userRepository.load(it) }

users.put(42, user)
users.getIfPresent(42)      // User, or null if absent or expired
users.invalidate(42)
println(users.stats().hitRate)
```

Each entry can also get its own lifetime:

```kotlin
val responses = kaffeine<String, Response> {
    maximumSize = 50_000
    expireAfter = { _, response -> response.maxAge }
}
```

What `get(key, loader)` promises:

- However many callers ask for the same missing key at once, the loader runs once.
- If the loader throws, every waiting caller gets the exception and nothing is cached.
- If one caller is cancelled, the load keeps going for the others and is still cached.
- If the key is invalidated or given a new value while it loads, the newer state wins.

## How it works

The data lives in a `ConcurrentHashMap`, so reads never wait. The eviction policy and the
timer wheel are plain single-threaded structures. Reads drop a note in a lossy, striped ring
buffer, and adding or removing a key queues a task. One thread at a time takes a lock with
`tryLock`, applies the notes and tasks, expires what is due, and evicts what the policy picks.

When the cache is full, the oldest entry of a small LRU window competes with the oldest entry
of the main region. A Count-Min Sketch with 4-bit counters estimates how often each was used,
and the more popular one stays. The counters are halved now and then, so old popularity fades.

Expiry uses a five-level timing wheel, from about one second per bucket up to days, so finding
expired entries costs O(1) per entry. A read also checks the entry's time, so an expired value
is never returned, even before the wheel removes it.

This upkeep runs on the calling thread by default. The `executor` setting can move it to a
thread pool, the way Caffeine does it, but for this cache that measured slower for reads.

`get(key, loader)` keeps one `CompletableDeferred` per loading key. Loads run in the cache's
own `SupervisorJob` scope, so a caller being cancelled never cancels a load others are waiting
for. The full design, with diagrams and the list of races it handles, is in the docs below.

## Results

### Hit ratio

![Hit ratio of Kaffeine, Caffeine, LRU and the optimal policy on seven traces](docs/images/hit-ratio.svg)

Percent of requests served from the cache, averaged over the cache sizes tested per trace:

| Trace | Kaffeine | Caffeine | LRU | Optimal |
|---|---:|---:|---:|---:|
| DS1 (database) | 39.4 | 38.9 | 19.3 | 43.8 |
| S3 (web search) | 37.1 | 35.7 | 18.9 | 51.6 |
| OLTP (database) | 52.6 | 53.8 | 50.9 | 66.1 |
| loop | 57.4 | 58.7 | 0.0 | 61.7 |
| gli | 39.2 | 35.6 | 17.6 | 40.7 |
| multi2 | 62.7 | 63.1 | 51.0 | 67.2 |
| sprite | 61.2 | 62.9 | 59.8 | 76.6 |

Kaffeine beats LRU by 12 to 57 points on five of the seven traces, edges it on the other two,
and is level with Caffeine on average. Its window resizes itself while running, like
Caffeine's: on OLTP, a trace that rewards recently used keys, that cut the gap to Caffeine
from 3.2 points to 1.2.

### Throughput

![Reads per second at 1, 4 and 8 threads for four caches](docs/images/throughput.svg)

Millions of operations per second at 8 threads, all caches in one JMH run:

| Workload | Kaffeine | Caffeine | ConcurrentHashMap (no eviction) | LinkedHashMap + lock |
|---|---:|---:|---:|---:|
| 100% reads | 128.4 | 244.5 | 430.3 | 8.0 |
| 75% reads | 55.8 | 112.4 | 141.8 | 8.6 |
| 100% writes | 38.9 | 66.5 | 56.4 | 8.0 |

Kaffeine scales with threads like Caffeine does, at about half its speed. The first version,
with one lock around the policy, got slower as threads were added, like the `LinkedHashMap`
does here; lock-free reads made it more than 10 times faster at 8 threads.

All numbers, how they were measured, and what changed along the way:
[bench/RESULTS.md](bench/RESULTS.md).

## Build and test

```bash
./gradlew build             # compile, run every test, check coverage (85% minimum)
```

The tests include an 8-thread stress test and [Lincheck](https://github.com/JetBrains/lincheck)
checks that run operations from several threads in many interleavings and verify the results.

## Benchmarks

```bash
scripts/download-traces.sh                                    # ARC and LIRS traces into traces/
scripts/simulate-all.sh                                       # hit ratio: every trace, every policy
./gradlew :bench:run --args="traces/OLTP.lis 1000,2000,5000"  # or one trace at chosen sizes

scripts/benchmark.sh                                          # JMH at 1, 4 and 8 threads, about 12 minutes
THREADS=8 scripts/benchmark.sh -p cache=kaffeine,caffeine     # a quicker, focused run
```

## Design docs

- [PRD](docs/PRD.md): what we are building and why
- [HLD](docs/HLD.md): architecture and main flows
- [LLD](docs/LLD.md): classes, algorithms, races and tests
- [Plan](docs/PLAN.md): the four build steps

## License

Copyright 2026 Shashank Patil. Licensed under Apache 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). The cache traces used for the hit ratio results belong to
their authors and are cited in [bench/RESULTS.md](bench/RESULTS.md).
