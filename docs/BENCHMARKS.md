# Benchmarks

Every number here was measured on 2026-10-02 and comes from a file in [`bench/results/`](../bench/results) or
[`bench/dedup/`](../bench/dedup). Scripts to reproduce each one are in [`bench/`](../bench).

**Machine:** Apple M5 (10 cores, 16 GB), macOS, Zulu JDK 21.0.11, Spring Boot 4.1.1. H2 runs embedded in the
JVM. Postgres 18 ran in Docker under Colima (a VM with 2 vCPU and 2 GB, reached through port forwarding).
**Reporting:** medians, never best runs; min-max where there were enough runs.

The performance figures were measured before a later round of correctness fixes (listing completeness, board
identity, cache race, export paging). Those fixes don't touch the measured hot paths: fetching, scoring, the
dedup inner loop, or the ranked query.

## 1. Ingest: sequential vs. platform thread pool vs. virtual threads

Three fetch strategies run the same pipeline (fetch, parse, score, persist, dedup). Only the thread model
changes. The platform pool (16 threads) is there so the comparison asks "are virtual threads better than a thread
pool?", not just "is concurrency better than none?".

### Replay (controlled)

`IngestBenchmark` replays recorded real payloads of 45 boards (15 Greenhouse, 15 Lever, 15 Ashby: 9,479 postings).
Each ATS is served by its own WireMock server, so the per-host rate limiter sees three hosts as in production,
with a uniform random 150-600 ms delay per response (live response times were 145-600 ms). Each configuration
gets 1 discarded warm-up and 5 measured runs, and the database is emptied before every run.
Source: [`ingest-summary-2026-10-02.md`](../bench/results/ingest-summary-2026-10-02.md).

| Rate limit | Boards | Sequential | Platform pool (16) | Virtual threads | Virtual vs. sequential | Virtual vs. pool |
|---|---|---|---|---|---|---|
| none | 15 | 6.89 s | 1.24 s | 1.26 s | 5.5x | 1.0x |
| none | 30 | 13.68 s | 2.08 s | 1.61 s | 8.5x | 1.3x |
| none | 45 | 20.58 s | 2.77 s | 2.09 s | **9.9x** | **1.3x** |
| 2 req/s/host | 15 | 7.24 s | 2.68 s | 2.72 s | 2.7x | 1.0x |
| 2 req/s/host | 30 | 14.42 s | 6.25 s | 5.44 s | 2.6x | 1.1x |
| 2 req/s/host | 45 | 22.52 s | 11.73 s | 8.35 s | **2.7x** | **1.4x** |

What this shows:
- With no limit, wall time is mostly latency, and both concurrent models hide it. Virtual threads pull ahead of
  the 16-thread pool once there are more boards than pool threads.
- With the production limit, the 15 boards per host can't go faster than the bucket allows: 2 requests at once,
  then one every 0.5 s, comes to about 6.5 s per host plus response time. Virtual threads get close (8.35 s). The pool is slower because its
  threads park inside the rate limiter while holding a pool slot, so boards on other hosts queue behind them
  (head-of-line blocking). With one virtual thread per board, nothing waits behind a sleeping thread.
- At N = 15 the pool has enough threads for every board, and the two concurrent models tie.

### Live

[`bench/live-ingest.sh`](../bench/live-ingest.sh) ran the same 45 boards against the real APIs with the production
limiter, 3 rounds, rotating which mode went first. Source: [`live-ingest-2026-10-02.csv`](../bench/results/live-ingest-2026-10-02.csv).

| Mode | Median wall | Median fetch phase | Runs (wall) |
|---|---|---|---|
| Sequential | 42.3 s | 27.8 s | 36.5 / 42.3 / 45.0 s |
| Platform pool (16) | 24.1 s | 9.9 s | 23.9 / 24.1 / 26.2 s |
| Virtual threads | 21.7 s | 7.7 s | 20.9 / 21.7 / 21.9 s |

At the time of this run the persist phase was a flat ~14 s in every mode, which is what section 2 fixed.

### Full crawl

All 51 configured boards (including the paged Workday and SmartRecruiters sources, whose detail calls are capped
at 50 per board), virtual threads: **11,770-11,773 postings in 84-94 s**, almost all of it fetch time under the
2 req/s/host limit ([`full-crawl-2026-10-02.json`](../bench/results/full-crawl-2026-10-02.json), [`full-crawl-2026-10-02-postgres.json`](../bench/results/full-crawl-2026-10-02-postgres.json)).

