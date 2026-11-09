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
| DS1 | database (ARC) | +20.4 | +0.76 | +0.21 | 4.1 |
| S3 | web search (ARC) | +18.1 | +1.39 | -0.42 | 14.6 |
| OLTP | database (ARC) | -0.3 | -3.19 | -0.07 | 15.5 |
| loop | loop (LIRS) | +57.8 | -1.05 | -0.42 | 3.9 |
| gli | mixed (LIRS) | +21.7 | +3.63 | +0.19 | 1.4 |
| multi2 | mixed (LIRS) | +11.8 | -0.30 | +0.06 | 4.4 |
| sprite | file system (LIRS) | +1.7 | -1.37 | -0.06 | 15.1 |

Over all 29 data points, Kaffeine is **0.13 points behind Caffeine on average**.

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

**The doorkeeper did not earn its keep.** It helped at only 15 of the 29 points, and on
average it moved the hit ratio by about -0.1 points, while costing about 10 extra bytes per entry. The
TinyLFU paper uses it so the sketch can be made smaller for the same accuracy. We keep the
sketch at full size, so the doorkeeper only adds noise from its false positives and from
being cleared at every reset. It is off by default and stays available as an option.

### Per trace

Hit ratio in %, higher is better.

#### DS1 (43,704,979 requests)

| Cache size | lru | kaffeine | kaffeine-doorkeeper | caffeine | belady |
|---:|---:|---:|---:|---:|---:|
| 1,000,000 | 3.09 | 14.36 | 14.65 | 12.38 | 20.19 |
| 2,000,000 | 10.74 | 28.57 | 29.14 | 28.15 | 31.79 |
| 4,000,000 | 20.24 | 45.27 | 45.21 | 45.29 | 48.09 |
| 8,000,000 | 43.03 | 70.38 | 70.41 | 69.72 | 74.93 |

#### S3 (16,407,702 requests)

| Cache size | lru | kaffeine | kaffeine-doorkeeper | caffeine | belady |
|---:|---:|---:|---:|---:|---:|
| 100,000 | 2.33 | 11.90 | 10.86 | 10.93 | 25.42 |
| 200,000 | 4.63 | 22.99 | 22.40 | 21.29 | 39.79 |
| 400,000 | 12.04 | 42.65 | 42.53 | 41.07 | 59.96 |
| 800,000 | 56.60 | 70.55 | 70.62 | 69.24 | 81.27 |

#### OLTP (914,145 requests)

| Cache size | lru | kaffeine | kaffeine-doorkeeper | caffeine | belady |
|---:|---:|---:|---:|---:|---:|
| 1,000 | 32.83 | 35.63 | 35.49 | 39.98 | 53.61 |
| 2,000 | 42.47 | 42.11 | 42.10 | 46.33 | 60.40 |
| 5,000 | 53.65 | 52.52 | 52.34 | 54.43 | 68.27 |
| 10,000 | 60.70 | 59.83 | 59.75 | 62.66 | 73.02 |
| 15,000 | 64.63 | 62.86 | 62.93 | 65.50 | 75.14 |

#### loop (505,500 requests)

| Cache size | lru | kaffeine | kaffeine-doorkeeper | caffeine | belady |
|---:|---:|---:|---:|---:|---:|
| 250 | 0.00 | 24.17 | 20.05 | 22.41 | 24.63 |
| 500 | 0.00 | 48.83 | 48.85 | 47.76 | 49.36 |
| 750 | 0.00 | 64.64 | 65.66 | 68.03 | 74.04 |
| 1,000 | 0.00 | 93.39 | 94.79 | 97.02 | 98.71 |

#### gli (6,015 requests)

| Cache size | lru | kaffeine | kaffeine-doorkeeper | caffeine | belady |
|---:|---:|---:|---:|---:|---:|
| 250 | 0.91 | 16.14 | 16.34 | 14.98 | 17.64 |
| 500 | 0.95 | 33.15 | 33.60 | 27.78 | 34.26 |
| 1,000 | 11.21 | 50.12 | 50.22 | 42.13 | 53.13 |
| 2,000 | 57.41 | 57.96 | 57.96 | 57.96 | 57.96 |

#### multi2 (26,311 requests)

| Cache size | lru | kaffeine | kaffeine-doorkeeper | caffeine | belady |
|---:|---:|---:|---:|---:|---:|
| 500 | 35.98 | 47.71 | 46.91 | 49.10 | 53.60 |
| 1,000 | 47.80 | 56.01 | 57.11 | 57.98 | 62.16 |
| 2,000 | 49.00 | 69.67 | 69.65 | 69.36 | 74.65 |
| 3,000 | 71.18 | 77.95 | 77.92 | 76.13 | 78.40 |

#### sprite (133,996 requests)

| Cache size | lru | kaffeine | kaffeine-doorkeeper | caffeine | belady |
|---:|---:|---:|---:|---:|---:|
| 100 | 21.58 | 31.20 | 30.26 | 31.72 | 50.80 |
| 250 | 48.84 | 55.34 | 55.58 | 54.83 | 74.48 |
| 500 | 78.30 | 74.64 | 74.92 | 78.11 | 87.88 |
| 1,000 | 90.64 | 84.84 | 85.03 | 86.86 | 93.24 |

### Traces

- **ARC traces** (DS1, S3, OLTP): N. Megiddo and D. S. Modha, "ARC: A Self-Tuning, Low
  Overhead Replacement Cache", FAST 2003. Cache sizes match the ones used in the paper.
- **LIRS traces** (loop, gli, multi2, sprite): S. Jiang and X. Zhang, "LIRS: An Efficient
  Low Inter-reference Recency Set Replacement Policy", SIGMETRICS 2002. Cache sizes are
  spread over each trace's number of unique keys.
