# Load Testing

How much traffic PayFlow sustains **while meeting its SLOs**, what limited it, and what was
changed. Every number here comes from a run of the harness in this repository against the local
Kubernetes cluster. These numbers are for one laptop, not a capacity plan.

**Result:** the ceiling went from **100 to 200 payments/s** (about 420 HTTP requests/s through
the gateway: each payment is created, read back, and sometimes followed by a balance read or a
refund).
- Payment → SUCCESS p95 at idle went from **2.7 s to 0.53 s**; at 150/s it is 0.57 s.
- Nine bottlenecks were found from evidence and fixed one at a time: eight in PayFlow and one
  in Kafka itself.
- At 250/s the single Postgres instance and the node's CPU run out together; see
  [the current limit](#the-current-limit).

## Contents

1. [Environment](#environment)
2. [SLOs](#slos)
3. [The harness](#the-harness)
4. [Method](#method)
5. [Results](#results)
6. [Bottlenecks, one at a time](#bottlenecks-one-at-a-time)
7. [Soak and spike](#soak-and-spike) (and the money check)
8. [The current limit](#the-current-limit)
9. [Running it](#running-it)
10. [File map](#file-map)

---

## Environment

- **Machine:** one Docker Desktop Kubernetes node: 12 CPUs, 16 GB, a WSL2 virtual disk.
- **What runs on it:** everything at once, 27 pods. That's the services, one Postgres
  (8 databases), one Kafka broker, Redis, Prometheus, Grafana, Jaeger, and the load generator
  itself.
- **Replicas:**
  - payment-service and api-gateway autoscale from 2 to 4 pods (the HPA).
  - Every other service runs 2.
  - Each pod has a Hikari pool of 10 connections.
- **Generator:** k6, limited to 1 CPU so it can't starve the system under test. It sends
  traffic through the gateway as 50 merchants. The gateway allows 20 requests/s per merchant,
  so 50 merchants keep the rate limit out of the result.

The numbers are for this machine. Relative improvements (before vs after a fix) carry over to
other machines; the absolute ceiling does not.

## SLOs

A rate counts as sustained only if **every** SLO holds for a steady 3-minute run:

| SLO | Threshold | Measured by |
|---|---|---|
| Create payment | p95 < 150 ms, p99 < 300 ms | k6, `POST /api/v1/payments` |
| Reads (payment, balance) | p99 < 200 ms | k6 |
| Payment → SUCCESS | p95 < 3 s | server: `saga_duration_seconds` (payment sagas that completed); client: 2% of payments polled until SUCCESS |
| Errors | < 0.1% failed requests; > 99.9% checks pass | k6 |
| Keeping up | 0 dropped iterations; 0 iterations that failed before creating their payment; oldest pending outbox row < 5 s; consumer lag drained within 60 s of the end | k6, Prometheus |

The **ceiling** is the highest rate in the search that passes.

## The harness

- **[`load-tests/k6/payflow.js`](../load-tests/k6/payflow.js):** the k6 script.
  - `setup()` takes an operator token, onboards 50 merchants through the API, and gets their
    client-credentials tokens. Tokens are renewed during long runs.
  - Each iteration creates a payment (with an Idempotency-Key) and reads it back. 10% also
    read the merchant balance. 2% poll the payment until SUCCESS; half of those then refund it.
  - The SLOs are k6 thresholds. `handleSummary` prints a JSON summary for the driver.
- **[`k8s/load/k6-job.yaml`](../k8s/load/k6-job.yaml):** a Kubernetes Job that runs the script
  in the cluster against `api-gateway.payflow.svc`.
  - It is limited to 1 CPU, runs as non-root and has a read-only filesystem.
  - It sends k6 metrics to Prometheus by remote write, so they appear next to the services'
    metrics in Grafana.
- **[`scripts/load-test.sh`](../scripts/load-test.sh) `<smoke|step|soak|spike>`:** runs one job.
  It then runs [`load-tests/collect.py`](../load-tests/collect.py), which queries Prometheus
  for the server side of the same window:
  - saga p95, outbox age and backlog, consumer lag and the time it takes to drain;
  - CPU per service, Hikari active and pending connections, heap, replicas.

  It writes one JSON file per run to `load-tests/results/` and prints PASS or FAIL with the
  reasons.
- **[`scripts/load-ceiling.sh`](../scripts/load-ceiling.sh) `[rate ...]`:** step runs at
  increasing rates until one fails. It starts with a 2-minute warm-up at half the first rate
  that isn't judged (see [cold JVMs](#cold-jvms-a-measurement-trap)).
- **Grafana "PayFlow Load Test" dashboard:** k6 request rate, latency by endpoint, errors,
  dropped iterations, saga p95, outbox age, consumer lag, CPU per service, Hikari pending.

## Method

Measure, find the bottleneck from evidence, fix one thing, measure again:

1. Run a ceiling search and look at the first rate that fails: which SLO broke, and which
   resource was saturated (CPU, pool, lag, outbox).
2. If the cause isn't visible in metrics, profile. A JFR recording from a pod was summarised
   by thread and by stack.
3. Make one change, with tests for the changed behaviour. The module's whole suite must still
   pass.
4. Search again and record before and after.

While fixes 1, 3, 4 and 5 were measured, the services kept their old poll intervals, applied
through environment overrides. So each latency improvement is attributed to the fix that made
it. Fix 2 (the poll intervals) was measured last, on its own.

## Results

| Stage | Ceiling | First failure above it | Evidence |
|---|---|---|---|
| Baseline | **100/s** | 125/s: saga p95 30 s | Sagas advanced only ~110/s with 4 pods, although CPU was 62% and the processor answered in 30 ms (p95) |
| + 1. Saga reservations | < 100/s | 100/s: outbox backlog | Ledger outbox 13,554 pending (oldest 45 s), payment outbox 8,019 (21 s); late replies caused re-sent commands |
| + 3. Batched outbox relays | < 100/s | 100/s: create p95 234 ms, saga p95 19.8 s | payment-service Hikari 10/10 with 4 waiting; 3.2 cores |
| + 4. HTTP client executor | **150/s** | 200/s: saga p95 30 s | Ledger consumer lag 22,759: 2 consumer threads × 9.7 ms per command |
| + 5. Partitions and listener threads | 150/s | 200/s: saga p95 3.9–9.5 s | Lag at 150/s: 1,082 → 108 |
| + 2. Shorter polls, in-thread continuation | 150/s | 150/s: consumers stalled | Kafka 4.0.0 coordinator bug ([below](#6-kafka-400-stalled-every-consumer-group)) |
| + 6. Kafka 4.2.0 | 200/s | 250/s: saga p95 30 s | Passed twice while the tables were still small |
| + 7. Claims wait for a lock | 150/s | 200/s: saga p95 22 s | 2.7% of sagas no longer wait 30 s; but by now the tables held ~1M payments |
| + 8. Outbox claim query | 150/s | 200/s: saga p95 30 s | Outbox backlog gone; the ledger fell behind (lag 20,655) and replies were re-sent |
| + 6 ledger listener threads | 200/s | 200/s in 1 of 4 runs | Ledger lag 20,655 → 32–51 |
| + 9. Queue-table autovacuum | **200/s** | 250/s: create p95 161 ms, saga p95 30 s | Soak outbox age no longer creeps |

Two things the table shows:
- **A fix can move the failure instead of removing it.** Fix 1 made sagas fast enough to flood
  the outboxes; fix 3 then exposed the CPU cost of fix 4's bug.
- **The data grew during the day.** By fix 7 the tables held about a million payments, and
  200/s, which had passed on small tables, failed again. Fixes 8 and 9 and the ledger threads
  are about keeping performance flat as tables grow.

At 200/s the system runs at this machine's edge. On the final settings, 200/s passed in 4 of
its last 5 runs, and a different component tipped in the one that failed. 150/s passes every
time.

At the final ceiling (final code, ~1.5M payments in the tables):

| | 150/s | 200/s |
|---|---|---|
| HTTP requests/s | 311 | 416 |
| Create p95 / p99 | 44 / 69 ms | 105 / 153 ms |
| Read p99 (payment / balance) | 23 / 38 ms | 65 / 79 ms |
| Payment → SUCCESS (client) p50 / p95 | 0.53 / 0.57 s | 1.10 / 2.17 s |
| Saga p95 / p99 (server) | 0.42 / 0.57 s | 1.92 / 2.32 s |
| Max consumer lag, drained after | 27, 5 s | 9,394 (notifications), 10 s |
| payment-service CPU (4 pods) | 1.6 cores | 2.1 cores |
| Node CPU busy | 67% | 78% |
| Errors, dropped iterations | 0, 0 | 0, 0 |

The 200/s column is the run from the final ceiling search. Across the four passing runs at
200/s on the final settings: create p95 93–105 ms, Payment → SUCCESS p95 1.7–2.7 s, saga p95
1.6–2.2 s. At 150/s the three runs gave create p95 41–49 ms and saga p95 0.42–0.49 s.

Latency floor (5 payments/s, idle system): Payment → SUCCESS p95 as the client sees it
**2.7 s → 0.53 s**; saga p95 on the server **2.4 s → 0.33 s**.

## Bottlenecks, one at a time

### 1. Every replica fetched the same sagas

- **Symptom:** at 125/s sagas fell behind (p95 30 s) while CPU sat at 62% and the processor
  answered in 30 ms.
- **Cause:** each replica's worker asked for "the 100 oldest due sagas". All four replicas got
  the *same* 100 ids. Then they raced to lock them with `SKIP LOCKED`, and the losers had
  nothing to do until the next poll. Adding pods added no throughput.
- **Fix:** `SagaReservations` reserves a disjoint batch per replica in one statement:
  `UPDATE … SET next_attempt_at = <lease> WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED) RETURNING id`.
  - The reservation timestamp is the token: a step only runs if the row still carries that
    exact value (`lockReserved`).
  - So a reservation that expired and was taken by another replica can't run twice.
  - The worker keeps reserving while batches come back full.
- **Tests:** `SagaReservationsTest`, against real Postgres. Four concurrent reservers get
  disjoint batches; only due sagas are handed out; a claim works only with the exact token.

Fixing this exposed the next bottleneck immediately: sagas now ran fast enough to flood the
outboxes.

### 3. Outbox relays: one synchronous send per row

- **Symptom:** at 100/s the ledger outbox had 13,554 pending rows, the oldest 45 s old.
  Replies arrived after the orchestrator's 15 s reply timeout, so it re-sent commands, which
  made the backlog worse (a retry storm, though deduplication kept it correct).
- **Cause:** each relay (payment, ledger, payout) sent one row, waited for the broker's ack,
  then sent the next. That's one round trip per message per pod.
- **Fix:** each poll claims up to 100 rows, at most one per aggregate so per-payment order is
  kept. The relay sends them all, then waits for the acks, and marks each row by its own
  outcome. A failed send is retried later without blocking the others. The relay loops while
  batches come back full.
- **Tests:**
  - `everyEventOfABatchIsSentBeforeAnyAckIsAwaited`
  - `aFailedSendInABatchIsRetriedLaterAndTheOthersArePublished`
  - the existing ordering and at-least-once tests.

### 4. A new thread for every processor call

- **Symptom:** at 100/s payment-service used 3.2 cores and its Hikari pool was full, with no
  obvious hot code.
- **Evidence:** a 2-minute JFR recording from a pod.
  - Threads named `SimpleAsyncTaskExecutor-N` used **38% of the sampled CPU**.
  - Thread ids passed 4,200 within the recording.
- **Cause:** Spring's `JdkClientHttpRequestFactory` writes the request body on a new thread
  whenever the JDK `HttpClient` has no executor of its own. That's one thread created and
  destroyed per processor call (and per call from payout and reconciliation).
- **Fix:** give each `HttpClient` an executor of its own: a cached pool of reused daemon
  threads.
- **Test:** `ProcessorClientTest` makes 50 calls against a local HTTP server and asserts that
  fewer than 10 threads were started. It fails without the fix (50 started) and passes with
  it.
- **Result:** ceiling 100 → 150/s. At 125/s payment-service used 1.4 cores.

### 5. Two consumer threads for the whole ledger

- **Symptom:** at 200/s ledger consumer lag reached 22,759 while ledger-service used only
  0.4 cores.
- **Cause:**
  - Every topic had 3 partitions and every listener 1 thread, so the ledger had 2 serial
    consumers (2 pods).
  - At 9.7 ms per command, that is ~200 commands/s at most.
  - notification-service and webhook-service were close to the same limit.
- **Fix:**
  - 12 partitions for every topic (`payflow.kafka.partitions`, `PAYFLOW_KAFKA_PARTITIONS`,
    the broker default).
  - 3 listener threads per pod (`payflow.kafka.listener-concurrency`).
  - Each partition still has one consumer thread, so one payment's messages stay in order.
  - Adding partitions to an existing topic remaps keys, so it was applied with the system
    idle.
- **Result:** at 150/s the lag peak fell from 1,082 to 108. 200/s still failed, now on saga
  latency rather than lag.
- **Later, with ~1M payments in the tables:** the ledger fell behind again at 200/s (lag
  20,655). Its consumers used 0.5 cores: they were waiting on commits, not computing. Replies
  then arrived after the 15 s reply timeout, and the orchestrator re-sent ~20,000 commands,
  adding load to the one service that was behind. The re-sends back off exponentially, so this
  is bounded, but it turned a slow minute into a failed run.
  - The ledger now runs 6 listener threads per pod, so 2 pods × 6 cover the 12 partitions.
    More commits in flight let Postgres flush more of them per write.
  - Two runs at 200/s then passed: ledger lag 32–38, saga p95 1.6 s and 2.2 s.

### 2. Every hop waited for a poll

- **Symptom:** even idle, a payment took 1.9 s on average to reach SUCCESS.
- **Cause:** a payment passes through these steps in order:
  1. saga poll (500 ms);
  2. authorize;
  3. capture;
  4. payment outbox poll (500 ms);
  5. ledger;
  6. ledger outbox poll (1 s);
  7. reply.

  Each step after the first was also picked up by the next saga poll.
- **Fix:**
  - **Polls:** saga and outbox polls every 100 ms. Polls that find nothing are cheap
    indexed reads.
  - **In-thread continuation:** after a processor step, the worker that just recorded it runs
    the next processor step itself, up to 4 in a row. It reserves the saga again in the same
    transaction that records the step, so authorize → capture doesn't wait for a poll.
- **Result:** idle Payment → SUCCESS p95 2.7 s → 0.53 s (saga p95 on the server 2.4 s → 0.35 s).

### 6. Kafka 4.0.0 stalled every consumer group

- **Symptom:** during a 150/s run every consumer group stopped consuming. Lag kept growing
  after the run ended, and the orchestrator re-sent ledger commands whose replies timed out.
- **Evidence:**
  - Consumers logged `COORDINATOR_NOT_AVAILABLE` on every offset commit.
  - The broker logged `ArrayIndexOutOfBoundsException: Histogram recorded value cannot be
    negative` in the group coordinator's `FlushBatch`, at 06:10, 06:17 and 06:29.
- **Cause:** the new group coordinator in Kafka 4.0 measures event queue time from wall-clock
  milliseconds. When the clock steps back (Docker Desktop's VM clock does), the time is
  negative, recording it throws, and the whole batch of offset commits fails.
  - Checking the bytecode showed that 4.0.1, 4.1.0 and 4.1.1 still record the raw value.
  - 4.2.0 clamps it at 0.
- **Fix:** `apache/kafka:4.2.0` in the cluster, docker-compose and the Testcontainers tests.
- **Correctness held:** once the broker was replaced, every backlog drained.
  - Unfinished sagas: 0.
  - Sagas needing an operator: 0.
  - The re-sent commands were answered from the ledger's stored replies.

### 7. A claim that skipped a locked row stranded its saga for 30 s

- **Symptom:** the 30-minute soak passed its SLOs (p95), but saga p99 was 30 s, and 98 of the
  ~5,000 polled payments took longer than 15 s.
- **Evidence:** 5,805 of the soak's 215,365 payment sagas (2.7%) took 31 s on average. Every
  one of them waited 30 s between being created and its first authorize; after that the steps
  took milliseconds. 30 s is the reservation lease.
- **Cause:** the claim (`lockReserved`) used `FOR UPDATE SKIP LOCKED`. When another replica's
  reservation query held the row's lock for a moment just as the worker claimed it, the claim
  found nothing and returned. The saga stayed reserved, and unrun, until the lease expired.
  - The assumption behind `SKIP LOCKED` was "whoever holds the lock is changing the saga".
  - But a scan that merely locks the row changes nothing.
- **Fix:** the claim waits for the lock (`FOR UPDATE`). The token match still decides: if the
  holder did change the saga, the reservation is gone and the claim returns nothing. There is no
  deadlock risk: the claim locks one row, and the reservation query never waits.
- **Test:** `aClaimWaitsForAMomentaryLockInsteadOfSkippingTheSaga` holds the row lock in
  another transaction for 300 ms while claiming. It fails with `SKIP LOCKED` and passes with the
  fix.

### 8. An ordering guard that got slower as the backlog grew

- **Symptom:** after hundreds of thousands of payments, 200/s failed where it had passed
  earlier in the day. payment-service's outbox backed up to 1,277 rows and the ledger's lag to
  3,089.
- **Evidence:** `EXPLAIN (ANALYZE, BUFFERS)` of the relay's claim on the live database. Finding
  84 claimable events read **46,640 pages**:
  - Postgres had turned the per-aggregate ordering guard (`NOT EXISTS` an earlier unpublished
    event) into a nested-loop anti-join.
  - The aggregate match was a join *filter*, not an index condition.
  - So for every candidate it scanned the whole `(aggregate_id, seq)` index, dead entries
    included.
  - It chose that plan because, with ~700 pending rows among 3 million, it estimated 1 row on
    each side.

  The cost was candidates × index size, so claims slowed down exactly when the backlog grew,
  and the backlog grew because claims slowed down.
- **Fix:** the same rule, written as "this event's `seq` is the lowest unpublished one of its
  aggregate" (`e.seq = (SELECT min(seq) … WHERE aggregate_id = e.aggregate_id …)`). Postgres
  runs it per candidate as a one-entry index-only lookup. The same claim read **1,436 pages**
  (about 4 per candidate). Applied to all three relays (payment, ledger, payout).
- **Test:** `OutboxClaimQueryTest` (Postgres in Testcontainers) pins the rule: only each
  aggregate's earliest unpublished, due event is claimable, including behind FAILED and
  backing-off events. It passed on the old query and passes unchanged on the new one.

### 9. Queue tables vacuumed too rarely

- **Symptom:** the 30-minute soak at 140/s met every latency SLO, but the oldest pending outbox
  event's age crept from 1 s to 7 s during the last 10 minutes, breaking the 5 s SLO.
- **Evidence:**
  - payment-service's outbox had 818,064 dead rows, and autovacuum hadn't run for 30 minutes.
  - The pending-events index was 11 MB while holding no pending events at all. An empty claim
    read 570 pages of dead entries; under load, more.
  - The saga table was the same: 254,453 dead rows, and a 13 MB index of due sagas.
- **Cause:** both tables work as queues, where every row is updated as it moves along.
  Autovacuum's default trigger is 20% of the table dead: hundreds of thousands of rows on a
  table of millions. Until then, every claim walks the dead entries.
- **Fix:** Flyway migrations set these tables to vacuum after 50,000 dead rows, whatever their
  size (`autovacuum_vacuum_scale_factor = 0`). They cover payment-service's outbox and saga
  table, the ledger's outbox, and payout-service's outbox and saga table.
- **Result:** in the next full 30-minute soak the outbox held about 35,000 dead rows instead of
  818,000, and the oldest pending event never waited more than 4 s.

### A harness bug: soaks that carried less load than they said

A soak's requests by name showed 215,265 payments created for 252,001 iterations, plus 36,411
token requests answered 429.
- **Cause:** every virtual user (VU) kept its own token for every merchant, and tokens last
  15 minutes.
  - That is hundreds of VUs × 50 merchants: about 14,000 tokens to renew per cycle.
  - The gateway allows 20 unauthenticated requests/s per IP, token requests included, and the
    whole generator is one IP.
  - So renewal ran pinned at the limit for the rest of the run. Each iteration whose token
    expired first threw before creating its payment.
- **Why it was missed:** a thrown iteration only shows in the log. None of the thresholds saw
  it, so the first soaks ran at about 120/s effective instead of 140/s.
- **Fix:**
  - Each VU now acts as one merchant, so a cycle needs a few hundred tokens. k6 hands
    iterations to its VUs in turn, so merchants still get an even share.
  - Each VU renews at its own age between 8 and 12 minutes, and keeps its current token if a
    renewal fails.
  - A new `iteration_errors` counter has a threshold of 0, so this can't go unnoticed again.

Only runs longer than about 10 minutes were affected (the soaks). The 3-minute steps and the
spike use the tokens from `setup()` and were not.

### Cold JVMs: a measurement trap

Right after a restart, or when the HPA adds a pod during a run, the JIT compiler is still
compiling. JFR showed C2 compiler threads at 16% of CPU.

- **Evidence:** at 150/s with freshly started pods, create p95 was 460 ms and saga p95 27 s.
  The same rate a few minutes later passed with p95 36 ms and 2.1 s.
- **Again in a soak:** one soak began with payment-service scaled down to 2 pods after an idle
  period. The HPA added two cold pods as the load arrived, and saga p95 was 13–15 s for the
  first 3.5 minutes, then 0.35–1.0 s for the remaining 26.
- **Consequence:**
  - `load-ceiling.sh` warms up for 2 minutes at half the first rate before judging anything.
    Half, because cold pods at the full rate build a backlog that the first judged step would
    inherit.
  - A soak is started only after a run at its own rate has brought the autoscaler to its
    steady replica count.
- **Production lesson:** a scale-out under load brings in pods that are slower than average
  for a minute or two. CPU-based autoscaling should scale out early, not at the edge.

## Soak and spike

### Soak: 140/s (70% of the ceiling) for 30 minutes

`scripts/load-test.sh soak 140 30m`, on the final code, after a run at 150/s had brought the
autoscaler to 4 warm payment-service pods. Every SLO held.

| | Result |
|---|---|
| Payments created | 252,001, one per iteration; 298 HTTP requests/s |
| Errors | 0 failed requests, 0 dropped iterations, 0 iteration errors, no 429s |
| Create p95 / p99 | 44 / 68 ms |
| Read p99 (payment / balance) | 19 / 54 ms |
| Payment → SUCCESS (client) p50 / p95 / p99 / max | 0.53 / 0.57 / 1.07 / 2.14 s |
| Saga p95 / p99 (server) | 0.44 / 0.67 s |
| Saga p95 per 5 minutes | 0.49, 0.42, 0.42, 0.43, 0.42, 0.43, 0.55 s: flat |
| Oldest pending outbox event | 4 s at most (SLO 5 s) |
| Max consumer lag, drained after | 195, 10 s |
| payment-service heap | 117–161 MB throughout: no growth |
| payment-service CPU (4 pods), node CPU | 1.6 cores, 70% |

Earlier soaks are how fixes 7 and 9 and the harness bug were found. They either ran below the
stated rate (the token bug) or failed an SLO for the reason that fix describes.

### Spike: 60/s, then 400/s (twice the ceiling) for 30 s

`scripts/load-test.sh spike 60 400`: 1 minute at 60/s, 30 s at 400/s, then 3 minutes back at
60/s. The whole-run SLOs are expected to break, since the peak is twice what the system can
sustain. What matters is what fails and how fast it recovers.

| | First spike (before fixes 7–8) | Final spike |
|---|---|---|
| Responses | 68,238: 356 × 429, no 5xx | 69,281: 260 × 429, no 5xx |
| Ledger + orchestrator lag, peak | 6,806 | 65 |
| Saga p95 back to normal (0.3 s) | ~2.5 min after the peak | ~85 s after the peak |
| Payments lost or stuck | 0 | 0 |

- **The only errors are the gateway's rate limit.** At 400/s, 50 merchants each send about
  17 requests/s against a limit of 20/s (burst 40), so some bursts get 429s. That's the limit
  working as designed, not a failure of the services behind it.
- **During the peak** the generator couldn't start every iteration (193 dropped), and sagas
  admitted then took up to 30 s. Every one of them completed.

### Money after the load

After about 2.75 million payments in one day of load tests, including a broker failure, a
re-send storm and a hard shutdown of the machine in the middle of a soak:

| Check | Result |
|---|---|
| Processor captures = completed payments = ledger settlements | 2,750,997 = 2,750,997 = 2,750,997 (one settlement per payment) |
| Processor refunds = refunded sagas = ledger refunds | 25,446 = 25,446 = 25,446 |
| Ledger debits − credits, per currency | 0.0000 |
| Unbalanced ledger transactions | 0 |
| Sagas unfinished or needing an operator | 0 |

After the hard shutdown, the in-flight sagas finished without help once the cluster was back.


## The current limit

At 250/s on the final code, several things run out at once:
- create p95 reaches 161 ms and saga p95 30 s;
- payment-service has up to 13 requests waiting for a connection;
- notification and webhook lag pass 22,000;
- the node's CPU is 81% busy.

Sampling `pg_stat_activity` during a 200/s run showed where the database's time goes:

| Waiting on | Active sessions (avg of 30 samples) |
|---|---|
| `LWLock:WALWrite` (commits queued behind the WAL flush) | ~17 of ~28 |
| `IO:DataFileRead` | ~3.8 |
| `Client:ClientRead` | ~3.3 |
| Running on CPU | ~2.5 |

- **The shared instance:** one Postgres serves all 8 databases on a virtual disk. Every commit
  of every service waits for the same WAL flush, and the Postgres pod used 2.7 cores.
- **The processor simulator:** a test double with its own database, it adds about half as
  many commits as payment-service itself.
- **Why it isn't "fixed" here:** going past this means either fewer commits per payment, or
  more WAL capacity: a database per service, faster storage, or tuning `wal_buffers` and
  `commit_delay`. Turning off `synchronous_commit` is not an option for a ledger: acknowledged
  postings could be lost in a crash.
- **In production:** the per-service databases this design already assumes would split this
  limit across instances.

## Running it

```bash
# against the local cluster (scripts/k8s-up.sh), from the repo root
scripts/load-test.sh smoke                 # 5 payments/s for 1 minute
scripts/load-test.sh step 150 3m           # one steady run
scripts/load-ceiling.sh 100 150 200 250    # warm-up, then steps until an SLO fails
scripts/load-test.sh soak 140 30m          # 70% of the ceiling for 30 minutes (after a step run)
scripts/load-test.sh spike 60 400          # 60/s base, 400/s for 30 s, 3 minutes of recovery
```

Each run writes `load-tests/results/<run>.json`: the k6 summary and the server-side metrics.
The k6 log goes next to it and is ignored by git. Watch a run on Grafana's "PayFlow Load Test"
dashboard at http://localhost:3000.

## File map

| Path | What it is |
|---|---|
| `load-tests/k6/payflow.js` | k6 scenarios and SLO thresholds |
| `load-tests/collect.py` | Server-side metrics for a run, verdict, result JSON |
| `load-tests/results/` | One JSON per run (the numbers in this document), indexed by stage in its `README.md` |
| `k8s/load/k6-job.yaml` | The in-cluster k6 Job (not part of the overlays) |
| `scripts/load-test.sh`, `scripts/load-ceiling.sh` | Run one scenario; search for the ceiling |
| `infrastructure/docker/grafana/dashboards/payflow-load-test.json` | The Grafana dashboard |
| `payment-service/.../saga/SagaReservations.java` | Fix 1 |
| `PaymentSagaRepository.lockReserved` | Fix 7 |
| `*/outbox/OutboxEventRepository.java` (`claimPublishable`) | Fix 8 |
| `*/db/migration/*__autovacuum_for_queue_tables.sql` | Fix 9 |
| `*/outbox/OutboxRelay.java`, `OutboxPublisher.java` | Fix 3 |
| `ProcessorClient.java`, `*/client/ApiClientsConfig.java` | Fix 4 |
| `*/config/KafkaConsumerConfig.java`, `payflow.kafka.*` in `application.yaml` | Fix 5 (and the ledger's 6 listener threads) |
| `SagaOrchestrator.java` (`runReservedStep`), poll intervals in `application.yaml` | Fix 2 |
| `k8s/base/infra/kafka.yaml` | Fix 6 (Kafka 4.2.0) |