## 2. Re-sync persistence: profiled and cut 4.5x

A daily crawl mostly re-syncs postings that are already stored. A Java Flight Recorder profile of one re-sync of
the 45 boards attributed ingest-thread CPU samples to:

| Where | Share of samples | Cause |
|---|---|---|
| Dedup refresh | 63% | Token Jaccard re-tokenized both titles with regexes on every comparison inside the O(n x leaders) clustering loop |
| Skill scoring | 35% | Building hash sets of every 1-3 word phrase of every description |
| Database work | negligible | |

The fixes: prepare every dedup candidate once (tokens, level words, location keys), and match skills with an
alias index keyed by first token in a single pass over the tokens.

[`bench/resync-compare.sh`](../bench/resync-compare.sh) alternated the before and after jars. Each run started
from a fresh copy of the same crawl snapshot, 3 runs each. Source: [`resync-2026-10-02.csv`](../bench/results/resync-2026-10-02.csv).

| | Persist phase (median) | Wall (median) |
|---|---|---|
| Before | 14,556 ms (14.0-15.2 s) | 23,140 ms |
| After | 3,232 ms (3.18-3.28 s) | 10,908 ms |
| Change | **4.5x faster** | 2.1x faster |

## 3. Deduplication quality

### How the samples were drawn and labeled

[`PairSampler`](../src/main/java/io/github/jozephzemambo/jobradar/dedup/eval/PairSampler.java) draws a seeded,
stratified sample of same-company pairs from the real crawl (11,773 open postings):
- **predicted**: 50 pairs drawn uniformly from all pairs the rule calls duplicates. Precision and its Wilson 95%
  interval come straight from this stratum.
- **near-miss**: 50 pairs the rule rejects but whose titles are at least 50% similar, split into similarity bands
  [0.5, 0.7), [0.7, 0.9) and [0.9, 1.0]. Each pair is weighted by stratum population / sample size, so recall can
  be estimated. Pairs below 0.5 title similarity are assumed distinct.

Labels came from **an LLM labeling blind**. It was given a shuffled file with the rule's output and the stratum
removed, plus a written rubric: same role, level, specialization and employment type, and an overlapping location.
The labels, each with a one-line reason, are in the CSVs. These are **LLM-labeled** samples, not hand-labeled ones.

### Results

Three rounds. Each sample was drawn only after the rule it measures had been committed, and each was disjoint from
every earlier sample.

| Sample | Rule measured | Precision | Wilson 95% | Source |
|---|---|---|---|---|
| 1. Development (100 pairs) | original: token Jaccard >= 0.8 | 39/50 = **78.0%** | 64.8-87.2% | [`labeled-pairs.csv`](../bench/dedup/labeled-pairs.csv) |
| 2. Validation (100 pairs) | round-1 fixes, Jaccard >= 0.85 | 43/50 = **86.0%** | 73.8-93.0% | [`validation-labeled.csv`](../bench/dedup/validation-labeled.csv) |
| 3. Held-out (100 pairs) | round-2 fixes, identical title signatures | 47/50 = **94.0%** | 83.8-97.9% | [`holdout2-labeled.csv`](../bench/dedup/holdout2-labeled.csv) |

