# Kaffeine: High-Level Design (HLD)

> **What** the system is made of and **how the parts talk to each other**. Class-level details are in [LLD.md](LLD.md).
>
> Diagrams use **Mermaid**. They render on GitHub automatically. In VS Code, install the extension *"Markdown Preview Mermaid Support"* (`bierner.markdown-mermaid`) and open the preview (`Ctrl+Shift+V`).

---

## 1. System context

Kaffeine is a **library inside your application**. There is no separate server and no network.

```mermaid
flowchart LR
    subgraph App["Your Kotlin service (one JVM process)"]
        H["Request handlers<br/>(coroutines)"] -->|"get / put"| K["Kaffeine cache"]
        K -->|"on miss: loader(key)"| L["Your loader function"]
    end
    L -->|"slow call"| DB[("Database / HTTP API")]
```

- **Hit:** the value is returned from memory in about 100 ns.
- **Miss:** the loader runs **once per key** (coalesced) and the result is stored.

---

## 2. Repository / module architecture

```mermaid
flowchart TB
    subgraph Repo["kaffeine (Gradle multi-module)"]
        Core["core<br/>the library<br/>(published)"]
        subgraph Bench["bench (not published)"]
            Sim["simulator<br/>hit ratio on traces"]
            JMH["jmh<br/>throughput"]
        end
    end
    Sim --> Core
    JMH --> Core
    Sim --> Caf["Caffeine<br/>(bench-only dependency)"]
    JMH --> Caf
    Traces[("traces/<br/>downloaded, git-ignored")] --> Sim
    Sim --> Res["bench/RESULTS.md<br/>one summary doc"]
    JMH --> Res
```

| Module | Purpose | Dependencies |
|--------|---------|--------------|
| `core` | The cache library | `kotlinx-coroutines-core` **only** |
| `bench` | Simulator (main) + JMH benchmarks (`src/jmh`) | `core`, Caffeine, JMH |

**Why two modules?** So the published library never drags Caffeine or JMH onto users' classpaths.

---

## 3. Components inside `core`

```mermaid
flowchart TB
    subgraph Public["Public API"]
        DSL["kaffeine { } DSL<br/>config + validation"]
        API["Cache interface<br/>getIfPresent / get / put / invalidate / stats"]
    end

    subgraph Engine["BoundedCache (orchestrator)"]
        Data["Data store<br/>ConcurrentHashMap: K to Node"]
        Load["Loader / Coalescer<br/>inFlight map + cacheScope"]
        RB["Read buffer<br/>striped, lossy ring buffers"]
        WB["Write buffer<br/>lock-free queue, never drops"]
        M["Maintenance<br/>tryLock, drain, evict, expire"]
    end

    subgraph Policy["Eviction policy (single-threaded, under lock)"]
        W["W-TinyLFU<br/>Window / Probation / Protected"]
        S["FrequencySketch<br/>Count-Min, 4-bit counters, aging"]
        D["Doorkeeper<br/>Bloom filter"]
    end

    subgraph Exp["Expiry (single-threaded, under lock)"]
        TW["TimerWheel<br/>hierarchical, O(1)"]
        T["Ticker<br/>time source"]
    end

    Stats["Stats<br/>LongAdder counters"]

    DSL --> API --> Data
    API --> Load --> Data
    Data --> RB
    Data --> WB
    RB --> M
    WB --> M
    M --> W
    W --> S --> D
    M --> TW --> T
    API --> Stats
```

| Component | Responsibility | Thread safety |
|-----------|----------------|---------------|
| `ConcurrentHashMap` | Holds the actual data. Source of truth for "is the key cached?" | Thread-safe (lock-free reads) |
| Read buffer | Records "node X was read" for the policy | Lock-free, **lossy** (dropping is OK) |
| Write buffer | Records "node X was added / updated / removed" | Lock-free, **never drops** |
| Maintenance | Applies the buffered events to the policy and the wheel, evicts, expires | One thread at a time (`tryLock`) |
| W-TinyLFU policy | Decides **who is evicted** | Not thread-safe; only touched under the lock |
| FrequencySketch + Doorkeeper | Estimates key popularity | Not thread-safe; only under the lock |
| TimerWheel | Finds expired entries in O(1) | Not thread-safe; only under the lock |
| Coalescer | One in-flight load per key | `ConcurrentHashMap.computeIfAbsent` + `CompletableDeferred` |
| Stats | Counters | `LongAdder` (contention-free) |

