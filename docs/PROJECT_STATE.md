# Project state

## Done

| Phase | What it added | Docs |
|---|---|---|
| Core | Payments API with idempotent creation, transactional outbox, ledger and notification consumers | [ARCHITECTURE.md](ARCHITECTURE.md) |
| Hardening | Consumer error classification, dead-letter topics, exact decimal amounts, permanent vs transient notification failures | [ARCHITECTURE.md](ARCHITECTURE.md#failure-handling) |
| Webhooks | Signed, retried, deduplicated merchant webhooks with SSRF protection | [WEBHOOK_SERVICE.md](WEBHOOK_SERVICE.md) |
| Merchant identity | OAuth2 authorization server, client credentials, JWT, scopes, data ownership | [MERCHANT_AUTH.md](MERCHANT_AUTH.md) |
| Kubernetes and gateway | API gateway, Kustomize deployment, autoscaling, probes, CI | [DEPLOYMENT.md](DEPLOYMENT.md) |
| Sagas | Processor integration (simulator), orchestrated payment and refund sagas, ledger commands, one trace per payment | [SAGA.md](SAGA.md) |
| Alerting and reconciliation | Prometheus alert rules (promtool-tested) and Alertmanager with runbooks; reconciliation-service: daily three-way reconciliation of processor, ledger and payments | [RECONCILIATION.md](RECONCILIATION.md), [DEPLOYMENT.md](DEPLOYMENT.md#alerting) |
| Merchant payouts | payout-service: daily batch and instant payouts as sagas, funds availability (payable balance), an asynchronous bank with failures and returns, payout webhooks, payouts in the daily reconciliation, payout alerts | [PAYOUTS.md](PAYOUTS.md) |
| Load testing | k6 as an in-cluster Job, SLOs as thresholds, a ceiling search; nine bottlenecks fixed (saga reservations, batched outbox relays, an HTTP client executor, more partitions and consumer threads, shorter polls, Kafka 4.2.0, lock-waiting claims, an outbox query plan, queue-table autovacuum): 100 → 200 payments/s | [LOAD_TEST.md](LOAD_TEST.md) |

## Next candidates

- **Past 200 payments/s:** the shared Postgres instance's write-ahead log is the limit. Options:
  fewer commits per payment (e.g. recording a saga step and claiming the next in one
  transaction), a database per service, faster storage.
- **Payout refinements:** fees, multi-currency (FX), negative balances with bank debits, payout
  schedules per merchant.
- **Engineering cleanup:**
  - a shared event-contract module or schema registry;
  - Testcontainers tests for the notification and webhook claim queries;
  - time-ordered UUIDv7 identifiers (see ADR-001);
  - a shared saga library (payment-service and payout-service copy the machinery).
- **Production readiness:** images in a registry, GitOps deployment, a 3-broker Kafka cluster,
  managed Postgres, External Secrets, network policies.
