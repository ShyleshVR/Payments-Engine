# Daily Reconciliation

Every night, `reconciliation-service` checks the previous day's money three ways:

- **the card processor**: what money actually did (its settlement report);
- **the ledger**: what PayFlow booked;
- **payment-service**: what PayFlow told the merchant.

It reports every payment on which they disagree, and an alert fires. Payouts are checked the same
way, against the bank's transfers and payout-service ([Payouts](#payouts)).

The sagas are built so that the three never disagree: outboxes, idempotency keys, compensation.
Reconciliation is the independent check that this holds in production, where bugs, manual
interventions and partner errors happen. It is the control payment companies run every day,
because the processor's records, not your own, decide what customers were charged.

```
02:00 UTC ─▶ reconciliation-service (one replica, ShedLock)
              ├─ GET  processor  /v1/reports/settlement/…           (API key; a page at a time)
              └─ GET  ledger     /api/v1/ledger/transactions        (OAuth2: its own client; a page at a time)
              ▼
          staging table (the day's records)
              ▼  2000 payments at a time
              ├─ POST payment    /api/v1/payments/lookup
              ▼
          Reconciler (pure function) ─▶ reconciliation_run + reconciliation_discrepancy
              ▼
          metrics ─▶ Prometheus ─▶ ReconciliationDiscrepancies alert ─▶ Alertmanager
```

## Contents

