# Design decisions

The decisions that shape PayFlow, with the reasoning and the cost of each. Details live in the
linked documents; the identifier scheme also has its own record, [ADR-001](adr/ADR-001-payment-identifier.md).

## Architecture

### 1. Microservices with a database per service
- **Decision:** eight services, each with its own database and credentials; nothing reads
  another service's tables.
- **Why:** services evolve and scale independently, and a ledger bug can't corrupt payment data.
  It also forces the hard problem (consistency across services) into the open, where it is
  solved explicitly.
- **Cost:** no cross-service transactions or joins. Consistency needs sagas, outboxes and
  idempotency, and operations are heavier than for a monolith.
- **Alternatives:** a modular monolith. Simpler, and the right call for many teams; the point
  here was to build the distributed-systems machinery.

### 2. Kafka with a transactional outbox
- **Decision:** services never write to the database and Kafka in one step. The message is
  inserted into an outbox table in the same transaction as the change, and a relay publishes it.
- **Why:** a dual write can commit one side and lose the other. The outbox makes "state changed"
  and "message will be sent" atomic.
- **Cost:** a polling relay (about 0.5s of latency per hop), an outbox table to clean up, and
  at-least-once delivery.
- **Alternatives:** change data capture (Debezium) avoids the polling but adds Kafka Connect and
  more to operate; Kafka transactions don't cover the database write.

### 3. At-least-once delivery with idempotent consumers
- **Decision:** accept duplicates everywhere and make every consumer and endpoint deduplicate
  (processed-message tables, idempotency keys, business-key guards).
- **Why:** exactly-once delivery across a database, Kafka and HTTP doesn't exist. Exactly-once
  *effects* do, if every step is idempotent.
- **Cost:** a dedupe record per message, and care in every new consumer.

### 4. Explicit topics, 3 partitions, keyed by payment id
- **Decision:** topics are declared by their owning service with explicit partition counts.
  Messages are keyed by payment id, and dead-letter topics have as many partitions as their
  source.
- **Why:** with auto-created single-partition topics, only one consumer replica could work. Keys
  keep each payment's messages in order, and the outbox relays also hold a payment's later
  messages back until its earlier ones are published.
- **Cost:** partition count caps consumer parallelism (3 here); changing it remaps keys.

## Payments and money

### 5. An orchestrated saga, run by payment-service
- **Decision:** payments (authorize → capture → settle) and refunds (hold → refund → finalize)
  are sagas owned by one orchestrator inside payment-service. Its state is persisted with the
  payment.
- **Why:** the flow, its timeouts and its compensation live in one place and can be inspected.
  Saga state and payment status change in the same transaction.
- **Cost:** payment-service carries the orchestration; another domain's flows would need their
  own orchestrator.
- **Alternatives:** choreography (no coordinator: harder to follow and to time out), a separate
  orchestrator service (state in another database than the payment), or a workflow engine such
  as Temporal (powerful, but a large dependency). [SAGA.md](SAGA.md)

### 6. Compensate before the pivot, retry after it, never guess at it
- **Decision:** the capture is the pivot. Failures before it are compensated (void the
  authorization); steps after it are only retried. If the capture's own outcome stays unknown,
  the saga parks for an operator.
- **Why:** voiding a capture that actually happened, or failing a payment the customer was
  charged for, is worse than a delay.
- **Cost:** an operator queue (`REQUIRES_ATTENTION`), which must be alerted on.

### 7. The saga as a pure state machine
- **Decision:** `PaymentSagaStateMachine` maps (state, what happened) to a decision, with no
  I/O. The orchestrator applies each decision in one transaction.
- **Why:** every rule, including every compensation and timeout, is unit-tested directly
  (29 tests), with no infrastructure involved.
- **Cost:** two places to read: the rules and how they are applied.

### 8. Processor over HTTP, ledger over Kafka
- **Decision:** the external processor is called synchronously with an idempotency key per step.
  The internal ledger is driven by commands and replies over Kafka.
- **Why:** this is how real processors work (HTTP and idempotency keys). For an internal
  participant, messaging decouples availability: a ledger outage only delays settlement.
- **Cost:** two styles of saga step, each with its own timeout handling.

### 9. Reversal by the original idempotency key
- **Decision:** to compensate an authorization whose answer was lost, the saga reverses "the
  request sent with key X". The processor voids it if it exists, and leaves a marker that rejects
  it if it arrives later.
- **Why:** without this, an authorization still in flight when the saga gives up could hold the
  customer's funds anyway. Card networks provide reversals for exactly this case.

### 10. Double-entry ledger; refund holds are postings
- **Decision:** every movement is a balanced debit/credit pair. Refunds first move money from
  the merchant's balance into a per-merchant reserve account, then release or finalize it.
  Balances are computed from entries. The merchant account row is locked during the balance
  check.
- **Why:** the books always balance and every movement is auditable. The lock means two
  concurrent refunds can't spend one balance (8 concurrent holds → exactly the 3 the balance
  allows; the test fails without the lock).
- **Cost:** balance reads sum entries; at scale, a running-balance column or snapshots would be
  added.

### 11. Exact decimals end to end
- **Decision:** `BigDecimal` in Java, `NUMERIC` in Postgres, typed JSON parsing, and Kafka
  serialization that never goes through `double`.
- **Why:** found in review: re-parsing JSON turned `75.50` into `75.5` and could lose cents on
  large amounts.

### 12. Asynchronous payment API
- **Decision:** `POST /payments` returns `201 PROCESSING` at once; capture, cancel and refund
  return `202`. Outcomes arrive by webhook or `GET`.