### The core idea: separate the *data* from the *policy*

- The **data** (the map) must be correct and fast **right now**, for every thread.
- The **policy** (who to evict) can be **a few operations behind** without hurting correctness. So threads leave notes in buffers, and one thread at a time applies them.

This is what makes reads fast: **a read never takes a lock**.

---

## 4. Key design decisions

| # | Decision | Alternatives considered | Why this one |
|---|----------|-------------------------|--------------|
| D1 | `ConcurrentHashMap` stores the data | A custom hash table | Battle-tested, lock-free reads, atomic `compute*` |
| D2 | The policy is updated **through buffers**, under `tryLock` | Lock on every operation (v1) | Reads never block. Built as v1 → v2 so the speedup can be **measured** |
| D3 | **W-TinyLFU** eviction | LRU, LFU, ARC | Best hit ratio across mixed workloads, with small memory |
| D4 | **Count-Min Sketch with 4-bit counters** + periodic halving | An exact counter map | About 8 bytes per entry. Aging handles changing popularity |
| D5 | **Doorkeeper Bloom filter** is optional (a flag) | Always on / never | Comes from the TinyLFU paper. The **simulator decides** the default with data |
| D6 | **One timing wheel for all expiry modes** | Order queues for fixed TTL plus a wheel for variable TTL (Caffeine) | One mechanism, less code, still O(1) |
| D7 | Expired entries are **checked on read** as well as cleaned by the wheel | Wheel only | The wheel is coarse (~1 s). The read check guarantees an expired value is never returned |
| D8 | Coalescing with **`CompletableDeferred` in an `inFlight` map**; the load runs in a **cache-owned `SupervisorJob` scope** | Run the load in the caller's coroutine | One caller cancelling must not kill the load for the others |
| D9 | Maintenance runs **on the calling thread** (amortized) | A background executor (Caffeine uses ForkJoinPool) | Deterministic and simpler. No thread pool to manage |
| D10 | Injectable **`Ticker`** | `System.nanoTime()` directly | Expiry tests run instantly and deterministically |
| D11 | **Simulator first** | Build the concurrent cache first | The policy is checked on real traces before concurrency adds noise |
| D12 | No dependencies besides coroutines | Use helper libraries | Keeps the library small, and shows we can build the pieces ourselves |

---

## 5. Main flows

### 5.1 Read hit: `getIfPresent(key)`

```mermaid
sequenceDiagram
    participant C as Caller
    participant BC as BoundedCache
    participant Map as ConcurrentHashMap
    participant RB as Read buffer
    participant M as Maintenance

    C->>BC: getIfPresent(key)
    BC->>Map: get(key)
    Map-->>BC: node
    BC->>BC: node expired? (compare with ticker)
    alt expired
        BC-->>C: null (miss)
    else fresh
        BC->>RB: offer(node), never blocks
        opt stripe is full
            RB->>M: tryLock and drain
        end
        BC-->>C: node.value (hit)
    end
```

**No lock is taken on the read path.** If the read buffer is full, the note is simply dropped.

### 5.2 Write: `put(key, value)`

```mermaid
sequenceDiagram
    participant C as Caller
    participant BC as BoundedCache
    participant Map as ConcurrentHashMap
    participant WB as Write buffer
    participant M as Maintenance

    C->>BC: put(key, value)
    BC->>Map: compute(key): new Node, or update the existing one
    BC->>WB: offer(AddTask or UpdateTask)
    BC->>M: tryLock and drain
    Note over M: policy.onAdd, sketch.increment,<br/>wheel.schedule, evict while over max
    BC-->>C: return
```

### 5.3 Load with coalescing: `suspend get(key, loader)`

Two callers ask for the same missing key at the same time:

