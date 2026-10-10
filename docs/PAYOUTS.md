# Merchant Payouts

Merchants' money enters their ledger balance when payments settle. **payout-service** pays it
out to their bank account: every day in a batch, or on demand (instant payouts). Each payout is
a saga across the ledger and the bank:
- the ledger holds the amount;
- the bank transfers it;
- the ledger finalizes the payout, or releases the hold if the bank refuses.

A transfer that was paid can still come back days later: the merchant's bank returns it (account
frozen, closed after the fact). The saga keeps watching paid payouts for a while and books a
return back to the merchant's balance.

```
04:00 UTC ─▶ payout-service (one replica, ShedLock)
              GET ledger /payable-balances ─▶ one payout per merchant and currency
                                │
        merchant ─ POST /api/v1/payouts (Idempotency-Key) ─┐
                                ▼                          ▼
   HOLDING ──▶ SUBMITTING ──▶ IN_TRANSIT ──▶ FINALIZING ──▶ RETURN_WINDOW ──▶ COMPLETED
   (ledger)    (bank POST)    (bank GET)     (ledger)        (bank GET)
      │             │              │                              │
      │ no funds    │ declined     │ failed                       │ returned
      ▼             ▼              ▼                              ▼
    FAILED     RELEASING ─────────────────▶ FAILED            RETURNING ──▶ RETURNED
               (ledger)                                        (ledger)
```

## Contents

