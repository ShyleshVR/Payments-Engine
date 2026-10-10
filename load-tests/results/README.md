# Load-test results

One JSON per run, written by `scripts/load-test.sh`: the k6 summary (`k6`) and the server-side
metrics for the same window (`server`). [docs/LOAD_TEST.md](../../docs/LOAD_TEST.md) explains them.

A failed run here is not a bad result to hide: most of them are the evidence for a bottleneck,
taken before the fix that removed it. Runs that measured nothing useful were left out: warm-ups,
runs on pods that had just started, and one that overlapped a build on the same machine.

Columns: payments/s achieved; create p95; Payment → SUCCESS p95 as the client saw it; saga p95
on the server (30 s is the histogram's top bucket); the largest consumer lag.

## Baseline (main, before any fix)

| Run | Rate | Result | Create p95 | → SUCCESS p95 | Saga p95 | Max lag | Note |
|---|---|---|---|---|---|---|---|
| `20261010T044036Z-smoke-5.json` | 5/s | fail | 40 ms | 2.68 s | 2.40 s | 2 | Idle latency floor. Fails only create p99, on the first requests of cold pods |
| `20261010T044215Z-step-25.json` | 25/s | pass | 33 ms | 2.36 s | 2.34 s | 11 |  |
| `20261010T044613Z-step-50.json` | 50/s | pass | 22 ms | 2.35 s | 2.37 s | 47 |  |
| `20261010T045011Z-step-75.json` | 75/s | pass | 20 ms | 2.57 s | 2.45 s | 123 |  |
| `20261010T045409Z-step-100.json` | 100/s | pass | 20 ms | 2.92 s | 2.87 s | 89 | Baseline ceiling |
| `20261010T045802Z-step-125.json` | 125/s | fail | 34 ms | 14.44 s | 30.00 s | 96 | First failure: every replica fetched the same sagas (fix 1) |

## Fixes 1, 3 and 4

Measured with the old poll intervals pinned, so each change is seen alone.

| Run | Rate | Result | Create p95 | → SUCCESS p95 | Saga p95 | Max lag | Note |
|---|---|---|---|---|---|---|---|
| `20261010T050910Z-step-100.json` | 100/s | fail | 57 ms | 13.30 s | 30.00 s | 39 | After fix 1: sagas now flood the outboxes (fix 3) |
| `20261010T052340Z-step-100.json` | 100/s | fail | 234 ms | 10.09 s | 19.78 s | 967 | After fix 3: a thread per processor call burns CPU (fix 4) |
| `20261010T053952Z-step-100.json` | 100/s | pass | 63 ms | 2.55 s | 2.07 s | 132 | After fix 4 |
| `20261010T054345Z-step-125.json` | 125/s | pass | 20 ms | 2.54 s | 2.12 s | 197 |  |
| `20261010T054739Z-step-150.json` | 150/s | pass | 20 ms | 2.55 s | 2.43 s | 1,082 | Ceiling after fix 4 |
| `20261010T055137Z-step-200.json` | 200/s | fail | 43 ms | 9.64 s | 30.00 s | 22,759 | The ledger's 2 consumer threads fall behind (fix 5) |

## Fix 5, fix 2 and the Kafka bug

| Run | Rate | Result | Create p95 | → SUCCESS p95 | Saga p95 | Max lag | Note |
|---|---|---|---|---|---|---|---|
| `20261010T060457Z-step-150.json` | 150/s | fail | 460 ms | 15.06 s | 27.44 s | 21,027 | Freshly started pods: the cold-JVM evidence |
| `20261010T060901Z-step-150.json` | 150/s | pass | 36 ms | 2.55 s | 2.09 s | 108 | The same rate minutes later, warm: lag 1,082 → 108 |
| `20261010T061246Z-step-200.json` | 200/s | fail | 127 ms | 9.82 s | 9.49 s | 1,816 | After fix 5: the lag is gone, saga latency is still over the SLO |
| `20261010T061643Z-step-200.json` | 200/s | fail | 86 ms | 4.24 s | 3.91 s | 1,232 | Same rate, with Postgres sampled: commits queue on the write-ahead log |
| `20261010T062238Z-smoke-5.json` | 5/s | fail | 21 ms | 0.53 s | 0.35 s | 0 | After fix 2: idle latency. Fails only create p99, on cold pods |
| `20261010T062715Z-step-150.json` | 150/s | fail | 56 ms | 1.21 s | 1.05 s | 11,472 | Kafka 4.0.0 stalled every consumer group (fix 6) |

## Kafka 4.2.0

Tables still small (under ~0.5M payments).

| Run | Rate | Result | Create p95 | → SUCCESS p95 | Saga p95 | Max lag | Note |
|---|---|---|---|---|---|---|---|
| `20261010T064213Z-step-150.json` | 150/s | pass | 71 ms | 1.12 s | 0.90 s | 117 |  |
| `20261010T064602Z-step-200.json` | 200/s | pass | 89 ms | 2.86 s | 2.76 s | 439 | Ceiling 200/s |
| `20261010T064955Z-step-250.json` | 250/s | fail | 145 ms | 14.71 s | 30.00 s | 12,376 | First failure above the ceiling |
| `20261010T065418Z-step-200.json` | 200/s | pass | 75 ms | 1.61 s | 1.46 s | 214 | Second run at the ceiling |
| `20261010T065842Z-soak-140.json` | 140/s, 30 min | pass | 40 ms | 0.59 s | 0.73 s | 249 | First soak. Passed, but under-loaded by the harness's token bug (~120/s). Its 30 s saga tail led to fix 7 |
| `20261010T073006Z-spike-60.json` | 60 → 400/s | fail | 234 ms | 7.91 s | 30.00 s | 6,806 | First spike. Only 429s; the whole-run SLOs are expected to break at 2× the ceiling |

## Fixes 7 and 8, and the ledger's listener threads

Tables now hold about 1M payments.

| Run | Rate | Result | Create p95 | → SUCCESS p95 | Saga p95 | Max lag | Note |
|---|---|---|---|---|---|---|---|
| `20261010T074901Z-step-150.json` | 150/s | pass | 42 ms | 0.60 s | 0.51 s | 42 | After fix 7: no saga over 5 s |
| `20261010T075553Z-step-150.json` | 150/s | pass | 37 ms | 0.57 s | 0.49 s | 41 |  |
| `20261010T075942Z-step-200.json` | 200/s | fail | 98 ms | 14.12 s | 21.89 s | 3,089 | The outbox claim query slows down as the backlog grows (fix 8) |
| `20261010T081424Z-step-150.json` | 150/s | pass | 73 ms | 1.56 s | 0.95 s | 78 | After fix 8 |
| `20261010T081808Z-step-200.json` | 200/s | fail | 117 ms | 14.58 s | 30.00 s | 20,655 | After fix 8: the outbox is fine, the ledger falls behind |
| `20261010T082322Z-step-200.json` | 200/s | fail | 87 ms | 5.25 s | 5.29 s | 990 | The same, second run |
| `20261010T082923Z-step-200.json` | 200/s | pass | 96 ms | 1.70 s | 1.59 s | 2,790 | Ledger with 6 listener threads per pod |
| `20261010T083326Z-step-200.json` | 200/s | pass | 93 ms | 2.66 s | 2.22 s | 2,572 | Second run |
| `20261010T084151Z-step-150.json` | 150/s | pass | 41 ms | 0.56 s | 0.42 s | 66 |  |
| `20261010T084539Z-step-200.json` | 200/s | fail | 121 ms | 6.95 s | 6.85 s | 13,514 | The one failure in the last five runs at 200/s: saga dispatch fell behind for 90 s |
| `20261010T085008Z-step-200.json` | 200/s | pass | 94 ms | 2.12 s | 1.84 s | 2,190 |  |
| `20261010T085432Z-soak-140.json` | 140/s, 30 min | fail | 43 ms | 0.57 s | 0.40 s | 59 | Under-loaded (~120/s). Outbox age crept to 7 s: queue tables vacuumed too rarely (fix 9) |
| `20261010T092556Z-spike-60.json` | 60 → 400/s | fail | 248 ms | 11.83 s | 30.00 s | 6,656 | Final spike. Only 429s; saga latency normal 85 s after the peak |

## Final code (after fix 9)

About 1.5M to 2.7M payments in the tables.

| Run | Rate | Result | Create p95 | → SUCCESS p95 | Saga p95 | Max lag | Note |
|---|---|---|---|---|---|---|---|
| `20261010T100824Z-step-150.json` | 150/s | pass | 44 ms | 0.57 s | 0.42 s | 27 | Final ceiling search |
| `20261010T101212Z-step-200.json` | 200/s | pass | 105 ms | 2.17 s | 1.92 s | 9,394 | Final ceiling: 200/s |
| `20261010T101607Z-step-250.json` | 250/s | fail | 161 ms | 14.52 s | 30.00 s | 25,093 | First failure above the ceiling |
| `20261010T102800Z-soak-140.json` | 140/s, 30 min | pass | 54 ms | 1.06 s | 0.53 s | 850 | Passed, but under-loaded (~120/s): the run in which the token bug was found |
| `20261010T105943Z-smoke-5.json` | 5/s | pass | 17 ms | 0.53 s | 0.33 s | 0 | Idle latency on the final code, with the fixed harness |
| `20261010T113627Z-soak-140.json` | 140/s, 30 min | fail | 86 ms | 9.48 s | 9.76 s | 2,644 | After a reboot: two cold pods joined at the start (saga p95 13–15 s for 3.5 min, then 0.35–1.0 s), and token renewal still hit the per-IP limit |
| `20261010T120823Z-step-150.json` | 150/s | pass | 49 ms | 0.56 s | 0.49 s | 49 | With one merchant per virtual user; brings the autoscaler to 4 warm pods before the soak |
| `20261010T121247Z-soak-140.json` | 140/s, 30 min | pass | 44 ms | 0.57 s | 0.44 s | 195 | **The final soak**: the full 140/s for 30 minutes, every SLO met |