```mermaid
sequenceDiagram
    participant A as Caller A
    participant B as Caller B
    participant BC as BoundedCache
    participant IF as inFlight map
    participant S as cacheScope
    participant L as loader

    A->>BC: get(k, loader)
    BC->>BC: data miss
    BC->>IF: computeIfAbsent(k) creates Deferred D
    BC->>S: launch load for k
    B->>BC: get(k, loader)
    BC->>BC: data miss
    BC->>IF: computeIfAbsent(k) returns existing D
    S->>L: loader(k)
    Note over A,B: A and B both suspend on D.await()<br/>(no threads blocked)
    L-->>S: value
    S->>IF: if D is still registered: store value, remove D
    S->>S: D.complete(value)
    BC-->>A: value
    BC-->>B: value
```

### 5.4 W-TinyLFU: what happens when a node is added

```mermaid
flowchart TD
    A["New node"] --> W["Add to Window tail<br/>sketch.increment(key)"]
    W --> Q{"Window over its max?"}
    Q -- no --> Done(["done"])
    Q -- yes --> C["candidate = Window head (oldest)"]
    C --> R{"Main region has room?"}
    R -- yes --> P["Move candidate to Probation tail"] --> Q
    R -- no --> V["victim = Probation head<br/>(or Protected head if Probation is empty)"]
    V --> F{"freq(candidate) > freq(victim)?"}
    F -- yes --> E1["Evict victim<br/>candidate goes to Probation"] --> Q
    F -- no --> H{"freq(candidate) >= 6<br/>and random 1-in-128?"}
    H -- yes --> E1
    H -- no --> E2["Evict candidate"] --> Q
```

On a **hit** (applied during maintenance):

```mermaid
flowchart LR
    Hit["Hit on node"] --> Inc["sketch.increment"]
    Inc --> Where{"Which region?"}
    Where -- Window --> MW["Move to Window tail"]
    Where -- Probation --> PR["Promote to Protected tail"]
    PR --> Over{"Protected over max?"}
    Over -- yes --> Dem["Demote Protected head<br/>to Probation tail"]
    Over -- no --> Done2(["done"])
    Where -- Protected --> MP["Move to Protected tail"]
```

### 5.5 Maintenance cycle

```mermaid
flowchart TD
    Trig["Triggered by: every write,<br/>a full read stripe, or cleanUp()"] --> TL{"tryLock()"}
    TL -- "busy: someone else is draining" --> Skip(["return, the lock holder will see our task"])
    TL -- acquired --> DR["Drain read buffer<br/>policy.onAccess(node)"]
    DR --> EX["timerWheel.advance(now)<br/>remove expired nodes first"]
    EX --> DW["Drain write buffer<br/>add / remove in policy and wheel"]
    DW --> EV["Evict while size > maximumSize<br/>(W-TinyLFU decides, inside onAdd)"]
    EV --> UL["unlock()"]
    UL --> RC{"Write buffer still has tasks?"}
    RC -- yes --> TL
    RC -- no --> Fin(["done"])
```

The final re-check prevents a **lost wakeup**. Without it, a task added just as the drainer was unlocking would sit in the queue forever.

### 5.6 Expiry

```mermaid
flowchart LR
    subgraph OnRead["On every read"]
        R1["node.expiresAt has passed?"] -- yes --> R2["Treat as miss<br/>(never return a stale value)"]
    end
    subgraph OnMaint["During maintenance"]
        M1["wheel.advance(now)"] --> M2["Buckets that came due"]
        M2 --> M3{"Really expired?<br/>(it may have been updated)"}
        M3 -- yes --> M4["Remove from map and policy"]
        M3 -- no --> M5["Re-schedule into the right bucket"]
    end
```

---

## 6. Concurrency model

```mermaid
flowchart LR
    subgraph Many["Any number of threads / coroutines"]
        T1["Thread 1"]
        T2["Thread 2"]
        TN["Thread N"]
    end
    T1 & T2 & TN -->|"lock-free"| Map["ConcurrentHashMap"]
    T1 & T2 & TN -->|"lock-free offer"| Buf["Read + Write buffers"]
    Buf -->|"one thread at a time<br/>(tryLock)"| Pol["Policy + Sketch + Wheel"]
```

