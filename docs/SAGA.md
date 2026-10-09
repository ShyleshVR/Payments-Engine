# Payment and Refund Sagas

A payment now involves three systems that each keep their own records: PayFlow's payment
service, an external **card processor** (authorize, capture, void, refund) and PayFlow's
**ledger**. No single database transaction can span them. Each payment and refund is therefore
an **orchestrated saga**: a sequence of local steps, each committed on its own. When a later step
fails, the earlier ones are undone by **compensating** steps. Before this change, an operator
moved a payment to "complete" by hand, and a refund was booked without checking anything, so
nothing could fail part-way and nothing ever needed undoing.

```
merchant ─▶ API gateway ─▶ payment-service ─── saga orchestrator (payment_saga table + SagaWorker)
                              │  HTTP, Idempotency-Key per step, circuit breaker
                              ├─────────────▶ processor-simulator   authorize · capture · reverse · refund
                              │  outbox ─▶ Kafka "ledger-commands" ─▶ ledger-service
                              │◀─ Kafka "ledger-replies" ◀─ ledger outbox (reply committed with the posting)
                              └─ outbox ─▶ "payment-created" events ─▶ notification-service, webhook-service
```

- **Orchestration, not choreography.** One component, payment-service's `SagaOrchestrator`,
  owns each saga's state machine. It decides every next step and sends commands to the
  participants. The flow, its timeouts and its compensation are in one place, and every saga's
  history can be queried.
- **The processor is called over HTTP.** It is an external system with idempotency keys, the
  way real payment processors work. The **ledger** is an internal participant and is driven
  asynchronously, by command and reply over Kafka, with a transactional outbox on both sides.

## Contents

