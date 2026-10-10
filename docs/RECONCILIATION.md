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
              ├─ GET  processor  /v1/reports/settlement?from&to     (API key)
              ├─ GET  ledger     /api/v1/ledger/transactions        (OAuth2: its own client)
              ├─ GET  ledger     /api/v1/ledger/refund-holds/open
              └─ POST payment    /api/v1/payments/lookup
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
6. [Operator API](#operator-api)
7. [Metrics and alerts](#metrics-and-alerts)
8. [Runbook](#runbook)
9. [Configuration](#configuration)
10. [Design decisions](#design-decisions)
11. [Payouts](#payouts)
12. [Testing](#testing)

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
  table, database clock) lets one replica at a time run them.
- **One run per day:** the scheduler never re-runs a day that has a completed run, and a partial
  unique index allows only one *running* run per day, even between a scheduled and a manual run.
  Tested: two "replicas" running the catch-up at the same moment produce exactly one run per day.
- **Manual runs** through the API are always allowed. They're for re-checking a day after fixing
  something: a day's latest completed run is what the metrics and alerts reflect for that day.

A source that can't be read fails the run (status `FAILED`, error recorded). It never produces
a partial result, which would show up as false discrepancies.

## Reading the sources

The job never reads another service's database; everything goes through APIs, as with a real
processor:

| Source | Endpoint | Auth |
|---|---|---|
| Processor settlement report | `GET /v1/reports/settlement?from&to`: every authorization created, captured or voided in the period (with `captured_at` / `voided_at`), and every refund | `X-Api-Key` |
| Ledger transactions | `GET /api/v1/ledger/transactions?from&to&page&size`: pages of up to 1000 | scope `ledger:admin` |
| Open refund holds | `GET /api/v1/ledger/refund-holds/open?createdBefore` | scope `ledger:admin` |
| Payment statuses | `POST /api/v1/payments/lookup {paymentIds}`: batches of up to 1000, including whether a saga is still running | scope `payments:audit` (new, read-only) |

The service has its own OAuth2 client, `reconciliation-service`, registered by merchant-service
from configuration (`payflow.auth.service-clients`). It holds only `ledger:admin` and
`payments:audit`, both read-only; service clients can never be given merchant scopes. Tokens
are fetched and renewed automatically (Spring Security's client-credentials support).

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
| In-flight payments reported as pending | Avoids false alarms for payments mid-saga | A payment stuck all night is skipped here (it has its own alert) |
| Pre-processor payments out of scope, flagged per payment | They have no processor record by design; the flag comes from the data (no payment method), not a cut-over date to configure | They are not reconciled at all (their ledger postings still balance) |
| Alert on each recent day's latest run | A clean day reconciled later can't hide a bad one | Resolving needs a clean re-run of that day |

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
  and all three sources):**
  - a manual run finds the expected discrepancies;
  - the sources are called with the right credentials (client-credentials token, Bearer on the
    ledger and payment calls, API key on the processor, the matching window, both ledger pages);
  - metrics reflect the latest run, and a day keeps alerting until it is re-run clean, whatever
    days are reconciled after it;
  - a partial capture is compared by the amount actually captured;
  - payouts: a day with a paid and a returned-but-unbooked payout, reported by payout id;
  - the run counters exist from startup, so the first failed run alerts;
  - an unreadable source fails the run;
  - two concurrent "replicas" reconcile each finished day exactly once;
  - API security, future dates, and a 409 for a day already running.
- **Sources:**
  - the processor report (captures, voids, refunds, bounded periods);
  - ledger transaction pages and open holds;
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
