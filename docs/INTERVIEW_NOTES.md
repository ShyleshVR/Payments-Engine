# Interview notes

Talking points for presenting PayFlow: pitches, the numbers worth remembering, likely questions
with short answers, true stories of bugs found, and what is deliberately not solved. Each
answer gives the claim first and the evidence after it.

## The pitch

**30 seconds.** "PayFlow is a card-payments backend: seven Spring Boot services on Kubernetes
behind an API gateway. Merchants authenticate with OAuth2, payments are authorized and captured
against a card processor and booked in a double-entry ledger, and merchants get signed webhooks.
The interesting part is correctness under failure. A payment is an orchestrated saga with
compensation, every hop is idempotent, and every message goes through a transactional outbox. I
verified it by killing pods, taking the processor and the ledger down mid-flow, and reconciling
every payment across three databases afterwards."

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
| Services / databases / Kafka topics | 7 / 6 / 4 main topics plus dead-letter topics |
| Automated tests | 319, run in CI on every push; 29 for the saga state machine, 15 Testcontainers saga scenarios |
| Rolling restart under ~12 req/s | 429 requests, 0 failed |
| Pods force-killed under traffic | ~1,085 requests, 0 failed (after adding gateway retries for idempotent requests) |
| Processor outage of 45s, 20 payments in flight | Circuit opened; all 20 succeeded afterwards |
| Ledger scaled to 0 during settlement | Commands re-sent, every payment booked exactly once afterwards |
| Concurrent refund holds (8 × 30 against a balance of 100) | Exactly 3 succeed; without the row lock the test fails |
| Concurrent duplicate processor requests (8 identical) | 1 authorization, 8 identical answers |
| Reconciliation, 52 payments × 3 databases | 0 discrepancies; debits = credits |
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
- **The last 0.3% of failures.** Killing a pod dropped 1 of 362 requests: a GET in flight on the
  dying pod. Adding gateway retries for idempotent requests on transport errors only (never
  POSTs, never a service's own 500) brought it to 0 over ~1,085 requests.

## What is deliberately not solved

| Limit | Why it's acceptable here, and the fix |
|---|---|
| One Kafka broker, one Postgres instance | Local footprint; production: 3+ brokers with replication factor 3, managed databases |
| Payouts don't exist | So merchant balances only shrink through refunds, and the insufficient-balance refund path is reachable only in tests; payouts are the next feature |
| Polling outboxes and workers | Simple and reliable; change data capture for lower latency at scale |
| Event classes copied into each service | Explicit and independent, but they can drift; a shared contract module or schema registry is next |
| No alerting rules yet | The metrics exist (`sagas_requires_attention`, outbox lag, consumer lag); Prometheus alert rules and Alertmanager are next |
| Card data | The processor is a simulator with tokens; real card data would bring PCI DSS scope, tokenization and a vault |
| Random (v4) UUID keys | Unguessable and coordination-free; at high insert rates time-ordered UUIDv7 keys index better, and switching touches only the generator ([ADR-001](adr/ADR-001-payment-identifier.md)) |
