# Kaffeine

A Caffeine-style in-memory cache for Kotlin.

- **W-TinyLFU eviction**: a small LRU window plus a segmented LRU main region, guarded by a
  Count-Min Sketch and a Bloom filter doorkeeper
- **Timing-wheel expiry**: after write, after access, or a lifetime per entry
- **`suspend` loading with request coalescing**: many callers for one missing key share a
  single load, with clear rules for failures, cancellation and invalidation
- **Lock-free reads**: reads leave a note in a striped buffer, and one thread at a time
  applies the notes to the policy
- **Measured against Caffeine** on public cache traces and with JMH

> Work in progress. See [docs/PLAN.md](docs/PLAN.md) for where things stand.

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
is level with Caffeine on average.

## Throughput benchmark

```bash
scripts/benchmark.sh                                          # JMH, 1, 4 and 8 threads, about 12 minutes
THREADS=8 scripts/benchmark.sh -p cache=kaffeine,caffeine     # a quicker, focused run
```

Lock-free reads made Kaffeine more than 10 times faster than the first single-lock version
at 8 threads. It still trails Caffeine: about 61% of its read throughput in the same run.

## Design docs

- [PRD](docs/PRD.md): what we are building and why
- [HLD](docs/HLD.md): architecture and main flows
- [LLD](docs/LLD.md): classes, algorithms, races and tests
- [Plan](docs/PLAN.md): the four build steps