| Rule | Reason |
|------|--------|
| The map is the **source of truth** | The policy may be behind. The map never is |
| Policy, sketch and wheel are touched **only under the maintenance lock** | They are plain (non-thread-safe) structures, which keeps them fast and simple |
| Every node has a **lifecycle state** (Alive → Retired → Dead) | Buffered events can arrive out of order (e.g. "remove" before "add") |
| No slow work inside `ConcurrentHashMap.compute*` | It holds a bin lock and would block unrelated keys |
| The loader never runs while holding a lock | Loaders are slow and may suspend |

---

## 7. Error handling and cancellation semantics

| Situation | Behavior |
|-----------|----------|
| Loader throws | All waiters get the exception. **Nothing is cached**. The next `get` retries |
| One waiter is cancelled | Only that waiter stops. The load continues for the others |
| **All** waiters are cancelled | The load still completes and the value is cached (useful work isn't thrown away) |
| `invalidate(k)` during an in-flight load | The load finishes, but its value is **not stored** |
| `put(k, v)` during an in-flight load | `v` wins. The load finishes, the waiters get its value, but it does not overwrite `v` |
| Loader calls `get` for the **same key** (recursion) | Not supported. It would wait on itself (documented, like Caffeine) |
| Invalid config (e.g. `maximumSize <= 0`, two expiry modes) | `IllegalArgumentException` at build time |

---

## 8. Benchmark architecture

### 8.1 Hit-ratio simulator

```mermaid
flowchart LR
    DL["scripts/download-traces.sh"] --> TR[("traces/*.trace")]
    TR --> RD["TraceFormat<br/>ARC / LIRS"]
    RD -->|"stream of Long keys"| P1["LRU"]
    RD --> P2["Kaffeine"]
    RD --> P3["Caffeine"]
    RD --> P4["Bélády (optimal)"]
    P1 & P2 & P3 & P4 --> REP["Report<br/>hit ratio per cache size"]
    REP --> OUT["Markdown table on stdout<br/>summed up in bench/RESULTS.md"]
```

Single-threaded, so the results are **deterministic**.

### 8.2 Throughput (JMH)

```mermaid
flowchart LR
    Z["Zipf key generator<br/>(pre-computed array)"] --> B["GetPutBenchmark<br/>read 100 / read 75 / write 100"]
    B --> C1["LinkedHashMap + lock<br/>(naive baseline)"]
    B --> C2["Kaffeine"]
    B --> C3["Caffeine"]
    B --> C4["ConcurrentHashMap<br/>(no eviction, ceiling)"]
    C1 & C2 & C3 & C4 --> J["JMH JSON<br/>1 / 4 / 8 threads"]
    J --> R["summed up in bench/RESULTS.md"]
```

---

## 9. Build, CI and release

```mermaid
flowchart LR
    Dev["git push / PR"] --> GA["GitHub Actions"]
    GA --> B1["./gradlew build<br/>compile + unit + stress + Lincheck"]
    B1 --> B2["Kover coverage<br/>(core >= 85%)"]
    B2 --> Ok{"green?"}
    Ok -- no --> Fix["fix"]
    Ok -- yes --> Merge["merge to main"]
    Merge --> Tag["git tag v0.1.0"]
    Tag --> JP["JitPack builds and serves<br/>com.github.shashankpatil-dev:kaffeine"]
```

- JMH and the full simulator run **locally** (too noisy and too slow for CI). Their results are committed.
- CI runs the simulator only on a **tiny synthetic trace** (smoke test).

---

## 10. Testing strategy (overview)

| Layer | What | Tool |
|-------|------|------|
| Unit | Sketch, Bloom filter, deque, policy, wheel, read buffer | JUnit 5 |
| Cache semantics | put/get/invalidate, size bound, expiry with fake time | JUnit 5 + `FakeTicker` |
| Coroutines | Coalescing, failure, cancellation, invalidate during load | `kotlinx-coroutines-test` |
| Concurrency | Linearizability of the map operations | **Lincheck** (JetBrains) |
| Stress | N threads doing random operations, then check invariants | JUnit 5 + threads |
| Simulator | Hand-made tiny traces with known hit counts | JUnit 5 |
| Coverage | ≥ 85% of `core` | Kover |

Details per class are in [LLD.md §10](LLD.md#10-test-plan).