1. [What is compared](#what-is-compared)
2. [Discrepancy types](#discrepancy-types)
3. [Matching window](#matching-window)
4. [Scheduling](#scheduling)
5. [Reading the sources](#reading-the-sources)
6. [Large days](#large-days)
7. [Operator API](#operator-api)
8. [Metrics and alerts](#metrics-and-alerts)
9. [Runbook](#runbook)
10. [Configuration](#configuration)
11. [Design decisions](#design-decisions)
12. [Payouts](#payouts)
13. [Testing](#testing)

---

## What is compared

For business day D (UTC), every money movement that happened on D is checked against its
counterparts:

| Fact on day D | Must have |
|---|---|
| Processor captured a payment | One ledger settlement of the same amount and currency |
| Ledger settled a payment | One processor capture |
| Processor refunded | Ledger refund postings with the same total |
| Ledger posted a refund | Processor refunds with the same total |
| Any of the above | A payment-service status that agrees: `SUCCESS` / `REFUNDED` only if money was captured / refunded; `FAILED` / `CANCELLED` only if nothing was captured and no authorization still holds funds |

Plus, every run: refund holds in the ledger that stayed open longer than an hour.

A payment whose saga is **still running** is skipped and counted as *pending*: its money movements
may legitimately be incomplete. A saga stuck for good raises its own alert
(`SagaRequiresAttention`).

Payments made **before the processor integration** (no payment method) are out of scope: they were
settled through the old operator endpoints and the processor has no record of them. Reconciliation
covers money from the cut-over on, as a real company does when it changes acquirer. payment-service
reports this per payment (`processorBacked`), so the rule lives with the data, not in a date.

## Discrepancy types

| Type | Meaning | Typical cause |
|---|---|---|
| `CAPTURED_NOT_SETTLED` | The customer was charged; PayFlow never booked it | Settlement lost or never sent |
| `SETTLED_NOT_CAPTURED` | PayFlow booked money it never received | Booking without a capture |
| `SETTLEMENT_AMOUNT_MISMATCH` | Both exist; amounts or currencies differ | Data corruption, a partial capture |
| `REFUNDED_NOT_BOOKED` | The processor refunded; the ledger doesn't show it | Refund finalization lost |
| `BOOKED_NOT_REFUNDED` | The ledger shows a refund the processor never made | Refund posted before or without the processor |
| `REFUND_AMOUNT_MISMATCH` | Refund totals differ | Partial or duplicate refund on one side |
| `STATUS_MISMATCH` | The payment's status contradicts the money (e.g. `FAILED` but captured) | A status set wrongly, or by hand |
| `AUTHORIZATION_NOT_RELEASED` | A failed or cancelled payment still holds the customer's funds | Void or reversal lost |
| `REFUND_HOLD_STALE` | A refund hold open over an hour | Refund saga stuck |
| `DUPLICATE` | More than one capture or settlement for one payment | Double processing |
| `UNKNOWN_PAYMENT` | Money moved for a payment id payment-service doesn't know | Foreign or deleted data |

## Matching window

Day D is `[00:00, 24:00)` UTC, but sources are read for `[D − 1h, D + 1 day + 1h]`. A capture at
23:59:59 whose settlement is booked at 00:00:01 therefore still matches. Each fact is checked on
the day it happened, so nothing is checked twice and nothing falls between two days. The
scheduler reconciles a day only once the margin after it has passed.

## Scheduling

- **Nightly, 02:00 UTC** (`payflow.reconciliation.cron`): reconciles the previous day.
- **Hourly catch-up:** reconciles any of the last 3 days without a completed run, covering a run
  missed while the service was down, or one that failed because a source was unavailable. It
  also runs a minute after startup.
- **One runner:** every replica schedules both jobs; a ShedLock row in Postgres (`shedlock`
  table, database clock) lets one replica at a time run them. The lock lasts 10 minutes at
  most, and a run renews it at every heartbeat, so it covers a run of any length and is free
  again within 10 minutes of a pod being killed.
- **One run per day:** the scheduler never re-runs a day that has a completed run, and a partial
  unique index allows only one *running* run per day, even between a scheduled and a manual run.
  Tested: two "replicas" running the catch-up at the same moment produce exactly one run per day.
- **Manual runs** through the API are always allowed. They're for re-checking a day after fixing
  something: a day's latest completed run is what the metrics and alerts reflect for that day.
- **A run that dies:** a run records a heartbeat while it works (`heartbeat_at`, every
  30 seconds or so). A run still `RUNNING` but silent for 5 minutes (`abandoned-after`) belonged to a
  pod that was killed: the next attempt at that day marks it `FAILED` ("Abandoned…"), deletes
  what it left behind and runs. A run that is still reporting keeps its day (`409`). If a run
  that was only stalled wakes up after being taken over, it notices at its next heartbeat and
  stops without writing a result.

A source that can't be read fails the run (status `FAILED`, error recorded). It never produces
a partial result, which would show up as false discrepancies.

## Reading the sources

The job never reads another service's database; everything goes through APIs, as with a real
processor:

| Source | Endpoint | Auth |
|---|---|---|
| Processor settlement report | `GET /v1/reports/settlement/authorizations?from&to&after&limit`: every authorization created, captured or voided in the period (with `captured_at` / `voided_at`), each once. `…/refunds` and `…/transfers` the same way. Pages of up to 1000 | `X-Api-Key` |
| Ledger transactions | `GET /api/v1/ledger/transactions?from&to&after&size`: pages of up to 1000 | scope `ledger:admin` |
| Open refund holds | `GET /api/v1/ledger/refund-holds/open?createdBefore` | scope `ledger:admin` |
| Payment statuses | `POST /api/v1/payments/lookup {paymentIds}`: batches of up to 1000, including whether a saga is still running | scope `payments:audit` (new, read-only) |

The paged endpoints answer `{"items": […], "next": "…"}`. `next` is sent back as `after` to get
the following page; `null` means the period is complete. The cursor is opaque to the caller. It
says where the previous page ended (the last row's time and id), so the next page is an index
range scan that starts there: every page costs the same, however far into the day it is. Page
numbers (`OFFSET`) would make each page slower than the one before. A cursor from another
period, or one that isn't ours, is refused with `400`.

The service has its own OAuth2 client, `reconciliation-service`, registered by merchant-service
from configuration (`payflow.auth.service-clients`). It holds only `ledger:admin` and
`payments:audit`, both read-only; service clients can never be given merchant scopes. Tokens
are fetched and renewed automatically (Spring Security's client-credentials support).

## Large days

A run never holds a day in memory, and neither do its sources. It used to, and that failed on
the first large day.

**What went wrong.** The load test left a day with 2,750,997 payments (2026-10-10). The
processor's report was a single request for the whole day. The processor loaded every
authorization into its 604 MB heap, ran out of memory and was killed. The run failed, the hourly
catch-up retried, and a processor pod died every hour. `ReconciliationRunFailed` and
`ReconciliationNotRun` fired, which is how it was found. Three more problems sat behind it:

- reconciliation-service would have held the whole day in its own heap too;
- the ledger's listing had no index on `created_at` and was paged by page number, so every
  page scanned more than the one before;
- a run whose pod died stayed `RUNNING` and blocked its day for ever.

**How a run works now**

1. **Stage.** The day's records (matching window included) are read from the processor and the
   ledger a page of 1000 at a time and written to the table `reconciliation_staging`: one row
   per record, the record as JSON, under the payment or payout it belongs to.
2. **Payments, in chunks.** The staged payment ids are walked in order, 2000 at a time. For
   each chunk, everything staged for those payments is loaded, the payments are looked up in
   payment-service, and the unchanged `Reconciler` runs on that chunk. Its counts are added to
   the run's and its discrepancies are stored.
3. **Payouts, in chunks,** the same way.
4. **Open holds** are checked once: they don't depend on the day's records.
5. The run completes with the summed counts, and its staged rows are deleted (also when it
   fails).

Every payment is in exactly one chunk, with all its records, so the chunks add up to the day.
That is why the chunks are by payment and not by time. An hour's slice would need the one-hour
margin on both sides, and a payment captured in one slice and refunded in another would be
counted twice.

The staging table is `UNLOGGED`: its rows are disposable, so they skip the write-ahead log.
After a Postgres crash it comes back empty, and the interrupted run is retried anyway.

**Measured on the cluster.** The real 2026-10-10, reconciled by the scheduled catch-up one
minute after the fix was deployed. Each pod has a 604 MB heap.

| | Before | After |
|---|---|---|
| Result | `FAILED` every hour, a processor pod OOM-killed each time | `COMPLETED`: 2,750,997 payments checked, 2,750,997 matched, 0 pending; 2 payouts matched; 0 discrepancies |
| Duration | – | 30 min 55 s: about 4 min to stage 5,578,343 records, 27 min for 1,376 chunks |
| Peak heap, processor-simulator | over 604 MB (killed) | 110 MB |
| Peak heap, reconciliation-service | – | 95 MB |
| Peak heap, ledger-service and payment-service | – | 89 MB and 92 MB |
| Pod restarts during the run | one per attempt | 0 |
| Staging table | – | 2.1 GB at its peak, 0 rows afterwards |
| A page of the processor report, deep in the day | – | about 5 ms, the same as the first page (an index range scan) |
| A page of the ledger listing | a scan and sort of 2.7M rows | an index scan on the new `(created_at, id)` index, no sort |

**A killed run, on the cluster.** A manual run of the same day was started and its pod was
force-deleted 33 seconds in, with 1.7 million rows staged.

| Step | Result |
|---|---|
| The pod is killed while staging | The run stays `RUNNING`; its last heartbeat was at 13:54:37 |
| A new attempt 20 seconds later | `409`: the run still counts as alive |
| A new attempt 5 min 15 s after the last heartbeat | The dead run is marked `FAILED` ("Abandoned…"), its 1,879,000 staged rows are deleted, and a new run starts |
| That run | `COMPLETED` in 30 min 27 s with the same result: 2,750,997 checked, 2,750,997 matched, 0 discrepancies |
| Small days afterwards (2026-10-08, -09, -11) | 954 payments and 9 payouts matched, 0 discrepancies, under 2 s each |
| Alerts | `ReconciliationRunFailed` and `ReconciliationNotRun` resolved after the first completed run |

One more problem showed up in these runs and was fixed. Right after the big run, a small day
took 8 to 19 seconds: two clean-up statements read the whole staging table, which was still
1.9 GB of deleted rows waiting for autovacuum. They now go through the indexes. Autovacuum
returned the file to 0 bytes three and a half minutes after the run.

**Limits**

- **The chunk phase is the slow part.** Staged rows lie in the order they arrived, and a chunk
  reads them by payment id, so each row is a random read from a 2.1 GB table in a Postgres
  with 1 GB of memory (about 0.75 s a chunk, plus 0.4 s for its two payment lookups). Fine
  for a nightly job. If it mattered: write the staged rows sorted by payment once, or give
  Postgres the memory to cache them.
- **A manual run answers when it ends.** For a day this size the gateway gives up after 30 s
  (`504`) while the run carries on; read it with `GET /api/v1/reconciliation/runs?date=…`.
- **After a pod is killed mid-run, the scheduler waits for its next tick.** The lock is free
  within 10 minutes and the day can be taken over after 5, but the catch-up runs hourly (and a
  minute after a pod starts, when the lock may still be held). A manual run can take the day
  over as soon as the 5 minutes have passed.
- **Open holds are still one list,** and the ledger's query for them looks at every hold it
  ever made: 1.5 s after the load test's 25,000 refunds. It runs once per run. To stay flat it
  would need an index over the holds that are still open.
- **Staged rows are deleted, not truncated** (another day's run may be using the table). The
  file shrinks when autovacuum next runs; two large runs back to back reuse the space.

## Operator API

Through the gateway, scope `reconciliation:admin` (granted to the operator client):

| | |
|---|---|
| `GET /api/v1/reconciliation/runs?date=2026-10-08` | That day's runs, newest first (without `date`: the 20 most recent) |
| `GET /api/v1/reconciliation/runs/{id}` | A run with its discrepancies |
| `POST /api/v1/reconciliation/runs {"date":"2026-10-08"}` | Reconcile a day now. `201` with the result; `409` if that day is being reconciled; `400` for a future day |

```json
{"run": {"businessDate": "2026-10-08", "trigger": "SCHEDULED", "status": "COMPLETED",
         "paymentsChecked": 412, "matched": 410, "pending": 0, "discrepancyCount": 2},
 "discrepancies": [
   {"type": "CAPTURED_NOT_SETTLED", "paymentId": "pay_…", "processorAmount": 40.00,
    "ledgerAmount": null, "paymentStatus": "SUCCESS", "detail": "Captured auth_… at …, no settlement in the ledger"}]}
```

## Metrics and alerts

| Metric | |
|---|---|
| `reconciliation_discrepancies{type}` | Discrepancies still standing: for each of the last 7 business days, those of that day's latest completed run |
| `reconciliation_latest_checked` / `_matched` / `_pending` | Counts of the most recent completed run |
| `reconciliation_last_success_timestamp_seconds` | When a run last completed |
| `reconciliation_runs_total{status, trigger}` | Runs |
| `reconciliation_duration_seconds` | How long runs take |

The gauges are read from the database, so every replica reports the same values whichever
replica ran the job. The discrepancy gauge is per day on purpose: if it showed only the most
recent run, reconciling a clean day after a bad one (the catch-up runs several days in a row)
would silence the alert while the bad day was still wrong. A day stops alerting only when it is
re-run clean, or after 7 days, by which time it has been paged on for a week.

| Alert | When |
|---|---|
| `ReconciliationDiscrepancies` (critical) | Any recent day's latest run has a discrepancy |
| `ReconciliationNotRun` (warning) | No completed run for 26 hours |
| `ReconciliationRunFailed` (warning) | A run failed in the last hour |

## Runbook

**ReconciliationDiscrepancies**
1. List the latest run: `GET /api/v1/reconciliation/runs`, then `GET …/runs/{id}` for its
   discrepancies.
2. For each payment, look at its saga (`GET /api/v1/payments/{id}/saga`), its ledger postings
   (`GET /api/v1/ledger/payments/{id}/transactions`) and the processor's record.
3. Fix the side that is wrong. Typical fixes:
   - **`CAPTURED_NOT_SETTLED`:** resume the saga, or post the settlement.
   - **`AUTHORIZATION_NOT_RELEASED`:** void the authorization at the processor.
   - **`STATUS_MISMATCH`:** correct the status through the saga.
4. Re-run the day (`POST /api/v1/reconciliation/runs`). The alert clears when every recent day's
   latest run is clean.

**ReconciliationNotRun / ReconciliationRunFailed**
1. Read the failed run's `error` (`GET /api/v1/reconciliation/runs?date=…`). It is usually a
   source that was unreachable: the processor, the ledger, payment-service, or merchant-service
   for tokens.
2. Check `ServiceDown`, `InstanceDown` and the reconciliation-service logs.
3. Once the source is back, the hourly catch-up retries automatically; or trigger a run manually.

## Configuration

`payflow.reconciliation.*`:

| Property | Default | |
|---|---|---|
| `cron` | `0 0 2 * * *` (UTC) | Nightly run of the previous day |
| `catch-up-days` / `catch-up-interval` | 3 / `PT1H` | Recent days without a completed run are retried |
| `matching-margin` | 1h | Window around the day; a day closes this long after midnight |
| `authorization-grace` | 1h | How long a failed payment's authorization may stay open |
| `refund-hold-stale-after` | 1h | How long a refund hold may stay open |
| `page-size` | 1000 | Records asked of the processor and the ledger per request (they cap it at 1000) |
| `chunk-size` | 2000 | Payments (or payouts) reconciled at a time: what a run holds in memory |
| `abandoned-after` | 5m | A `RUNNING` run silent for this long is taken over by the next attempt |
| `processor.base-url` / `api-key`, `ledger.base-url`, `payments.base-url` | localhost | Sources |

The OAuth2 client is `spring.security.oauth2.client.registration.payflow` (client id
`reconciliation-service`, secret `PAYFLOW_RECONCILIATION_CLIENT_SECRET`, token URI of
merchant-service).

## Design decisions

| Decision | Why | Trade-off |
|---|---|---|
| A separate long-running service | Its own database of runs, an API, scrapeable metrics, and a schedule that survives restarts | One more deployment (vs a CronJob: short-lived pods can't be scraped without a Pushgateway) |
| Read through APIs, never databases | Database-per-service holds for operations too, and it mirrors reconciling against a real processor's report | New read-only endpoints in three services |
| A pure reconciler | Every rule is unit-tested directly (17 tests) | — |
| Facts checked on the day they happened, matched within a window | No false alarms across midnight, nothing checked twice | A day is reconciled only after the margin has passed |
| Fail the run if a source is unreadable | A partial snapshot would look like discrepancies | A failed run must be retried (automatic, hourly) |
| ShedLock + idempotency per day + unique running run | Exactly one reconciliation per day across replicas | — |
| A short scheduler lock, renewed by the run's heartbeat | A lock sized for the longest run (hours) would outlive a killed pod by as long | The run and the lock are tied together by a callback |
| In-flight payments reported as pending | Avoids false alarms for payments mid-saga | A payment stuck all night is skipped here (it has its own alert) |
| Pre-processor payments out of scope, flagged per payment | They have no processor record by design; the flag comes from the data (no payment method), not a cut-over date to configure | They are not reconciled at all (their ledger postings still balance) |
| Alert on each recent day's latest run | A clean day reconciled later can't hide a bad one | Resolving needs a clean re-run of that day |
| Stage the day in a table, reconcile it in chunks of payments | Memory no longer grows with the day; the rules and their tests are unchanged | A run writes the day once more (an unlogged table, emptied after the run) |
| Cursor paging in the processor report and the ledger listing | Every page is one index range scan; a day of millions of rows is read at a constant rate | The old one-request report is gone: callers must follow cursors |
| A heartbeat on the run; a silent run is taken over | A killed pod no longer blocks its day for ever | A stalled (not dead) run can be ended wrongly; it then stops itself at its next heartbeat |

## Payouts

Payouts are reconciled in the same run, by `PayoutReconciler` (a pure function, 14 unit tests).
Its sources are:
- the bank's transfers, from the same settlement report;
- the ledger's `PAYOUT` and `PAYOUT_RETURN` postings;
- payout-service's statuses, via `POST /api/v1/payouts/lookup`, scope `payouts:audit` (the
  reconciliation client gained it).

| Type | Meaning |
|---|---|
| `PAID_NOT_BOOKED` | The bank paid a merchant; the ledger never booked the payout |
| `BOOKED_NOT_PAID` | The ledger booked a payout the bank never paid |
| `PAYOUT_AMOUNT_MISMATCH` | Both exist; amounts or currencies differ |
| `RETURN_NOT_BOOKED` | The bank sent a payout back; the ledger doesn't show it |
| `RETURN_BOOKED_NOT_RETURNED` | The ledger booked a return the bank never made |
| `PAYOUT_STATUS_MISMATCH` | The payout's status contradicts the money (expected: RETURNED if returned, PAID if paid, FAILED otherwise) |
| `PAYOUT_HOLD_STALE` | A payout hold open longer than a transfer can take (`payout-hold-stale-after`, 4 days) |
| `UNKNOWN_PAYOUT` | Money moved for a payout payout-service doesn't know |

Some payouts are pending, not discrepancies:
- a payout whose saga is still working (holding, in transit, finalizing, a return being booked);
- a return younger than `payout-return-grace` (2 hours), which the saga, checking hourly,
  hasn't booked yet.

A paid payout in its return window is checked: by then everything is booked. Runs report payout
counts separately (`payoutsChecked`, `payoutsMatched`, `payoutsPending`); discrepancies carry a
`payoutId` (`po_…`) instead of a `paymentId`. See [PAYOUTS.md](PAYOUTS.md).

## Testing

- **Reconciler (17 unit tests):**
  - every discrepancy type;
  - amounts that differ only in scale still match;
  - a capture at 23:59:59 matches a settlement at 00:00:01;
  - another day's facts are left alone;
  - in-flight payments are pending;
  - pre-processor payments are left out;
  - stale vs fresh holds.
- **Integration (10 tests, Postgres in Testcontainers, a stub HTTP server for the token endpoint
  and all the sources, serving real pages and cursors):**
  - a manual run finds the expected discrepancies;
  - the sources are called with the right credentials (client-credentials token, Bearer on the
    ledger and payment calls, API key on the processor, the matching window), and each page
    is asked for with the cursor the page before it gave;
  - metrics reflect the latest run, and a day keeps alerting until it is re-run clean, whatever
    days are reconciled after it;
  - a partial capture is compared by the amount actually captured;
  - payouts: a day with a paid and a returned-but-unbooked payout, reported by payout id;
  - the run counters exist from startup, so the first failed run alerts;
  - an unreadable source fails the run;
  - two concurrent "replicas" reconcile each finished day exactly once;
  - API security, future dates, and a 409 for a day already running.
- **Large days (8 tests, same setup, 3 records a page and 2 payments a chunk):**
  - a day of 28 payments and 5 payouts with 16 known discrepancies, read in 10 and 11 pages
    and reconciled in 14 chunks, gives exactly those counts and discrepancies; every payment
    is looked up in exactly one chunk;
  - a run that fails halfway keeps no staged rows and no discrepancies, and the day reconciles
    once the source is back;
  - a run silent for 10 minutes is taken over; one heard from a second ago keeps its day;
  - the scheduler's lock is pushed further out while a scheduled run works, and given back
    when it ends;
  - a run that is taken over while it works stops without writing a result;
  - staged records come back unchanged, in the source's order, for a range of payments or
    payouts; rows left by runs that are over are deleted, those of a run at work are kept.
- **Sources:**
  - the processor report: an authorization created, captured or voided in the period is
    listed exactly once across pages; rows with the same timestamp survive a page boundary;
    the page size is capped; a foreign cursor is refused; the unpaged report is gone;
  - ledger transaction pages (each transaction once, ties included) and open holds;
  - the payment lookup (scopes, the saga-running flag);
  - service clients in merchant-service (scopes limited, merchant scopes rejected, a real token
    issued).

### On the cluster

Run against the live deployment, through the gateway. The only direct database access was the
injected corruption itself:

| Check | Result |
|---|---|
| Scheduled catch-up after the first deploy, 2 replicas | 2026-10-06, -07 and -08 each reconciled exactly once (`SCHEDULED`); later restarts didn't re-run them |
| Day with pre-processor payments (2026-10-07) | First run: 157 checked, 109 legacy payments reported (`SETTLED_NOT_CAPTURED`, `STATUS_MISMATCH`), because they were settled before the processor existed. After scoping them out, the re-run checked 48 and matched 48, with 0 discrepancies |
| A bad day followed by clean days | The 2026-10-07 discrepancies kept the alert firing although 2026-10-08 was reconciled after it. Re-running the day resolved it, with resolved notifications in alert-sink |
| Clean day, mixed traffic | 8 captures, a refund, a declined refund, a manual capture, a cancellation and a declined card: 11 payments with money movement, 11 matched, 0 discrepancies, 0 pending (the declined card never reached an authorization) |
| Injected: settlement moved out of the window (ledger) | `CAPTURED_NOT_SETTLED` for that payment |
| Injected: captured amount +1.00 (processor) | `SETTLEMENT_AMOUNT_MISMATCH` ("Processor 13.0000 USD vs ledger 12.0000 USD") |
| Injected: a void undone, authorization 2h old (processor) | `AUTHORIZATION_NOT_RELEASED` |
| Alert delivery | Exactly those three types, nothing else; `ReconciliationDiscrepancies` fired for each within about 40s and reached alert-sink |
| Fixed and re-run | 0 discrepancies; the alerts resolved and the resolved notifications reached alert-sink within a minute |

Two bugs were found by these runs and fixed before the results above:
- **The alert followed only the most recently finished run.** The catch-up reconciles several
  days in a row, so a clean day finished after a bad one silenced the alert. Fix: the gauge
  sums each recent day's latest run.
- **Pre-processor payments** produced 109 false discrepancies. Fix: they are out of scope.

A review fix also landed: a captured authorization is now compared by its captured amount, not
the authorized amount, so a partial capture would show up (covered by an integration test).
