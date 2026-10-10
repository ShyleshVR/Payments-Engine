# Interview notes

Talking points for presenting PayFlow: pitches, the numbers worth remembering, likely questions
with short answers, true stories of bugs found, and what is deliberately not solved. Each
answer gives the claim first and the evidence after it.

## The pitch

**30 seconds.** "PayFlow is a card-payments backend: nine Spring Boot services on Kubernetes
behind an API gateway. Merchants authenticate with OAuth2, payments are authorized and captured
against a card processor and booked in a double-entry ledger, merchants are paid out to their
bank accounts, and they get signed webhooks.
The interesting part is correctness under failure. A payment is an orchestrated saga with
compensation, every hop is idempotent, and every message goes through a transactional outbox. I
verified it by killing pods and taking the processor and the ledger down mid-flow, and a daily
reconciliation job checks the processor, the ledger and the payment statuses against each other,
with alerts when anything is off."

**2 minutes:** walk the [payment flow](ARCHITECTURE.md#how-a-payment-flows):
- token issued, then checked at the gateway;
- payment and saga created in one transaction;
- authorize and capture with idempotency keys;
- settle through Kafka command/reply;
- `PAYMENT_COMPLETED` fanned out to webhooks and notifications.

Then name the three hardest problems and how each is solved:
1. A processor answer lost to a timeout: retry with the same key, and reversal by key.
2. The ledger being down after money moved: the pivot rule (retry after capture, never
   compensate), plus re-sent commands.
3. Several replicas working the same table: `SKIP LOCKED`, leases, and attempt checks.

## Numbers to remember

| | |
|---|---|
| Services / databases / Kafka topics | 9 / 8 / 6 main topics plus dead-letter topics |
| Automated tests | 433, run in CI on every push; 29 for the payment saga state machine and 21 for the payout one, 15 Testcontainers saga scenarios, 31 reconciliation rules; plus promtool tests for every alert rule |
| Rolling restart under ~12 req/s | 429 requests, 0 failed |
| Pods force-killed under traffic | ~1,085 requests, 0 failed (after adding gateway retries for idempotent requests) |
| Processor outage of 45s, 20 payments in flight | Circuit opened; all 20 succeeded afterwards |
| Ledger scaled to 0 during settlement | Commands re-sent, every payment booked exactly once afterwards |
| Concurrent refund holds (8 × 30 against a balance of 100) | Exactly 3 succeed; without the row lock the test fails |
| Concurrent duplicate processor requests (8 identical) | 1 authorization, 8 identical answers |
| Reconciliation, 52 payments × 3 databases (by hand, saga phase) | 0 discrepancies; debits = credits |
| Daily reconciliation after a simulated incident (ledger down, 13-min processor outage, ~950 payments) | 916 payments with money movement, 916 matched, 0 discrepancies |
| Injected corruption (3 kinds) | Each detected with the right type; alert in alert-sink within ~40s; resolved after the fix and a re-run |
| Alert rules | 16, each unit-tested with promtool; 11 also fired for real on the cluster |
| Payouts on the cluster | Every bank outcome (paid, failed, invalid, returned, stuck) ended right; a bank outage plus a pod kill gave exactly 1 transfer; 7 payouts reconciled with 0 discrepancies, an injected amount change caught |
| Autoscaling under ~90 req/s | payment-service and gateway 2 → 4 pods, 9,341 responses, 0 errors |

## Likely questions

### Architecture

**Why microservices?** To own the hard parts explicitly: consistency across databases, partial
failure, idempotency. For a real early-stage product a modular monolith is often better, and I
would say so. Splitting along money boundaries (payments, ledger, identity, delivery) also
limits the damage a bug in one can do.

**Why does every service have its own database?** So no service can couple to another's schema,
and the ledger's data can't be written by anything but the ledger. The cost is no cross-service
transactions, which is what the saga and the outbox are for.

### Messaging and consistency

**What is the transactional outbox and why use it?** Writing to the database and to Kafka are
two separate operations; either can fail after the other succeeded. Instead, the message goes
into an outbox table in the same transaction as the change, and a relay publishes it afterwards.
It can be published twice, but never lost, and never sent for a change that rolled back. The
alternative is change data capture with Debezium: no polling, but Kafka Connect to run.

**Do you have exactly-once?** Not delivery: nothing gives that across a database, Kafka and
HTTP. The effects are exactly-once:
- ledger commands are recorded with their replies, and a repeat gets the stored reply;
- processor calls carry idempotency keys, and repeats replay the stored answer;
- a settlement is unique per payment, even under a different command id.

The reconciliation checks the result: no double capture, settlement or refund.

**How do you keep a payment's events in order?** Keys are payment ids, so one payment's messages
share a partition. The outbox relay also never publishes a payment's later message while an
earlier one is still unpublished; a stuck message blocks only its own payment.

**What happens when a consumer fails?** It depends on why, decided by a failure classifier:
- **Infrastructure error** (database down): retry until it passes, holding the partition, so
  nothing is skipped.
- **Malformed message:** straight to the dead-letter topic.
- **Anything else:** a few retries, then the dead-letter topic.

The backoff is capped below the consumer's poll timeout, so retrying never gets the consumer
evicted from its group.

### The saga

**Why orchestration rather than choreography?** One place owns the flow, its timeouts and its
compensation, and any payment's saga can be inspected step by step. With choreography the flow
is spread across services, and a missing reaction is hard to notice. The orchestrator lives in
payment-service, so saga state and payment status change in one transaction.

**What if the processor times out on capture?** That's the pivot: money may have moved. The saga
retries with the same idempotency key, and the processor returns what it did the first time. If
there is still no answer after 10 minutes, it parks for an operator. It never voids and never
marks the payment failed, because either could be wrong. A timeout on *authorization* is
different: nothing has been charged yet, so after 2 minutes the saga reverses the authorization
and fails the payment.

**What is "reversal by key"?** If the saga gives up on an authorization whose answer it never
got, the request might still be in flight. A reversal names the *original request's*
idempotency key. If the authorization exists, it is voided. If it hasn't arrived yet, a marker
is left, and when it does arrive it is rejected. Without this, a customer's funds could stay
held for an order that failed.

**What if the orchestrator crashes mid-step?** Every claimed step has a lease. When it expires,
another replica claims the step and repeats it with the same idempotency key. A result from the
old worker, should it still arrive, is discarded because the attempt counter moved on. This was
tested by force-killing a payment pod while 6 slow authorizations were in flight: all 6
completed, each booked once.

**Why put the hold before the processor refund?** The most likely "no" is the merchant not having
the balance. Checking it first means there's nothing to undo. If the processor then refuses,
the hold is released and the balance is exactly what it was.

**How did you test all this?** In three layers:
1. **State machine unit tests:** every rule, as a pure function.
2. **Testcontainers integration tests:** real Postgres, Kafka and Redis, a stub processor over
   HTTP, and a stub ledger over Kafka. 15 scenarios, including outage, lost answer, overdue
   reply and operator retry.
3. **Fault injection on the live cluster,** finished with a three-database reconciliation.

### Ledger

**Why double entry?** Every movement is a debit and a credit of the same amount, so the books
always balance; that is the check. Holds are postings too (balance → reserve), so nothing is
"soft": every cent is in some account.

**How do you stop two refunds spending the same balance?** The merchant's account row is locked
(`SELECT … FOR UPDATE`) before the balance is read and the hold posted. Tested: 8 concurrent
holds of 30 against 100, exactly 3 succeed. Removing the lock makes the test fail; I checked
that to be sure the test really tests the lock.

**How do payouts work, and what stops a payout and a refund spending the same money?**
- **The saga:** hold the payable balance in the ledger, transfer at the bank (asynchronous:
  accepted, then paid or failed), finalize; a bank refusal releases the hold.
- **Payable balance:** only settlements older than the payout delay (T+2), so recent money is
  left for refunds.
- **The lock:** the hold takes the same row lock as refund holds, so the ledger decides. Tested
  with 4 refunds and 4 payouts of 60 racing for 100: exactly one succeeds. On the cluster, a
  refund and an instant payout racing for one balance: one won.
- **The pivot:** the transfer. An unknown outcome there is never released; the saga parks, and
  an operator retries under the same idempotency key.

**What's hard about payouts that payments don't have?** "Paid" isn't final: a bank can return a
transfer days later. The saga stays open for a return window, keeps checking, and books a return
back to the merchant (PAYOUT_RETURNED webhook). The reconciliation tolerates a return younger
than the saga's check interval instead of flagging it.

### Scaling

**How do multiple replicas avoid doing the same work?** Every worker claims rows with
`FOR UPDATE SKIP LOCKED`, takes a lease, and records its result only if the claim is still
valid. Adding replicas adds throughput with no coordinator. Kafka consumers scale up to the
partition count (3); a fourth replica would be idle.

**What would you change at 100x?**
- Change data capture instead of polling outboxes.
- More partitions, watching for hot keys.
- A Kafka cluster with replication factor 3.
- Running balances in the ledger instead of summing entries.
- Separate managed databases per service.
- A schema registry for event contracts.
- Partitioning the saga worker's queries.

### Security

**Why JWT with client credentials, and how is a token revoked?** It's the standard
machine-to-machine flow, and services validate tokens locally, so merchant-service isn't on
every request's path. Revoking a credential stops new tokens at once. An issued token can't be
revoked, so tokens last 15 minutes; that is the trade-off against an introspection call per
request.

**Why validate at the gateway and again in each service?** The gateway stops forged and expired
tokens before they use any service's resources. Each service still validates, so nothing is
trusted just because it came through the gateway, and the authorization rules (scopes,
ownership) stay with the data they protect.

**How are webhooks secured?**
- **Signature:** HMAC-SHA256 over the timestamp and raw body, so merchants verify authenticity
  and reject replays outside a time window.
- **SSRF:** subscription URLs must be HTTPS and must not resolve to private or internal
  addresses. Otherwise the webhook system could be used to probe the internal network.

### Kubernetes and operations

**How do you get zero-downtime deploys?**
- `maxUnavailable: 0` with a readiness probe, so new pods take traffic only when ready.
- A preStop sleep, so endpoints are removed before shutdown begins.
- Spring graceful shutdown, so in-flight requests finish.

Verified: 429 requests during a rolling restart, 0 failed. Liveness never checks dependencies; a
database outage must not restart every pod.

**What does observability look like?** Prometheus finds pods itself, and Grafana has saga,
processor and ledger panels. One trace per payment in Jaeger: the request's trace context is
stored with the saga and on every outbox row, and continued wherever work resumes, so the
asynchronous steps join the original trace.

**What do you alert on?** Symptoms an operator must act on, not every metric: a saga parked
for a human or a step running over 15 minutes, an outbox that isn't draining, consumer lag, dead
letters, an open circuit breaker, a service with no healthy pod, and reconciliation
discrepancies. Each rule has a `promtool` unit test and a runbook. The tests found a real bug:
`absent(up == 1)` drops the job label, so all services collapsed into one alert.

**How do you know the books are right?** A daily reconciliation, like real payment companies
run. reconciliation-service reads the processor's settlement report, the ledger and the payment
statuses through APIs and classifies every disagreement (captured but not booked, booked but not
captured, amount mismatch, a failed payment still holding funds, ...). Facts are checked on the
day they happened, matched within a window around midnight, so nothing is checked twice and
nothing falls between days. Payments mid-saga are reported as pending, not as errors.

**How does a scheduled job run once with several replicas?** ShedLock (a lock row in Postgres
using the database's clock), plus "one completed run per day" checked before running, plus a
partial unique index allowing only one running run per day, which also covers a manual run racing
the scheduler. An hourly catch-up re-runs any recent day that has no completed run.

## Stories: things that went wrong, and what they taught

- **A test passing for the wrong reason.** The gateway's rate-limit test passed security checks
  but the rate limiter never ran. The test's fake upstream was a controller inside the gateway
  app, and Spring matched it before the gateway's routes. Fix: a real upstream server. Lesson:
  check that a test fails when the feature is removed.
- **Money precision.** Re-parsing event JSON turned `75.50` into `75.5` and could lose cents on
  large amounts. Found in review; fixed with typed decimal parsing end to end, plus a ledger
  column that was too narrow for the payment column's range.
- **A dependency that silently disabled another.** Adding a second `KafkaTemplate` bean (for
  dead letters) made Spring Boot skip creating its main one, so payment-service would not have
  started. The Testcontainers integration test caught it before deployment.
- **A Kubernetes scheduling deadlock.** Rolling out seven deployments at once filled the node's
  memory. The restarted Postgres pod couldn't be scheduled, and the new app pods waited for
  Postgres while the old ones held the memory. Fix: a priority class so infrastructure is always
  scheduled first, and right-sized memory requests.
- **Alerts that couldn't see a first failure.** On the cluster, three payouts failed and
  `PayoutsFailing` stayed silent. The counter for each outcome was created on its first
  increment, so Prometheus' first sample was already 1, and `increase()` counts nothing for a
  series that starts at its first event. The same flaw sat in `ReconciliationRunFailed`. The
  promtool tests passed because their series start at 0. Fix: register the counters at 0 on
  startup. Lesson: an alert test must use the shape the metric really has in production.
- **An alert that went quiet for the wrong reason.** The first reconciliation alert followed
  only the most recent run. On the first deploy, the catch-up reconciled three days in a row,
  and the last day was clean, so the alert stayed silent while the day before had 218
  discrepancies. Fix: alert on each recent day's latest run. Lesson: test what an alert does
  over a sequence of events, not one state.
- **Legacy data versus a new control.** The first reconciliation flagged 109 payments: all made
  before the processor existed, settled through the old operator endpoints. They weren't wrong,
  just out of scope. Reconciliation now covers processor-backed payments only, decided from
  each payment's data, not a configured cut-over date. The same migration left the ledger's old
  offsets on `payment-created`, which the consumer-lag alert caught days later.
- **The last 0.3% of failures.** Killing a pod dropped 1 of 362 requests: a GET in flight on the
  dying pod. Adding gateway retries for idempotent requests on transport errors only (never
  POSTs, never a service's own 500) brought it to 0 over ~1,085 requests.

## What is deliberately not solved

| Limit | Why it's acceptable here, and the fix |
|---|---|
| One Kafka broker, one Postgres instance | Local footprint; production: 3+ brokers with replication factor 3, managed databases |
| No fees, FX or negative balances | Payouts pay only what is payable; a real system would net fees, convert currencies, and debit the merchant's bank when refunds exceed the balance |
| A simulated bank | The bank, like the card processor, is a simulator with test accounts; real rails (ACH, SEPA) bring cut-off times, files and return codes |
| Polling outboxes and workers | Simple and reliable; change data capture for lower latency at scale |
| Event classes copied into each service | Explicit and independent, but they can drift; a shared contract module or schema registry is next |
| Alerts go to an in-cluster receiver | No paging or on-call; Slack is one file away, and production would route critical alerts to a pager |
| Reconciliation is daily | A discrepancy surfaces the next morning; real-time issues have their own alerts. Pre-processor payments are out of scope (no processor record) |
| Card data | The processor is a simulator with tokens; real card data would bring PCI DSS scope, tokenization and a vault |
| Random (v4) UUID keys | Unguessable and coordination-free; at high insert rates time-ordered UUIDv7 keys index better, and switching touches only the generator ([ADR-001](adr/ADR-001-payment-identifier.md)) |
