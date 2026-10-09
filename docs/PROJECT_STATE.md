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

## Next candidates

- **Alerting and reconciliation:** Prometheus alert rules (parked sagas, outbox lag, consumer
  lag, open circuit breaker) with Alertmanager, and a scheduled reconciliation job between
  processor and ledger.
- **Merchant payouts:** pay out settled balances in batches, as a saga; completes the money flow.
- **Engineering cleanup:**
  - a shared event-contract module or schema registry;
  - Testcontainers tests for the notification and webhook claim queries;
  - time-ordered UUIDv7 identifiers (see ADR-001);
  - a load test with performance targets.
- **Production readiness:** images in a registry, GitOps deployment, a 3-broker Kafka cluster,
  managed Postgres, External Secrets, network policies.
