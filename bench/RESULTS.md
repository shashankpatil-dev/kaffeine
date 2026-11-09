# Benchmark results

## Hit ratio

How often each policy finds the requested key when we replay real recorded traces.
Higher is better.

| Policy | What it is |
|--------|------------|
| `lru` | Plain LRU (`LinkedHashMap` in access order) |
| `kaffeine` | This library with default settings |
| `kaffeine-doorkeeper` | This library with the Bloom filter doorkeeper turned on |
| `caffeine` | Caffeine 3.3.1, which adds an adaptive window on top of W-TinyLFU |
| `belady` | The optimal policy. It knows the future, so no real cache can beat it |

To reproduce:

```bash
scripts/download-traces.sh
scripts/simulate-all.sh    # tables are also saved as CSV under bench/build/hit-ratio/
```

Both Kaffeine and Caffeine admit a few entries at random (a guard against hash flooding
attacks), so numbers can move by a few hundredths of a point between runs.

### Summary

Average difference in hit ratio points over the cache sizes of each trace.

| Trace | Kind | Kaffeine vs LRU | Kaffeine vs Caffeine | Doorkeeper effect | Gap to optimal |
|-------|------|---:|---:|---:|---:|
| DS1 | database (ARC) | +20.2 | +0.61 | +0.33 | 4.2 |
| S3 | web search (ARC) | +18.1 | +1.41 | -0.44 | 14.6 |
| OLTP | database (ARC) | -0.3 | -3.20 | -0.07 | 15.5 |
| loop | loop (LIRS) | +58.5 | -0.37 | -1.32 | 3.2 |
| gli | mixed (LIRS) | +21.6 | +3.60 | +0.04 | 1.5 |
| multi2 | mixed (LIRS) | +12.0 | -0.18 | -0.21 | 4.2 |
| sprite | file system (LIRS) | +1.7 | -0.87 | -0.12 | 15.1 |

Over all 29 data points, Kaffeine and Caffeine are **level on average** (+0.03 points).

### What the numbers say

**W-TinyLFU beats LRU by a wide margin when frequency matters.** On DS1, S3 and gli it
gains around 20 points. On loop, LRU gets 0%: the loop has 1,011 keys, more than any cache
size we tried, so every key is evicted just before it comes back. Kaffeine keeps most of the loop
and gets close to optimal.

**Kaffeine is close to Caffeine overall, but not on recency-heavy traces.** OLTP rewards
recently used keys more than often used ones, so plain LRU already does well there, and
Kaffeine is 3.2 points behind Caffeine. Our window is fixed at 1% of the cache. Caffeine
grows or shrinks its window while running (hill climbing), which lets it act more like LRU
on traces like this one. Adding that is a stretch goal in [the plan](../docs/PLAN.md).

**The doorkeeper did not earn its keep.** It helped at only 14 of the 29 points and lowered
the hit ratio by about 0.25 points on average, while costing about 10 extra bytes per entry. The
TinyLFU paper uses it so the sketch can be made smaller for the same accuracy. We keep the
sketch at full size, so the doorkeeper only adds noise from its false positives and from
being cleared at every reset. It is off by default and stays available as an option.

**The read buffer barely changes the hit ratio.** These numbers come from the final cache,
where reads only leave a note that may be dropped. Compared with the v1 cache, which
updated the policy on every read, the hit ratio moved by +0.1 points on average, and 25 of
the 29 points moved by less than half a point. The exception is loop, which swung from -1.7
to +3.9 points: it is a strict cycle, so the exact order in which the policy sees accesses
decides which keys stay.

### Per trace

Hit ratio in %, higher is better.

#### DS1 (43,704,979 requests)

| Cache size | lru | kaffeine | kaffeine-doorkeeper | caffeine | belady |
|---:|---:|---:|---:|---:|---:|
| 1,000,000 | 3.09 | 14.25 | 15.02 | 12.49 | 20.19 |
| 2,000,000 | 10.74 | 28.44 | 28.75 | 28.15 | 31.79 |
| 4,000,000 | 20.24 | 45.00 | 45.22 | 45.23 | 48.09 |
| 8,000,000 | 43.03 | 70.33 | 70.36 | 69.72 | 74.93 |

#### S3 (16,407,702 requests)

| Cache size | lru | kaffeine | kaffeine-doorkeeper | caffeine | belady |
|---:|---:|---:|---:|---:|---:|
| 100,000 | 2.33 | 11.90 | 10.84 | 10.95 | 25.42 |
| 200,000 | 4.63 | 23.00 | 22.42 | 21.27 | 39.79 |
| 400,000 | 12.04 | 42.65 | 42.50 | 41.07 | 59.96 |
| 800,000 | 56.60 | 70.56 | 70.60 | 69.19 | 81.27 |

#### OLTP (914,145 requests)