1. [Payment saga](#payment-saga)
2. [Refund saga](#refund-saga)
3. [The pivot: when to compensate and when to retry](#the-pivot-when-to-compensate-and-when-to-retry)
4. [What happens when something fails](#what-happens-when-something-fails)
5. [Idempotency at every hop](#idempotency-at-every-hop)
6. [How sagas run](#how-sagas-run)
7. [The card processor (processor-simulator)](#the-card-processor-processor-simulator)
8. [Ledger: commands, holds and replies](#ledger-commands-holds-and-replies)
9. [API changes](#api-changes)
10. [Events and webhooks](#events-and-webhooks)
11. [Observability](#observability)
12. [Configuration](#configuration)
13. [Design decisions and trade-offs](#design-decisions-and-trade-offs)
14. [Testing](#testing)
15. [Code map](#code-map)

---

## Payment saga

```
                    ┌──declined──────────────────────────────────────────▶ FAILED
POST /payments ─▶ AUTHORIZING ──no answer by the deadline──▶ VOIDING ─▶ FAILED (processor_unavailable)
                    │ approved
                    ├── MANUAL ─▶ AWAITING_CAPTURE ──cancel / expiry──▶ VOIDING ─▶ CANCELLED
                    │                  │ POST /capture
                    ▼                  ▼
                 CAPTURING ──declined──▶ VOIDING ─▶ FAILED (decline code)
                    │ captured   ◀── pivot
                 SETTLING (ledger) ─▶ COMPLETED
```

| Step | Who | Action | Idempotency key |
|---|---|---|---|
| AUTHORIZING | processor | `POST /v1/authorizations`: reserve the amount on the card | `<sagaId>:authorize` |
| AWAITING_CAPTURE | merchant | MANUAL capture only: waits for `POST /payments/{id}/capture` or `/cancel`, or for the authorization to expire (7 days) | — |
| CAPTURING | processor | `POST /v1/authorizations/{id}/capture`: charge it | `<sagaId>:capture` |
| SETTLING | ledger | `SETTLE_PAYMENT`: debit platform clearing, credit the merchant | command id |
| VOIDING | processor | `POST /v1/reversals {originalIdempotencyKey: <sagaId>:authorize}`: release the reservation | `<sagaId>:reverse` |

The payment is created in the same transaction that starts its saga, so a payment never exists
without the saga that finishes it. `POST /payments` answers **201 `PROCESSING`** right away. The
outcome arrives by webhook (`PAYMENT_COMPLETED`, `PAYMENT_FAILED`, …) or by `GET`.

**Capture methods.** `AUTOMATIC` (the default) captures right after authorization. `MANUAL`
works like a card hold, for example while an order ships: the payment waits as `AUTHORIZED`
until the merchant captures or cancels it. If neither happens before
`payflow.saga.authorization-ttl`, the authorization is voided and the payment becomes
`CANCELLED` with `authorization_expired`.

## Refund saga

```
POST /refund ─▶ HOLDING (ledger) ──INSUFFICIENT_FUNDS──▶ REFUND_FAILED (payment stays SUCCESS)
                  │ held
               REFUNDING (processor) ──declined──▶ RELEASING (ledger) ─▶ REFUND_FAILED
                  │ refunded  ◀── pivot
               FINALIZING (ledger) ─▶ REFUNDED
```

| Step | Who | Action |
|---|---|---|
| HOLDING | ledger | `HOLD_REFUND`: move the amount from the merchant's available balance into their refund reserve, only if the balance covers it |
| REFUNDING | processor | `POST /v1/refunds` (key `<sagaId>:refund`) |
| FINALIZING | ledger | `FINALIZE_REFUND`: the reserved amount leaves through platform clearing |
| RELEASING | ledger | `RELEASE_HOLD` (compensation): the reserved amount returns to the merchant's balance |

The hold comes first because it is the step most likely to say no: a merchant can't refund money
it no longer has. If the processor then refuses the refund, the hold is released and the
merchant's balance is exactly what it was. A failed refund leaves the payment `SUCCESS` with a
`refundFailureCode`, and the merchant can try again later. Each attempt is a new saga. Only one
saga per payment can be active, which a partial unique index enforces.

## The pivot: when to compensate and when to retry

Each saga has a **pivot**: the step after which money has really moved. For a payment that is
the capture; for a refund, the processor's refund.

- **Before the pivot**, a failure is **compensated**. A declined capture or an authorization
  that never got an answer means releasing the authorization: the payment fails, and nothing
  was charged.
- **After the pivot**, steps are only **retried**, never compensated. A captured payment must
  reach the ledger; it can't be "un-captured" because the ledger is slow.
- **At the pivot itself**, an unknown outcome is never guessed. If a capture call keeps timing
  out, the money may or may not have moved. Voiding could fail to release an authorization that
  was in fact captured, and marking the payment failed could make the merchant ship nothing for
  a customer who was charged. So the saga keeps retrying with the same idempotency key. If there
  is still no answer by the step deadline (10 minutes), it **parks** in `REQUIRES_ATTENTION` for
  an operator.

## What happens when something fails

| Failure | What the saga does | Payment ends |
|---|---|---|
| Card declined at authorization | Nothing to undo | `FAILED` (`do_not_honor`, `insufficient_funds`) |
| Processor times out / 5xx while authorizing | Retries with the same key (1s, doubling, max 30s). The processor replays the original answer if it had processed the request | `SUCCESS` once an answer arrives |
| …still no answer after 2 minutes | Reverses the authorization by its request key, then fails the payment | `FAILED` (`processor_unavailable`) |
| Capture declined | Voids the authorization (compensation) | `FAILED` (`capture_declined`) |
| Capture outcome unknown for 10 minutes | Parks; never voids | `PROCESSING` until an operator acts |
| Ledger down / slow during settlement | Re-sends the same command (15s, doubling, max 5 min); the ledger handles it once | `SUCCESS` when the ledger is back |
| Merchant balance too low for a refund | The hold is rejected, so nothing was moved | `SUCCESS`, `refundFailureCode: insufficient_merchant_balance` |
| Processor refuses the refund | Releases the hold (compensation) | `SUCCESS`, `refundFailureCode: refund_declined` |
| Processor down (many calls failing) | The circuit breaker opens: calls fail locally and fast, and sagas back off instead of waiting out timeouts. Trial calls after 10s | Resumes when the processor is back |
| payment-service pod dies mid-step | Its lease (30s) expires, and another replica takes the step and repeats the call with the same key | Unchanged outcome |
| Ledger reply for a command the saga isn't waiting for | Ignored (a duplicate, or the reply to an earlier re-send) | — |
| A participant rejects something that should never be rejected | Parks with the reason | Unchanged until an operator retries |

An operator inspects a saga with `GET /api/v1/payments/{id}/saga`, which returns its state and
every step outcome. After fixing the cause, `POST /api/v1/payments/{id}/saga/retry` resumes the
parked step: processor steps reuse their idempotency key, and ledger steps send a new command.

## Idempotency at every hop

A message can be lost or delivered twice anywhere, so every hop is safe to repeat:

| Hop | Mechanism |
|---|---|
| Merchant → payment API | `Idempotency-Key` header per merchant (existing) |
| Saga step → processor | `Idempotency-Key = <sagaId>:<step>`. The processor stores the response per key and replays it; the same key with a different body gets 422 |
| Authorization that may or may not have arrived | Reversal by original key: voids it if it was processed, and blocks it if it arrives later |
| Payment-service → Kafka | Transactional outbox: a command or event is committed with the state change that caused it |
| Ledger command handling | `processed_commands` stores each reply; a repeated command posts nothing and gets the same reply again. A settlement is also unique per payment |
| Ledger → Kafka | The ledger's own outbox: the reply is committed with the posting |
| Ledger reply → saga | Applied only if it answers the command the saga is waiting for (`pending_command_id`) |
| Saga worker → saga | Claim with `FOR UPDATE SKIP LOCKED` plus a lease and an attempt counter. A stale result (lease expired, step taken over) is dropped |

## How sagas run

- **`SagaWorker`** runs on every replica and polls every 500ms for due sagas (`next_attempt_at`
  in the past). Each saga is handed to a pool of 8 threads, so a slow processor call holds one
  thread and nothing else. This is the same design as the webhook dispatcher.
- **Processor steps** run in three parts:
  1. **Claim** (short transaction): lock the saga with `SKIP LOCKED`, count the attempt, and
     push `next_attempt_at` forward as a 30s lease.
  2. **Call:** the HTTP call, outside any transaction.
  3. **Record** (short transaction): lock again and apply the result, only if the saga is still
     at that step and attempt.
- **Ledger steps** need no worker while all is well. The command goes into the outbox together
  with the state change, and the reply consumer advances the saga. The worker only acts when
  the reply is overdue, and then re-sends the command.
- **Merchant and operator actions** (capture, cancel, retry) lock the saga and apply the same
  state machine.
- **`PaymentSagaStateMachine`** is a pure function, (state, what happened) → decision. It does
  no I/O, so every rule above is unit-tested directly. The orchestrator applies each decision in
  one transaction: saga state, payment status, ledger command and payment event together.

## The card processor (processor-simulator)

A new internal service (port 8086, database `processor_db`, 2 replicas in Kubernetes) that
behaves like a card processor's API. It has no gateway route, and callers need `X-Api-Key`.

| Endpoint | |
|---|---|
| `POST /v1/authorizations` | `{paymentMethod, amount, currency, reference}` → 201 `AUTHORIZED`, or 402 with a decline code |
| `POST /v1/authorizations/{id}/capture` | 200 `CAPTURED`; 402 if declined (the authorization stays open); 409 if already captured or voided |
| `POST /v1/authorizations/{id}/void` | 200 `VOIDED` (voiding again also returns 200); 409 if captured |
| `POST /v1/refunds` | `{authorizationId, amount}` → 201; 402 if declined; 422 if more than captured minus refunded |
| `POST /v1/reversals` | `{originalIdempotencyKey}`: cancels an authorization request whatever happened to it (see below) |
| `GET /v1/operations/{key}` | Status inquiry: what happened to the request sent with this key (404: never received) |
| `POST /v1/test/outage?seconds=N` | Test hook: every call returns 503 for N seconds (shared by all replicas) |

Every POST requires an `Idempotency-Key`. The first request with a key holds a row lock on it
until its response is stored. A concurrent duplicate waits, then gets the same response, so
eight simultaneous identical requests create exactly one authorization (tested).

**Reversal by key** closes the gap left by an authorization whose answer was lost: the request
may still be in flight when the saga gives up. A reversal for a key that hasn't arrived yet
leaves a marker, and when the authorization does arrive it is rejected (409 `reversed`) instead
of reserving the customer's funds. Card networks support reversals for this reason.

**Test payment methods** produce every outcome on demand:

| Payment method | Behaviour |
|---|---|
| `pm_card_visa` | Every step succeeds |
| `pm_card_declined` / `pm_card_insufficient_funds` | Authorization declined (`do_not_honor` / `insufficient_funds`) |
| `pm_card_capture_fails` | Authorized, capture declined: exercises the void compensation |
| `pm_card_refund_fails` | Payment succeeds, refunds are declined: exercises the hold release |
| `pm_card_timeout_once` | The first authorization succeeds but answers after 8s, past the caller's 5s timeout: exercises the unknown-outcome retry |
| `pm_card_flaky` | Each operation's first 2 calls return 503 |

`processor.chaos.error-rate` and `processor.chaos.latency` add random failures and latency to
every request, for load tests.

## Ledger: commands, holds and replies

The ledger used to post from `PAYMENT_COMPLETED` / `PAYMENT_REFUNDED` events. It now posts
**only on saga commands** from the `ledger-commands` topic, and no longer consumes
`payment-created`.

| Command | Posting | Can be rejected |
|---|---|---|
| `SETTLE_PAYMENT` | debit `PLATFORM_CLEARING`, credit `MERCHANT` | no; at most once per payment, even under a new command id |
| `HOLD_REFUND` | debit `MERCHANT`, credit `MERCHANT_REFUND_RESERVE` | `INSUFFICIENT_FUNDS` |
| `RELEASE_HOLD` | debit `MERCHANT_REFUND_RESERVE`, credit `MERCHANT` | `NO_ACTIVE_HOLD` |
| `FINALIZE_REFUND` | debit `MERCHANT_REFUND_RESERVE`, credit `PLATFORM_CLEARING` | `NO_ACTIVE_HOLD` |

- **Holds are ledger entries.** A hold moves money between two of the merchant's accounts, so
  the books stay double-entry and every movement is auditable. The balance API now also returns
  `reserved`.
- **Race-safe balance check.** `HOLD_REFUND` locks the merchant's account row
  (`SELECT … FOR UPDATE`) before reading the balance, so two concurrent refunds can't both spend
  it. Tested: 8 concurrent holds of 30 against a balance of 100 → exactly 3 succeed. Without the
  lock, the test fails.
- **Release and finalize** require this saga's open hold, of the same amount. Anything else
  means the two services disagree; the command is rejected and the saga parks.
- **Replies** go through a new ledger outbox and relay, copied from payment-service's: the
  posting, the processed-command record and the reply commit together.

## API changes

| | Before | After |
|---|---|---|
| `POST /payments` | `{amount, currency, ...}` → 201 `CREATED` | + required `paymentMethod`, optional `captureMethod`; → 201 `PROCESSING`, saga started |
| `POST /payments/{id}/process`, `/complete`, `/fail` | operator drove the outcome | **removed**: the saga drives it |
| `POST /payments/{id}/capture` | — | merchant, MANUAL capture → 202 |
| `POST /payments/{id}/cancel` | `CREATED` → `CANCELLED` | releases an `AUTHORIZED` payment → 202, then `CANCELLED` |
| `POST /payments/{id}/refund` | `SUCCESS` → `REFUNDED` at once | → 202 `REFUND_PENDING`, then `REFUNDED` or back to `SUCCESS` with `refundFailureCode` |
| `GET /payments/{id}/saga`, `POST …/saga/retry` | — | operator (`payments:operate`) |

The response gains `captureMethod`, `failureCode` and `refundFailureCode`. Statuses are
`PROCESSING`, `AUTHORIZED`, `SUCCESS`, `FAILED`, `CANCELLED`, `REFUND_PENDING`, `REFUNDED`
(`CREATED` remains only on payments made before sagas). Payments made before sagas have no
processor authorization, so refunding them returns 409.

## Events and webhooks

New payment events: `PAYMENT_AUTHORIZED` (MANUAL capture), `PAYMENT_CANCELLED` and
`PAYMENT_REFUND_FAILED`. `PAYMENT_COMPLETED` is now published only once the ledger has booked
the payment. Failure events carry `failureCode`.

- **Webhooks** deliver all seven types. Payload version **1.1** adds `failureCode` (left out
  when null); the change is additive, so 1.0 parsers keep working.
- **Customer notifications** add `PAYMENT_CANCELLED`. `PAYMENT_AUTHORIZED` and
  `PAYMENT_REFUND_FAILED` are for the merchant only.
- Both consumers already skipped event types they didn't know, so services could be deployed
  in any order.

## Observability

| Metric | Meaning |
|---|---|
| `sagas.started` / `sagas.finished{type, state}` | Saga throughput and outcomes |
| `saga.duration{type, state}` | Start to finish, histogram (p50/p95 in Grafana) |
| `saga.steps{state, outcome}` | Every step outcome (APPROVED, DECLINED, UNKNOWN, TIMED_OUT, ...) |
| `sagas.requires_attention` | Gauge: sagas parked for an operator, the number to alert on |
| `sagas.parked{type, state}` | Where they got stuck |
| `saga.replies.ignored{reason}` | Stale or duplicate ledger replies |
| `processor.calls{operation, outcome}` | Processor latency and outcomes |
| `resilience4j.circuitbreaker.state{name="processor"}` | Circuit breaker |
| `ledger.commands{type, outcome, reason, duplicate}` | Ledger side |
| `processor.requests{operation, status, replayed}` | processor-simulator side (shows idempotent replays) |

The Grafana dashboard has a Sagas row with these panels.

**One trace per payment.** The traceparent of the request that created the payment is stored
on the saga and on every outbox row. It is continued by the worker (processor calls), by the
outbox publishers of both services, and through Kafka's own header propagation. In Jaeger, a
payment is one trace: API call → authorize → capture → `ledger-commands` → ledger posting →
`ledger-replies` → `PAYMENT_COMPLETED` → webhook and notification consumers. This resolves the
earlier limitation where the API call and the outbox publishing were separate traces.

## Configuration

payment-service, `payflow.saga.*` (`SagaProperties`):

| Property | Default | |
|---|---|---|
| `poll-interval` / `workers` / `max-in-flight` | 500ms / 8 / 100 | Worker |
| `lease` | 30s | Must outlast a processor call (connect + read timeout) |
| `authorization-ttl` | 7d | MANUAL authorizations expire |
| `step-timeouts.authorize` | 2m | Then reverse and fail |
| `step-timeouts.capture` / `refund` / `voiding` | 10m / 10m / 24h | Then park |
| `retry.initial-backoff` / `max-backoff` | 1s / 30s | Unknown processor outcomes, ±20% jitter |
| `replies.timeout` / `max-timeout` | 15s / 5m | Ledger command re-send |

`payflow.processor.*`: `base-url`, `api-key` (env `PAYFLOW_PROCESSOR_API_KEY`), `connect-timeout`
1s, `read-timeout` 5s. The circuit breaker (`resilience4j.circuitbreaker.instances.processor`)
counts only UNKNOWN outcomes (declines are answers, not failures). It opens at 50% failures over
the last 20 calls (with at least 10 calls), and tries again after 10s with 3 trial calls.

## Design decisions and trade-offs

| Decision | Why | Trade-off |
|---|---|---|
| Orchestration in payment-service | Payment status and saga state change in one transaction; no extra service or hop | payment-service grows; another service's flows would need their own orchestrator |
| State machine as a pure function | Every rule is unit-tested without infrastructure; the orchestrator only applies decisions | Two places to read: the rules and how they are applied |
| Processor over HTTP, ledger over Kafka | Matches reality: external PSPs are HTTP with idempotency keys; internal participants decouple through messaging | Two styles of step, each with its own timeout handling |
| Hold before refund | The likeliest "no" (balance) comes first and needs no compensation | Refunds take one extra round trip |
| Park instead of guess at the pivot | Never charges without booking, never voids a capture | Needs an operator; `sagas.requires_attention` must be alerted on |
| Payment saga voids by reversal-by-key | Works whether or not the authorization's outcome is known, and blocks a late arrival | Relies on the processor supporting reversals (card networks do) |
| Polling worker (500ms) and outbox (500ms) | Simple, durable, works across replicas | About a second of added latency per async step |
| A failed refund leaves the payment `SUCCESS` | The money did not move; the merchant can try again | Clients must check `refundFailureCode` |
| Breaking API change (process/complete/fail removed) | They contradict an automated flow | Clients of the old operator endpoints must change |

## Testing

**Automated**

| Module | Tests | Covers |
|---|---|---|
| processor-simulator | 15 | Replay per key; key reuse → 422; 8 concurrent duplicates → one authorization; each test payment method; capture/void/refund rules; reversal before and after the authorization; status inquiry; outage; API key |
| ledger-service | 26 | Settlement, duplicate command → one posting and the same reply, settlement once per payment, insufficient balance, concurrent holds (row lock, checked by mutation), release/finalize rules, debits = credits |
| payment-service | 77 | 29 state machine tests (every transition, compensation path and timeout, including "capture outcome unknown is never voided"); 15 Testcontainers integration tests (Postgres, Kafka, Redis, stub processor over HTTP, stub ledger over Kafka); API and security tests |
| webhook-service / notification-service | 124 / 34 | New event types, `failureCode` in payload 1.1 |

The integration tests run the real orchestrator against:
- automatic and manual payments;
- cancel and expiry;
- a declined authorization and a declined capture (void);
- a lost answer recovered with the same key;
- a processor outage past the authorization deadline (circuit opens, reversal, failure, recovery);
- an overdue ledger reply (same command id re-sent);
- stale replies ignored;
- a refund that succeeds, one with insufficient balance (then retried successfully), and one refused by the processor (hold released);
- a concurrent second refund (409);
- a parked settlement completed by an operator retry.

Two real bugs were found by these tests before deployment:
- a `KafkaTemplate` bean that stopped Spring Boot from creating the main one, so payment-service would not have started;
- Postgres 17 rejecting the test JVM's timezone name.

**On the cluster:** see [Cluster verification](#cluster-verification).

### Cluster verification

Docker Desktop Kubernetes, 2 replicas of every service, all traffic through `http://localhost`:

| Scenario | Result |
|---|---|
| Every test payment method, AUTOMATIC capture | `pm_card_visa` SUCCESS (2.4s); `declined` / `insufficient_funds` FAILED with the issuer code, no capture; `capture_fails` FAILED `capture_declined` after VOIDING; `timeout_once` SUCCESS after one unknown outcome recovered with the same key (8.7s); `flaky` SUCCESS after two 503s at authorize *and* capture. Ledger credited only the 3 successes |
| Webhooks | 6 CREATED, 3 COMPLETED, 3 FAILED delivered and signed; payload 1.1 with the three failure codes |
| MANUAL capture | AUTHORIZED → capture (202) → SUCCESS; second payment cancelled (202) → voided → CANCELLED; capture after cancel → 409 with a clear message; ledger only counts the captured one |
| Refunds | Refund 202 `REFUND_PENDING` → REFUNDED; a concurrent second refund → 409; `pm_card_refund_fails` → HOLDING:HELD → REFUNDING:DECLINED → RELEASING:RELEASED, payment back to SUCCESS with `refund_declined`, merchant balance and reserve exactly as before |
| Processor outage (45s) while 20 payments arrive | Circuit breaker opened on both payment pods; payments waited in PROCESSING; all 20 SUCCESS afterwards |
| Ledger scaled to 0 during settlement | 5 sagas waited in SETTLING, commands re-sent with the same id; after scaling back up, all SUCCESS, each settled exactly once |
| payment-service pod force-killed mid-saga (6 slow authorizations in flight) | All 6 SUCCESS on the surviving replica, each settled once |
| One trace per payment | A single Jaeger trace of 33 spans across all 6 services: gateway → API → PAYMENT_CREATED → authorize → capture → ledger-commands → ledger posting → ledger-replies → PAYMENT_COMPLETED → webhook / notification consumers |
| Reconciliation of all 52 saga-era payments across the three databases | Processor, ledger and payment agree for every payment: no double capture, settlement or refund, no authorization left holding funds after a failure or cancel; ledger debits = credits (1,221.00); no saga active or parked |

Found during deployment: rolling out seven deployments at once on the single-node cluster exhausted
schedulable memory, and the restarted Postgres pod couldn't be placed while the new application pods
waited for it. Fixed with a `payflow-infrastructure` PriorityClass (Postgres, Kafka and Redis may
preempt application pods) and 384Mi memory requests in the local overlay.

Not reproducible through the API yet: a refund rejected for `INSUFFICIENT_FUNDS`. Refunds can't
exceed their own payment, so a merchant's balance only drops below a refund once money leaves it by
other means (payouts, chargebacks, fees), none of which exist yet. The rule is covered by the
ledger's tests (including 8 concurrent holds) and the saga integration tests.

## Code map

| Path | Contents |
|---|---|
| `payment-service/.../saga/` | `PaymentSagaStateMachine` (rules), `SagaOrchestrator` (applies decisions, starts sagas, replies, merchant/operator actions), `SagaWorker` (polling, worker pool), `LedgerReplyConsumer`, `PaymentSaga` / `PaymentSagaStep` (+ repositories), `SagaProperties` |
| `payment-service/.../processor/` | `ProcessorClient` (RestClient, idempotency keys, circuit breaker, outcome mapping), `ProcessorProperties` |
| `payment-service/.../common/tracing/TraceContext` | Stores and continues traces across the worker and the outbox |
| `payment-service/.../common/kafka/` | Consumer error handling for ledger replies |
| `processor-simulator/` | `ProcessorController`, `IdempotentExecutor` (per-key lock + replay), `ProcessorService` (rules, reversal), `PaymentMethodBehaviour`, `FaultInjector` |
| `ledger-service/.../command/` | `LedgerCommandConsumer`, `LedgerCommandParser`, `LedgerCommandHandler` (postings, holds, row lock, replies) |
| `ledger-service/.../outbox/` | Reply outbox and relay |
| `payment-service/.../db/migration/V7__payment_sagas.sql`, `ledger-service/.../V5__ledger_commands_and_outbox.sql`, `processor-simulator/.../V1__create_processor_tables.sql` | Schema |
