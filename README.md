# Kaffeine

A Caffeine-style in-memory cache for Kotlin.

- **W-TinyLFU eviction**: a small LRU window plus a segmented LRU main region, guarded by a
  Count-Min Sketch and a Bloom filter doorkeeper
- **Timing-wheel expiry** (step 3)
- **`suspend` loading with request coalescing** (step 3)
- **Measured against Caffeine** on public cache traces

> Work in progress. See [docs/PLAN.md](docs/PLAN.md) for where things stand.

## Usage

```kotlin
val users = kaffeine<Long, User> {
    maximumSize = 10_000
    recordStats = true
}

users.put(42, user)
users.getIfPresent(42)      // User, or null if absent
users.invalidate(42)
println(users.stats().hitRate)
```

## Build

Needs JDK 17.

```bash
./gradlew build             # compile everything and run all tests
```

## Hit ratio simulator

The simulator replays real recorded traces through LRU, Kaffeine, Caffeine and Belady's
optimal policy, and reports the hit ratio of each.

```bash
scripts/download-traces.sh                                    # ARC and LIRS traces into traces/
scripts/simulate-all.sh                                       # every trace, every policy
./gradlew :bench:run --args="traces/OLTP.lis 1000,2000,5000"  # or one trace at chosen sizes
```

Results and what they mean: [bench/RESULTS.md](bench/RESULTS.md). In short, Kaffeine
beats LRU by 12 to 58 points on five of the seven traces, roughly ties it on the other two, and
ends up 0.13 points behind Caffeine on average.

## Design docs

- [PRD](docs/PRD.md): what we are building and why
- [HLD](docs/HLD.md): architecture and main flows
- [LLD](docs/LLD.md): classes, algorithms, races and tests
- [Plan](docs/PLAN.md): the four build steps