| Cache size | lru | kaffeine | kaffeine-doorkeeper | caffeine | belady |
|---:|---:|---:|---:|---:|---:|
| 1,000 | 32.83 | 35.61 | 35.48 | 40.01 | 53.61 |
| 2,000 | 42.47 | 42.18 | 42.13 | 46.35 | 60.40 |
| 5,000 | 53.65 | 52.48 | 52.33 | 54.38 | 68.27 |
| 10,000 | 60.70 | 59.86 | 59.75 | 62.67 | 73.02 |
| 15,000 | 64.63 | 62.80 | 62.91 | 65.51 | 75.14 |

#### loop (505,500 requests)

| Cache size | lru | kaffeine | kaffeine-doorkeeper | caffeine | belady |
|---:|---:|---:|---:|---:|---:|
| 250 | 0.00 | 24.19 | 20.66 | 22.41 | 24.63 |
| 500 | 0.00 | 47.15 | 45.17 | 47.62 | 49.36 |
| 750 | 0.00 | 65.45 | 65.52 | 68.55 | 74.04 |
| 1,000 | 0.00 | 97.22 | 97.36 | 96.92 | 98.71 |

#### gli (6,015 requests)

| Cache size | lru | kaffeine | kaffeine-doorkeeper | caffeine | belady |
|---:|---:|---:|---:|---:|---:|
| 250 | 0.91 | 16.19 | 16.31 | 14.68 | 17.64 |
| 500 | 0.95 | 33.27 | 33.22 | 27.78 | 34.26 |
| 1,000 | 11.21 | 49.53 | 49.61 | 42.13 | 53.13 |
| 2,000 | 57.41 | 57.96 | 57.96 | 57.96 | 57.96 |

#### multi2 (26,311 requests)

| Cache size | lru | kaffeine | kaffeine-doorkeeper | caffeine | belady |
|---:|---:|---:|---:|---:|---:|
| 500 | 35.98 | 47.72 | 47.17 | 49.10 | 53.60 |
| 1,000 | 47.80 | 56.33 | 56.35 | 57.98 | 62.16 |
| 2,000 | 49.00 | 69.85 | 69.57 | 69.36 | 74.65 |
| 3,000 | 71.18 | 77.95 | 77.92 | 76.13 | 78.40 |

#### sprite (133,996 requests)

| Cache size | lru | kaffeine | kaffeine-doorkeeper | caffeine | belady |
|---:|---:|---:|---:|---:|---:|
| 100 | 21.58 | 31.37 | 30.45 | 31.72 | 50.80 |
| 250 | 48.84 | 55.49 | 55.54 | 54.79 | 74.48 |
| 500 | 78.30 | 74.41 | 74.63 | 76.21 | 87.88 |
| 1,000 | 90.64 | 84.86 | 85.04 | 86.90 | 93.24 |

### Traces

- **ARC traces** (DS1, S3, OLTP): N. Megiddo and D. S. Modha, "ARC: A Self-Tuning, Low
  Overhead Replacement Cache", FAST 2003. Cache sizes match the ones used in the paper.
- **LIRS traces** (loop, gli, multi2, sprite): S. Jiang and X. Zhang, "LIRS: An Efficient
  Low Inter-reference Recency Set Replacement Policy", SIGMETRICS 2002. Cache sizes are
  spread over each trace's number of unique keys.

## Throughput

Millions of operations per second, higher is better.

Measured with JMH on an AMD Ryzen 5 5625U laptop (6 cores, 12 threads) with JDK 17:
one fork, 3 warmup rounds and 5 measured rounds of 2 seconds each. Keys follow a Zipf
distribution over 65,536 keys. Every key is in the cache, so reads always hit, and we
measure the cache's own bookkeeping rather than misses. Laptop numbers are noisy, so treat
differences under about 10% as noise.

| Cache | What it is |
|-------|------------|
| `chm` | `ConcurrentHashMap` with no eviction at all. No cache can beat it, so it is the ceiling |
| `caffeine` | Caffeine 3.3.1 |
| `kaffeine` | This library |
| `lhm-lock` | `LinkedHashMap` in access order behind one lock. The naive floor |

To reproduce:

```bash
scripts/benchmark.sh
```

### Final results (v0.1.0)

All four caches in one JMH run, on the released code, so these numbers can be compared
with each other directly.

| Workload | Cache | 1 thread | 4 threads | 8 threads |
|---|---|---:|---:|---:|
| read_100 | chm | 95.2 | 311.0 | 430.3 |
| read_100 | caffeine | 49.7 | 136.1 | 244.5 |
| read_100 | kaffeine | 14.8 | 68.3 | 128.4 |
| read_100 | lhm-lock | 27.6 | 8.9 | 8.0 |
| read_75 | chm | 37.5 | 99.1 | 141.8 |
| read_75 | caffeine | 24.8 | 64.5 | 112.4 |
| read_75 | kaffeine | 11.1 | 37.8 | 55.8 |
| read_75 | lhm-lock | 23.1 | 9.7 | 8.6 |
| write_100 | chm | 37.8 | 58.6 | 56.4 |
| write_100 | caffeine | 27.3 | 51.5 | 66.5 |
| write_100 | kaffeine | 10.7 | 25.6 | 38.9 |
| write_100 | lhm-lock | 25.9 | 9.0 | 8.0 |