1. [Money model](#money-model)
2. [Funds availability](#funds-availability)
3. [The payout saga](#the-payout-saga)
4. [The bank](#the-bank)
5. [Batch and instant payouts](#batch-and-instant-payouts)
6. [API](#api)
7. [Webhooks](#webhooks)
8. [Reconciliation](#reconciliation)
9. [Metrics and alerts](#metrics-and-alerts)
10. [Runbook](#runbook)
11. [Configuration](#configuration)
12. [Design decisions](#design-decisions)
13. [Testing](#testing)

---

## Money model

Two new ledger accounts:
- **`MERCHANT_PAYOUT_RESERVE`**, per merchant and currency: payouts in progress, like the refund
  reserve;
- **`PAYOUT_CLEARING`**, platform-wide: money sent to merchants' banks.

| Command (from payout-service) | Posting (debit → credit) | Rule |
|---|---|---|
| `HOLD_PAYOUT` | `MERCHANT` → `MERCHANT_PAYOUT_RESERVE` | The **payable** balance covers it (checked under the merchant account's row lock, the same one refund holds take). A payout is held at most once, whatever the command id |
| `RELEASE_PAYOUT` | reserve → `MERCHANT` | This saga's hold, still open, same amount |
| `FINALIZE_PAYOUT` | reserve → `PAYOUT_CLEARING` | This saga's hold, still open, same amount |
| `RETURN_PAYOUT` | `PAYOUT_CLEARING` → `MERCHANT` | This saga's payout, same amount, not returned before |

A posting belongs to a payment or to a payout (a database CHECK enforces exactly one). Payout
commands travel on the same `ledger-commands` topic as payment commands. The ledger routes each
reply by command type: payout replies go to `payout-ledger-replies`, so each orchestrator only
receives its own.

## Funds availability

Only money that settled at least `payflow.payouts.delay` ago can be paid out. That's 2 days by
default, like a "T+2" payout schedule:

```
payable = balance − settlements credited since (now − delay)
```

Recent settlements stay in the balance, where refunds can still draw on them. Without the delay,
a merchant paid out every night would have nothing left to refund the next morning.
Everything else that came back stays payable: released holds and returned payouts.

An earlier draft used `min(balance now, balance at the cutoff)`. That's wrong: yesterday's payout
happened after today's cutoff, so the balance at the cutoff still contains money that was
already paid out.

## The payout saga

A pure state machine (`PayoutSagaStateMachine`, unit-tested transition by transition), run by
an orchestrator with the same mechanics as payment-service's:
- a step is claimed with `FOR UPDATE SKIP LOCKED` and a lease;
- bank calls run outside any transaction;
- an answer is applied only if the saga is still at that step and attempt;
- state changes, ledger commands and payout events commit together (outbox).

| State | Does | Then |
|---|---|---|
| `HOLDING` | Ledger `HOLD_PAYOUT` (with the payout's cutoff) | `SUBMITTING`; or `FAILED` (`insufficient_payable_balance`) with nothing to undo |
| `SUBMITTING` | `POST /v1/transfers`, Idempotency-Key `<sagaId>:transfer` | Accepted: `IN_TRANSIT`. Declined (402): `RELEASING`. No answer: retried with backoff under the same key; still unknown after 10 minutes: **parked** |
| `IN_TRANSIT` | `GET /v1/transfers/{id}`, hourly | `PAID`: `FINALIZING`. `FAILED`: `RELEASING`. Still pending after 3 days: **parked** |
| `FINALIZING` | Ledger `FINALIZE_PAYOUT` | Payout **PAID** (`PAYOUT_PAID`), then `RETURN_WINDOW` |
| `RETURN_WINDOW` | Checks the transfer hourly for 5 days | Still paid at the end: `COMPLETED`. `RETURNED`: `RETURNING` |
| `RETURNING` | Ledger `RETURN_PAYOUT` | Payout **RETURNED** (`PAYOUT_RETURNED`) |
| `RELEASING` | Ledger `RELEASE_PAYOUT` | Payout **FAILED** (`PAYOUT_FAILED`) with the bank's code |

**The pivot is `SUBMITTING`.** Until the bank may have the transfer, a failure is compensated by
releasing the hold. Once it may have it, money may be moving: an unknown outcome is never
released. The saga keeps the hold and stops for an operator (`REQUIRES_ATTENTION`), who
retries under the same key. That's the same rule as the payment saga's capture.

Ledger steps re-send an overdue command under the same command id, and the ledger handles it
once. A rejected ledger step parks the saga: the ledger and the orchestrator disagree, and that
must not be papered over.

Merchant-visible statuses: `PENDING` → `IN_TRANSIT` → `PAID` → (`RETURNED`), or `FAILED`.

## The bank

processor-simulator also takes payouts (real acquirers do), with the same conventions as card
payments: an API key, an Idempotency-Key on every POST, 402 for a decline, and outage injection.
Transfers are asynchronous: accepted as `PENDING`, then a scheduled clock (every replica, rows
claimed with `SKIP LOCKED`) settles or fails them, and sends returning ones back.

| Test account | Outcome |
|---|---|
| `ba_test_ok` | Paid after ~20s |
| `ba_test_invalid` (or any unknown account) | Declined at once: 402 `invalid_account` |
| `ba_test_closed` | Accepted, then `FAILED` `account_closed` |
| `ba_test_returned` | Paid, then `RETURNED` `account_frozen` 60s later |
| `ba_test_slow` | Stays in transit for 10 minutes |

Its settlement report (`GET /v1/reports/settlement`) lists transfers too: each one created, paid,
failed or returned in the period, with its reference `po_<id>`.

## Batch and instant payouts

- **Daily batch.** It runs at 04:00 UTC, with an hourly catch-up after that time for a missed day:
  1. Read the payable balances from the ledger (`GET /api/v1/ledger/payable-balances?cutoff&minimum`).
  2. Create one payout per merchant and currency of the whole payable balance. Only merchants
     with a payout destination get one; balances under the minimum (1.00) are skipped.

  A ShedLock row lets one replica at a time run it. A unique index (merchant, currency, day)
  makes it idempotent: a re-run only creates what is missing. Merchants without a destination
  are counted as skipped.
- **Instant payouts.** `POST /api/v1/payouts {amount, currency}` with an `Idempotency-Key`, `202`:
  - **Replays:** the same key with the same request returns the same payout (`200`,
    `Idempotent-Replayed: true`); with a different request it's `422`.
  - **Upfront checks:** the payable balance is checked first, so the merchant gets an
    immediate `422` rather than a failed payout. The ledger hold remains the authority, and it
    still runs.

## API

Through the gateway, `/api/v1/payouts/**`. Payout ids are `po_<uuid>`.

| | Scope | |
|---|---|---|
| `PUT /destination {bankAccount}` | `payouts:write` | Where payouts go: a bank account token (`ba_…`) |
| `GET /destination` | `payouts:read` or `payouts:write` | |
| `GET /balance?currency` | `payouts:read` | `{payable, cutoff}` |
| `POST /` | `payouts:write` | Instant payout (see above) |
| `GET /`, `GET /{id}` | `payouts:read` | The merchant's own payouts (another merchant's: 404); an operator (`payouts:operate`) sees all |
| `POST /batches`, `GET /batches` | `payouts:operate` | Run today's batch now (`409` while another run holds the lock); batch history |
| `GET /{id}/saga`, `POST /{id}/saga/retry` | `payouts:operate` | A payout's saga and step history; resume a parked saga |
| `POST /lookup {payoutIds}` | `payouts:audit` | For the reconciliation: statuses, and whether money may still be moving |

Merchant tokens carry `payouts:read` and `payouts:write` (computed when the token is issued, so
existing merchants have them too). payout-service has its own OAuth2 client (`ledger:admin`) to
read payable balances. The reconciliation client gained `payouts:audit`.

## Webhooks

`PAYOUT_CREATED`, `PAYOUT_PAID`, `PAYOUT_FAILED` and `PAYOUT_RETURNED` are published on
`payout-events` (outbox) and delivered by webhook-service. Delivery uses the same subscription,
HMAC signature, retries and dead-letter topic as payment webhooks. The payload is its own
contract, at version `1.0`:

```json
{"payloadVersion": "1.0", "eventId": "…", "eventType": "PAYOUT_RETURNED", "payoutId": "po_…",
 "merchantId": "…", "amount": 60.0000, "currency": "USD", "status": "RETURNED",
 "occurredAt": "2026-10-09T18:12:01Z", "failureCode": "account_frozen"}
```

A delivery is about a payment or a payout (database CHECK). A merchant can list a payout's
deliveries with `GET /api/v1/webhooks/deliveries/payout/{po_id}`.

## Reconciliation

The daily reconciliation covers payouts too ([RECONCILIATION.md](RECONCILIATION.md)). It
compares the bank's transfers (settlement report), the ledger's `PAYOUT` / `PAYOUT_RETURN`
postings, and payout-service's statuses (`POST /api/v1/payouts/lookup`):

| Type | Meaning |
|---|---|
| `PAID_NOT_BOOKED` / `BOOKED_NOT_PAID` | The bank paid and the ledger didn't book it, or the other way round |
| `PAYOUT_AMOUNT_MISMATCH` | Both exist; amounts or currencies differ |
| `RETURN_NOT_BOOKED` / `RETURN_BOOKED_NOT_RETURNED` | A return on one side only |
| `PAYOUT_STATUS_MISMATCH` | The payout says something else than the money (e.g. FAILED but paid) |
| `PAYOUT_HOLD_STALE` | A payout hold open longer than a transfer can take (4 days) |
| `UNKNOWN_PAYOUT`, `DUPLICATE` | Money for a payout nobody knows; two transfers or postings for one |

Some payouts are counted as pending, not as discrepancies:
- a payout whose saga is still working;
- a return younger than the grace period (2 hours), which the saga hasn't booked yet. It checks
  for returns hourly.

A payout in its return window is checked: everything is booked by then.

## Metrics and alerts

| Metric | |
|---|---|
| `payouts_status_transitions_total{status, trigger}` | Payouts reaching IN_TRANSIT, PAID, FAILED, RETURNED |
| `payout_batch_last_success_timestamp_seconds`, `payout_batch_latest_created`, `_skipped` | The latest batch (from the database: every replica reports the same) |
| `sagas_requires_attention`, `sagas_oldest_step_age_seconds{type="PAYOUT"}`, `outbox_*` | The same names as payment-service's, so `SagaRequiresAttention`, `SagaStepStuck` and the outbox alerts cover payouts with no new rules. Steps waiting on the bank by design (in transit, return window) are left out of the step age |
| `bank_calls_seconds{operation, outcome}` | Calls to the bank |

New alerts: `PayoutBatchNotRun` (no batch for 26 hours; silent before the first batch ever) and
`PayoutsFailing` (3 or more payouts failed or were returned within an hour). `ServiceDown`
covers payout-service.

## Runbook

**A parked payout (`SagaRequiresAttention`, application payout-service)**
1. `GET /api/v1/payouts/{po_id}/saga`: `stuckState` and `lastError` say where it stopped.
   - **`SUBMITTING`:** the bank never answered the transfer. Ask the bank whether a transfer
     with reference `po_<id>` exists.
   - **`IN_TRANSIT`:** the transfer is still pending at the bank after the in-transit timeout.
   - **A ledger step:** the ledger rejected a posting. Compare its postings:
     `GET /api/v1/ledger/payouts/{id}/transactions`.
2. Fix the cause, then `POST /api/v1/payouts/{po_id}/saga/retry`. The transfer is sent again
   under the same key, so the bank pays it at most once.

**`PayoutsFailing`**
1. `GET /api/v1/payouts` (operator) and look at `failureCode`:
   - **`invalid_account` / `account_closed`:** the merchant's details are wrong. Contact them
     and update their destination.
   - **Many returns at once:** a bank-side problem.
2. The money is already back in the merchants' balances; the next batch pays it again once the
   destination is fixed.

**`PayoutBatchNotRun`**
1. `GET /api/v1/payouts/batches`: a `FAILED` batch has its `error`. It's usually the ledger being
   unreachable for the payable balances.
2. Once it's back, the hourly catch-up retries; or `POST /api/v1/payouts/batches`.

## Configuration

`payflow.payouts.*`:

| Property | Default | Local overlay | |
|---|---|---|---|
| `delay` | `P2D` | `PT2M` | Funds availability |
| `minimum-amount` | `1.00` | | The batch skips smaller balances |
| `batch.cron` / `batch.time` / `batch.catch-up-interval` | `0 0 4 * * *` / `04:00` / `PT1H` | | |
| `saga.submit-timeout` | `10m` | | Unknown transfer outcome → parked |
| `saga.in-transit-timeout` | `3d` | `3m` | Still pending → parked |
| `saga.transit-poll-interval` | `1h` | `5s` | |
| `saga.return-window` / `saga.return-check-interval` | `5d` / `1h` | `3m` / `10s` | Under the reconciliation's return grace (2h) |
| `bank.base-url`, `bank.api-key`, `ledger.base-url` | localhost | | |

The bank's own timing (`processor.bank.*` in processor-simulator): settle 20s, return 60s, slow
10m.

## Design decisions

| Decision | Why | Trade-off |
|---|---|---|
| A separate payout-service, its own saga | Payouts are their own domain (schedules, destinations, bank rails); payment-service's saga tables are tied to payments | Saga machinery adapted, not shared, so it is copied |
| The ledger decides what is payable, under the account lock | One rule for refunds and payouts: they can't both spend one balance (tested concurrently) | The batch reads balances that may have changed by the time of the hold (the hold then fails; the next batch retries) |
| Pay only settlements older than the delay | Keeps a refund buffer, like real payout schedules | Merchants wait for their money |
| Pivot at the transfer; unknown outcomes park | A transfer can't be un-sent; releasing a hold for money that left would double-pay | An operator is needed when the bank stays silent |
| Watch paid payouts for returns | Bank rails return money days later; "paid" isn't final | Sagas stay active for the return window |
| Replies routed by command type | Each orchestrator consumes only its own replies | The ledger knows which orchestrator owns which command |
| Instant payouts with a payable pre-check | The merchant gets `422` immediately instead of a failed payout | Two reads of the balance; the hold still decides |

## Testing

**Automated**
- **State machine (21 unit tests):**
  - every transition;
  - the pivot (unknown outcomes are retried, then parked, never released);
  - polling and in-transit timeout;
  - returns before and after the window;
  - inputs a step can't handle.
- **payout-service integration (15, Postgres and Kafka in Testcontainers, a stub ledger on Kafka,
  a stub bank and ledger API over HTTP):**
  - an instant payout end to end, with its commands, transfer key and events;
  - key replay and key reuse;
  - no destination, or more than the payable balance;
  - a declined transfer and a failed one, both released;
  - a returned one, booked back;
  - a ledger refusing the hold;
  - a lost transfer answer, retried under the same key, paid once;
  - a bank outage parks the saga, and an operator retry pays it;
  - a transfer stuck in transit;
  - two concurrent batch runs create one payout per merchant;
  - the outcome counters exist from startup (see below);
  - API ownership, audit lookup and destination validation.
- **Ledger:**
  - payable cutoff;
  - hold / finalize / return / release guards;
  - a hold that is idempotent across command ids;
  - a refund and a payout racing for one balance;
  - the payable listing;
  - reply routing.
- **Bank:**
  - idempotency (8 concurrent duplicates → 1 transfer);
  - every test account;
  - outage;
  - the report.
- **Webhooks:** the payout payload and parser.
- **Reconciliation:** 14 payout rules and an integration test.

**On the cluster** (local overlay, demo timings; one run through the gateway, direct database
access only to read or to inject corruption):

| Check | Result |
|---|---|
| Funds availability | Right after the payments settled: payable 0, an instant payout refused (`422 insufficient_payable_balance`). After the 2-minute delay: payable 60.00 |
| Instant payouts | 25.00 accepted (`202`) and paid in ~20s; the same key returned the same payout (`200`); the same key with 26.00 got `422`; 36.00 against the remaining 35.00 got `422` |
| A refund and a payout racing for one 50.00 balance | The refund won, the payout failed (`insufficient_payable_balance`); balance 0, never negative |
| Batch triggered twice at once | One run (`200`), the other `409`; 6 payouts of the whole payable balance (60.00 each for the test merchants), 26 merchants without a destination skipped; a re-run created nothing |
| `ba_test_ok` | PAID in ~22s; saga COMPLETED when the 3-minute return window closed |
| `ba_test_closed` | FAILED `account_closed`, hold released (balance back to 60.00) |
| `ba_test_invalid` | FAILED `invalid_account` at once, no transfer, hold released |
| `ba_test_returned` | PAID, then RETURNED `account_frozen` ~60s later, booked back (balance 60.00) |
| `ba_test_slow` | Parked after the 3-minute in-transit timeout; `SagaRequiresAttention` fired for payout-service; once the bank paid it, an operator retry finished it (PAID) |
| Bank outage (45s) and a payout-service pod force-killed mid-saga | The transfer was retried under one key (6 unknown attempts), then paid: exactly 1 transfer at the bank |
| Ledger | Every merchant balance as expected (0 when paid out, 60.00 after a failure or return), no payout hold left open, debits = credits (5,440.0000 each) |
| Webhooks | PAYOUT_CREATED and the final event(s) for every outcome (PAID, FAILED, PAID + RETURNED), every HMAC signature valid |
| Reconciliation of the day | 7 payouts checked, 7 matched, 0 discrepancies (and 951 payments, all matched). The bank's amount for one paid transfer raised by 1.00: exactly one `PAYOUT_AMOUNT_MISMATCH` for that payout; fixed and re-run: 0 |

One real bug was found by this run. `PayoutsFailing` didn't fire, although three payouts had
failed or been returned. Each outcome counter series was created on its first increment, so
Prometheus first saw it at 1, and `increase()` counts nothing for a series that starts at its
first event. The counters are now registered at 0 on startup; `ReconciliationRunFailed` had
the same flaw. The promtool tests couldn't catch it, because their test series start at 0. A
test now checks that the counters exist from startup, and both alerts were verified live after
the fix.
