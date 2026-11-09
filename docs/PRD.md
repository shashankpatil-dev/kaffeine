# Kaffeine: Product Requirements Document (PRD)

| | |
|---|---|
| **Project** | Kaffeine: a coroutine-native in-memory cache for Kotlin |
| **Owner** | Shashank Patil (`shashankpatil-dev`) |
| **Status** | Draft v1 |
| **Related** | [HLD.md](HLD.md) · [LLD.md](LLD.md) · [PLAN.md](PLAN.md) |

---

## 1. Problem

Kotlin backend services (Ktor, Spring WebFlux, coroutines) need an in-memory cache that:

1. **Keeps the right data.** Plain LRU is destroyed by scans and ignores frequency, so the hit ratio is low and the database load is high.
2. **Is safe under heavy concurrency.** Many coroutines on many threads read and write at once.
3. **Speaks coroutines natively.** The best JVM cache (Caffeine) is built around `CompletableFuture`. Kotlin users have to wrap it. Cancellation and structured concurrency don't map cleanly onto it.
4. **Prevents cache stampedes.** When a hot key is missing, 1000 concurrent requests should cause **one** load, not 1000.

## 2. Solution summary

**Kaffeine** is a Kotlin library (in-process, no server) that provides:

- **W-TinyLFU eviction**: a small LRU window plus a segmented LRU main region, protected by a frequency filter (Count-Min Sketch + Bloom-filter doorkeeper).
- **Timing-wheel expiry**: O(1) time-based expiry, including a different TTL per entry.
- **A `suspend` loading API with request coalescing**: one load per key, shared by every waiting coroutine, with clear cancellation semantics.
- **Benchmarks against Caffeine**: hit ratio on public cache traces and throughput with JMH, published honestly.

## 3. Users

| Persona | Need |
|---------|------|
| Kotlin backend developer | A drop-in cache with a `suspend` API: `cache.get(id) { repo.load(it) }` |
| Performance-minded engineer | Proof (numbers) that the hit ratio and speed are close to Caffeine |
| Me (portfolio / interviews) | Deep, defensible knowledge of every component |

## 4. User stories

1. *As a developer*, I create a cache with a size limit and TTL in a few lines of Kotlin DSL.
2. *As a developer*, I call `cache.get(key) { loadFromDb(it) }` from a coroutine. On a miss it loads once and stores the value.
3. *As a developer*, when 1000 coroutines request the same missing key, my loader runs **exactly once**.
4. *As a developer*, if my coroutine is cancelled while waiting, the other waiters still get their value.
5. *As a developer*, if the loader throws, every waiter gets the exception and the **next** call retries (failures are not cached).
6. *As a developer*, I can give each entry its own TTL (e.g. from an HTTP `Cache-Control` header).
7. *As a developer*, I can read stats (hit rate, evictions, load time) to tune the cache.
8. *As a reviewer*, I can reproduce every benchmark number with one command.

## 5. Functional requirements

| ID | Requirement |
|----|-------------|
| FR-1 | Create a cache with the DSL: `kaffeine<K, V> { maximumSize = ...; expireAfterWrite = ... }` |
| FR-2 | `maximumSize` is **required** and must be > 0. The cache never holds more than `maximumSize` entries once maintenance has run |
| FR-3 | `getIfPresent(key): V?`: returns the value, or `null` if absent or expired |
| FR-4 | `put(key, value)`: inserts or replaces |
| FR-5 | `suspend get(key, loader): V`: returns the cached value, or loads it with **coalescing** (one in-flight load per key) |
| FR-6 | `invalidate(key)`, `invalidateAll()`: remove entries. Invalidating, or putting a new value, during an in-flight load means the loaded value is **not** stored: newer data wins |
| FR-7 | Expiry modes (at most one): `expireAfterWrite`, `expireAfterAccess`, or `expireAfter { key, value -> Duration }` (per entry) |
| FR-8 | An expired entry is **never returned**, even before background cleanup removes it |
| FR-9 | `stats()`: hits, misses, hit rate, load successes and failures, total load time, evictions (when `recordStats = true`) |
| FR-10 | `estimatedSize()`, `cleanUp()` (force maintenance now) |
| FR-11 | Pluggable `Ticker` (time source), so tests can control time |
| FR-12 | Pluggable `loadContext` (dispatcher where loaders run). Default: `Dispatchers.Default` |
| FR-13 | Null keys and values are not allowed (`K : Any, V : Any`) |

## 6. Non-functional requirements