Kaffeine as a share of Caffeine, from the same run:

| Workload | 1 thread | 4 threads | 8 threads |
|---|---:|---:|---:|
| read_100 | 30% | 50% | 53% |
| read_75 | 45% | 59% | 50% |
| write_100 | 39% | 50% | 59% |

Kaffeine scales with threads the way Caffeine does, at about half its speed once several
threads are busy. On a single thread the gap is wider. Caffeine runs its policy upkeep on a
background thread pool, which a one-thread benchmark gets for free, while Kaffeine does that
work on the calling thread. The naive `LinkedHashMap` behind a lock gets slower with every
thread added, which is the problem all of this machinery exists to solve.

### How we got here

The tables below are from earlier runs, kept to show what each step changed. They come from
different sessions, so compare numbers only within one table.

#### v1: one lock around the policy

| Workload | Cache | 1 thread | 4 threads | 8 threads |
|---|---|---:|---:|---:|
| read_100 | chm | 90.3 | 283.3 | 397.3 |
| read_100 | caffeine | 36.5 | 169.7 | 206.7 |
| read_100 | kaffeine | 13.1 | 8.6 | 6.5 |
| read_100 | lhm-lock | 25.3 | 9.2 | 8.2 |
| read_75 | chm | 37.9 | 93.1 | 129.9 |
| read_75 | caffeine | 17.0 | 67.8 | 105.3 |
| read_75 | kaffeine | 12.2 | 6.9 | 5.8 |
| read_75 | lhm-lock | 18.5 | 10.2 | 7.2 |
| write_100 | chm | 36.9 | 60.5 | 54.1 |
| write_100 | caffeine | 25.3 | 59.3 | 59.0 |
| write_100 | kaffeine | 12.6 | 7.0 | 6.4 |
| write_100 | lhm-lock | 22.2 | 8.4 | 7.9 |

v1 takes the policy lock on every read and every write. So it gets slower as threads are
added: from 13.1 million reads per second on one thread down to 6.5 million on eight, while
Caffeine reaches 207 million. Every reader is waiting in line for the same lock.

#### v2: buffered policy updates

Only Kaffeine changed, so only Kaffeine was measured again, with the same settings. The
"vs" columns compare against the v1 run's numbers, from another session, so the "vs Caffeine"
column is too pessimistic (see the note on noise below, and the final results above).

| Workload | 1 thread | 4 threads | 8 threads | vs v1 at 8 threads | vs Caffeine at 8 threads |
|---|---:|---:|---:|---:|---:|
| read_100 | 13.2 | 50.3 | 77.2 | 11.9× | 37% |
| read_75 | 9.9 | 30.4 | 55.6 | 9.6× | 53% |
| write_100 | 9.2 | 25.5 | 37.7 | 5.9× | 64% |

Reads now leave a note in a lock-free striped buffer instead of taking the lock, and a
replaced value counts as a read. So v2 gets faster as threads are added, where v1 got slower.

The first v2 run livelocked at 8 threads. Every write queued a task, writers queued them
faster than the one draining thread could apply them, and the queue grew without limit.
Two changes fixed it: replacing a value goes through the read buffer, and once 1,024 tasks
are waiting a writer stops to help drain instead of adding more.

Kaffeine is still behind Caffeine, by about the same ratio at one thread as at eight. So the
gap is the cost of each operation, not how it scales. Two likely reasons: Caffeine applies
policy updates on a background thread pool, while Kaffeine does it on the calling thread,
and Caffeine's read path is more tuned (padding against false sharing, cheaper per-thread
stripe choice). Profiling this is on the list for step 4.

#### Cost of expiry

8 threads, Kaffeine and Caffeine measured in the same session. Expiry "on" means
`expireAfterWrite = 1 hour`, so both caches do all the expiry bookkeeping but nothing
actually expires.

```bash
THREADS=8 scripts/benchmark.sh -p cache=kaffeine,caffeine -p expiry=off,on
```

| Workload | Kaffeine off | Kaffeine on | Cost | Caffeine off | Caffeine on | Cost |
|---|---:|---:|---:|---:|---:|---:|
| read_100 | 117.1 | 68.7 | -41% | 190.7 | 94.0 | -51% |
| read_75 | 57.1 | 43.4 | -24% | 105.3 | 66.8 | -37% |
| write_100 | 38.3 | 29.9 | -22% | 56.3 | 40.7 | -28% |

Expiry costs both caches a similar share. Every read now reads the clock and checks the
entry's time, and a write that moves the expiry time by more than a second has to move the
entry in the timer wheel. With expiry on, Kaffeine reaches 65% to 73% of Caffeine.

**A note on noise.** Without expiry, Kaffeine did 117 million reads per second here, against
77 million in the v2 run above. Nothing in the read path changed between the two runs, so
that gap is just how much a laptop moves between sessions (heat, turbo, background work).
Only compare numbers from the same run. In this run, Kaffeine reaches 61% of Caffeine on
pure reads; the 37% in the v2 table compared against Caffeine numbers from an earlier session.