- **Why:** a payment involves an external processor and a ledger. Holding the HTTP request open
  for all of it would tie its availability to theirs.
- **Cost:** clients must handle asynchronous outcomes (webhooks or polling).

### 13. Polling workers with `FOR UPDATE SKIP LOCKED` and leases
- **Decision:** saga steps, outbox relays, webhook deliveries and notifications are claimed from
  Postgres by polling workers on every replica. A claimed row gets a lease, and its result is
  recorded only if the claim is still valid.
- **Why:** scaling out is just adding replicas, with no coordinator and no distributed lock
  service. A crashed worker's lease simply expires.
- **Cost:** polling load and latency (sub-second intervals); database-bound throughput.

## Identity and the edge

### 14. Own OAuth2 authorization server, client credentials, JWT
- **Decision:** merchant-service runs Spring Authorization Server. Merchants use the client
  credentials grant to get 15-minute RS256 JWTs, which services validate locally against
  cached public keys.
- **Why:** this is the standard machine-to-machine flow. merchant-service is not on the request
  path, so tokens keep validating while it is down. Revoking a credential stops new tokens at
  once.
- **Cost:** a token already issued can't be revoked; it lives at most 15 minutes. The service
  itself has to be operated (vs. Keycloak/Auth0).

### 15. Validate tokens at the gateway and in every service
- **Decision:** the gateway rejects bad tokens before routing; services validate again and own
  their authorization rules (scopes, ownership).
- **Why:** abusive traffic is stopped at the edge, nothing is trusted just because it came
  through the gateway, and each service's rules stay in one place.
- **Cost:** two signature checks per request, which is cheap with cached keys.

### 16. Gateway rate limiting in Redis that fails open
- **Decision:** token bucket per merchant (or per client, or per peer IP for anonymous calls),
  in Redis, shared by all gateway replicas. If Redis is down, requests pass.
- **Why:** losing a protective limit is better than rejecting all traffic.
- **Cost:** no limit during a Redis outage.

## Platform

### 17. Kubernetes with Kustomize, everything in the cluster
- **Decision:** plain Kustomize (base plus a local overlay) on Docker Desktop. Postgres, Kafka,
  Redis, Prometheus, Grafana and Jaeger all run in the cluster.
- **Why:** the whole system comes up with one script and behaves like a real deployment
  (replicas, probes, rolling updates, autoscaling).
- **Cost:** one Kafka broker (replication factor 1) and one Postgres instance hosting seven
  databases: no infrastructure fault tolerance locally. Production would use 3+ brokers and
  managed databases. Dev secrets are generated locally rather than coming from a secret manager.

### 18. Application-generated UUIDs with typed public ids
- **Decision:** ids are random UUIDs generated by the application (behind an `IdGenerator`
  interface). Payments are exposed as `pay_<uuid>`, and endpoints reject ids without the prefix.
- **Why:** any replica can create ids without coordination; random ids can't be guessed or used
  to enumerate other merchants' payments; the prefix makes an id's type obvious in logs, tickets
  and webhooks.
- **Cost:** random keys spread inserts across the primary-key index. Time-ordered UUIDv7 would
  insert near the end instead, which matters at high write rates; switching is a change to the
  generator alone. [ADR-001](adr/ADR-001-payment-identifier.md)

## Operations

### 19. A daily three-way reconciliation, as its own service, through APIs
- **Decision:** reconciliation-service compares the processor's settlement report, the ledger's
  transactions and payment-service's statuses for each business day. It reads them through
  read-only APIs with its own least-privilege OAuth2 client, never their databases. A pure
  function does the matching; runs and discrepancies are stored and served by an operator API.
- **Why:** the sagas are designed so the three never disagree, but bugs, manual fixes and
  partner errors happen in production, and the processor's records decide what customers were
  charged. An independent daily check is the control that catches what the design missed.
  Reading through APIs keeps database-per-service true for operations too and matches how a
  real processor is reconciled (a report, not a table).
- **Cost:** one more deployment and database; new read endpoints in three services; a day is
  checked only after it closes (plus a one-hour margin), so a discrepancy surfaces the next
  morning, not in real time. Real-time problems have their own alerts (parked sagas, stuck steps).
- **Alternatives:** a Kubernetes CronJob (short-lived pods can't be scraped without a
  Pushgateway, and it has no API or run history); a job inside the ledger (it would have to
  trust its own books); reading the databases directly (simpler, but couples the job to three
  schemas). [RECONCILIATION.md](RECONCILIATION.md)

### 20. Alerts on symptoms, unit-tested, with a runbook each
- **Decision:** Prometheus rules for what an operator must act on: a parked or stuck saga, an
  outbox backlog or parked message, consumer lag, dead letters, an open circuit breaker,
  services or replicas down, gateway errors, reconciliation discrepancies or missed runs.
  Every rule has a `promtool` unit test and a runbook. Alertmanager suppresses a service's
  warnings while the whole service is down. Locally, notifications go to an in-cluster receiver;
  Slack is added only when a webhook URL is provided.
- **Why:** metrics nobody watches don't prevent incidents. Testing the rules catches the classic
  mistakes, such as an alert that loses its labels or never fires when a service has no pods at
  all. Consumer lag comes from a Kafka exporter rather than the consumers themselves, so it shows
  even when every consumer is dead.
- **Cost:** thresholds are tuned for a demo and would need real traffic data; there is no
  paging or on-call rotation behind the receiver.
- **Alternatives:** Grafana-managed alerts (tied to the dashboard tool, harder to test in CI); a
  hosted monitoring service (nothing to run, but the setup would leave the repository).
  [DEPLOYMENT.md](DEPLOYMENT.md#alerting)