**Round 1** (from sample 1's false positives):
- **State names treated as cities.** "Mountain View, California" matched "San Francisco, California" on
  "california".
- **"Hybrid" treated as a place.**
- **The 0.8 threshold.** 7 of the 9 predicted pairs at exactly 0.8 were distinct roles: a five-word title plus one
  specialization word. Raised to 0.85.
- **Requisition ids inside titles.**

**Round 2** (from sample 2's false positives, which had two causes):
- **Remote postings in different regions overlapped** ("Florida, USA, Remote" vs. "Texas, USA, Remote" on "us"
  and "remote"; 4 of 7). Regions now have levels (state/province > country > "remote", "EMEA"), and a location
  without a city is keyed by its most specific region only. A remote posting is keyed apart from an office
  posting in the same region.
- **One specialization word in a long title still cleared 0.85** ("... Autonomous Pilot Integration - Weapons";
  3 of 7). Across samples 1 and 2, 11 of the 14 predicted pairs with non-identical titles were distinct roles. Two
  of the three duplicates differed only by a requisition id or "Engineer" vs. "Engineering". Dedup now requires
  identical **title signatures**: tokens with stop words, stray letters and gender markers ("F/H/NB") removed, and
  a light suffix stem.

Samples 1 and 2 are development data now. On them the round-2 rule scores 97.4% and 100% weighted precision, but
those numbers are in-sample. Sample 3 is the honest measure. `DedupRegressionTest` fails the build if the rule
gets worse on samples 2 or 3.

**Recall.** Samples 1 and 2 had no duplicates among their 100 near-miss pairs. Sample 3 had one: the same
healthcare FDE role in Philadelphia and Chicago, whose descriptions both allow remote work across the Central and
Eastern time zones, which the location rule can't see. Weighting it by its stratum gives a recall estimate of
79.4%, but that rests on a single pair (weight 217), so treat it as "most duplicates are caught, not all", not as a
precise figure.

**Remaining failure modes** (sample 3's false positives):
1. Same words, different role. "Senior Enterprise Customer Success Manager" (an individual contributor) and
   "Senior Manager, Enterprise Customer Success" (a people manager) have identical token sets; word order carries
   the meaning.
2. A truncated Workday list title. The URL slug of one posting reads "Analytics Sr Software Engineer", but its
   listed title doesn't.
3. Both postings located only as "Distributed". This is ambiguous, and the rubric defaults ambiguous pairs to
   distinct.

**Caveat on the unit.** Precision is measured over *pairs*, so a role reposted many times (several Western Digital
technician reqs) contributes many pairs. All three samples share this design, so they are comparable to each
other.

**Labeling.** All three samples were labeled blind by an LLM against the same written rubric. In each round, a
second LLM pass re-checked 15 labels, including every disputed and low-confidence one. In round 3 it agreed with
all 15, one of them an ambiguous case the rubric resolves as distinct.

**On the real crawl** the current rule links 352 of 11,773 open postings (3.0%) as duplicates (392 under the round-1
rule), none of them by exact URL: each board gives every posting its own URL.

## 4. API latency

[`bench/load-test.sh`](../bench/load-test.sh) runs ApacheBench with keep-alive: 2,000 warm-up requests, then
5,000 measured requests at concurrency 10 and 50, against about 11,770 postings from a real crawl. Endpoints:
- ranked list: `/api/postings?minScore=0.5&size=50`
- title search: `/api/postings?ats=GREENHOUSE&q=engineer&size=50`
- posting detail
- `/api/stats`

### H2 (embedded), before and after caching stats

| Endpoint (c=10) | p50 | p95 | p99 | req/s |
|---|---|---|---|---|
| Ranked list | 2 ms | 5 ms | 20 ms | 3,369 |
| Posting detail | 2 ms | 8 ms | 10 ms | 4,652 |
| Title search | 70 ms | 81 ms | 90 ms | 141 |
| Stats, uncached | 217 ms | 242 ms | 265 ms | 45 |
| Stats, cached between ingests | <1 ms | 1 ms | 1 ms | 25,565 |

Title search on H2 stays at about 141 req/s whether concurrency is 10 or 50. Throughput that doesn't grow with
concurrency points to serialization inside the embedded database rather than slow SQL. A single request takes
about 13 ms.

### Postgres, before and after a partial index for the ranked list

The ranked list was slower on Postgres (p50 47 ms). `EXPLAIN ANALYZE` showed a sequential scan plus a top-N sort
(5.8 ms) on every request, which saturated the 2-vCPU database VM at about 207 req/s. A partial index matching the
query's `WHERE` and `ORDER BY` turns that into an index scan that stops after 50 rows (0.48 ms).

| Endpoint (c=10) | Before p50 / p95 | After p50 / p95 | req/s before -> after |
|---|---|---|---|
| Ranked list | 47 / 66 ms | **20 / 31 ms** | 207 -> **471** |
| Title search | 32 / 43 ms | 25 / 36 ms | 313 -> 392 |
| Posting detail | 4 / 6 ms | 4 / 5 ms | 2,394 -> 2,547 |

H2 and Postgres absolute numbers are not a fair database comparison. H2 runs in-process, while Postgres sat
behind a 2-vCPU VM and port forwarding. The before/after rows on the same database are the meaningful comparison.

## 5. Tests and coverage

265 tests (`./mvnw verify`) cover:
- unit and parameterized tests;
- WireMock contract tests built from recorded real responses of all five ATSes;
- `@DataJpaTest` and `@WebMvcTest` slices;
- one end-to-end `@SpringBootTest`;
- a Testcontainers Postgres test.

JaCoCo: **96.8% line and 88.1% branch coverage** (1,668 / 1,723 lines), with no exclusions. The build fails below
90% / 80%.