| ID | Requirement | Target |
|----|-------------|--------|
| NFR-1 | **Thread safety**: every public method is safe from any thread or coroutine | Lincheck + stress tests pass |
| NFR-2 | **Hit ratio** vs Caffeine on public traces | Within **~1 percentage point** on average (gaps explained) |
| NFR-3 | **Read throughput** (100% reads, 8 threads, Zipf keys) | v2 (buffered) ≥ **3×** v1 (single lock), and ≥ **50%** of Caffeine |
| NFR-4 | **Runtime dependencies** | Only `kotlinx-coroutines-core` |
| NFR-5 | **Platform** | JDK 17+, Kotlin 2.x |
| NFR-6 | **Test coverage** of `core` | ≥ **85%** lines (Kover) |
| NFR-7 | **Reproducibility** | Every benchmark runs with one command; a summary of the results is committed |

> NFR-2 and NFR-3 are **targets, not promises**. Whatever the real numbers are, they get reported honestly, with the reasons.

## 7. Public API (what a user writes)

```kotlin
val users = kaffeine<Long, User> {
    maximumSize = 10_000
    expireAfterWrite = 10.minutes
    recordStats = true
    loadContext = Dispatchers.IO          // loaders call a blocking JDBC driver
}

suspend fun findUser(id: Long): User =
    users.get(id) { userRepository.load(it) }   // coalesced: 1 DB call per id, however many callers

users.put(42, user)
users.getIfPresent(42)                          // User? (null if absent or expired)
users.invalidate(42)
println(users.stats().hitRate)
```

Per-entry TTL:

```kotlin
val http = kaffeine<String, Response> {
    maximumSize = 50_000
    expireAfter = { _, response -> response.maxAge }   // a different TTL per entry
}
```

## 8. Out of scope (v1)

| Not doing | Why |
|-----------|-----|
| Distributed cache, persistence, network | It's an in-process library. Use Redis for distributed caching |
| Unbounded cache (no `maximumSize`) | Eviction is the point of the project |
| Weighted entries (size in bytes) | Count-based limits keep the policy simple. Can be added later |
| Weak/soft references | Complex GC interplay, small learning value |
| `refreshAfterWrite`, bulk `getAll` | Not needed for the core story |
| Removal listeners | Stretch goal |
| Adaptive window (hill climbing) | **Stretch goal** in Step 4 |
| JCache (JSR-107), Kotlin Multiplatform | Too much surface area for v1 |

## 9. Success metrics (definition of done)

- [x] All functional requirements implemented and tested
- [x] Hit-ratio results for **≥ 4 public traces × ≥ 3 cache sizes** vs LRU, Caffeine and Bélády (optimal), summed up in `bench/RESULTS.md`
- [x] JMH results for **3 workloads × 1/4/8 threads** vs Caffeine, summed up in `bench/RESULTS.md`
- [x] Coalescing proven: 1,000 concurrent `get` calls for the same key → loader called **exactly once** (test)
- [x] CI green: unit, stress and Lincheck tests, coverage ≥ 85%
- [x] Released as `v0.1.0` on JitPack. A sample project can depend on it
- [x] README with quickstart, design summary and honest benchmark charts

## 10. Risks and mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Without an adaptive window, we may trail LRU on recency-heavy traces | Weaker hit-ratio story | Report it honestly. Hill climbing is a stretch goal |
| Concurrency bugs (races between add, remove and evict) | Wrong results, memory leaks | Node lifecycle states, Lincheck, stress tests, simple v1 first |
| Benchmarks give misleading numbers | Loss of credibility | JMH with forks and warmup, Blackhole, Zipf keys, publish raw JSON |
| Scope creep (copying all of Caffeine) | Never finishes | Strict out-of-scope list (§8), 4-step plan |
| Large trace files | Slow CI, repo bloat | Traces are git-ignored and downloaded by script. CI uses a tiny synthetic trace |

## 11. Milestones

See [PLAN.md](PLAN.md) for the detailed checklist.

| Step | Theme | Outcome |
|------|-------|---------|
| 1 | Foundation + eviction policy | v1 cache (single lock) + simulator + hit-ratio results |
| 2 | Concurrency + throughput | v2 cache (buffers) + Lincheck/stress tests + JMH v1 vs v2 vs Caffeine |
| 3 | Expiry + coroutine loading | Timing wheel + `suspend get` with coalescing + stats |
| 4 | Benchmarks, polish, release | Full results, README, KDoc, CI coverage, `v0.1.0` on JitPack |
