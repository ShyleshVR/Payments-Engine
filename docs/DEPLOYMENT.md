# Deployment: Kubernetes, API Gateway and CI

PayFlow runs on Kubernetes behind a single public entry point, the **API gateway**, with two
replicas of every service. All the platform's dependencies (Postgres, Kafka, Redis, Prometheus,
Alertmanager, Grafana and Jaeger) run in the same cluster. The manifests are plain Kustomize, the target is
Docker Desktop's built-in Kubernetes, and GitHub Actions tests every service, builds the images
and validates the manifests on every push to `main` and every pull request.

```
http://localhost ──(Service type LoadBalancer :80)──▶ api-gateway ×2 (HPA 2–4)
      │   JWT check (signature, exp, iss, aud) · rate limit per merchant (Redis) · X-Request-Id
      ├── /oauth2/**, /api/v1/merchants/** ─▶ merchant-service ×2   (token endpoint: no JWT needed)
      ├── /api/v1/payments/**             ─▶ payment-service ×2 (HPA 2–4)
      ├── /api/v1/ledger/**               ─▶ ledger-service ×2
      ├── /api/v1/webhooks/**             ─▶ webhook-service ×2
      ├── /api/v1/reconciliation/**       ─▶ reconciliation-service ×2 (daily job; see RECONCILIATION.md)
      └── /api/v1/payouts/**              ─▶ payout-service ×2 (batch + instant payouts; see PAYOUTS.md)
      notification-service ×2 (no public API)
      processor-simulator ×2 (card processor and bank stand-in, internal only; see SAGA.md, PAYOUTS.md)

namespace payflow:
  postgres   StatefulSet, 1 instance, 8 databases (one per service, each with its own user)
  kafka      StatefulSet, 1 KRaft broker, topics with 3 partitions (events + saga commands/replies)
  redis      rate-limit buckets, idempotency cache
  prometheus (Kubernetes pod discovery, alert rules) · alertmanager ─▶ alert-sink (or Slack)
  kafka-exporter (consumer lag) · grafana (provisioned dashboard) · jaeger (OTLP traces)
```

## Contents

1. [Quick start](#quick-start)
2. [What runs in the cluster](#what-runs-in-the-cluster)
3. [API gateway](#api-gateway)
4. [Token issuer and signing key](#token-issuer-and-signing-key)
5. [Kafka topics and consumer scaling](#kafka-topics-and-consumer-scaling)
6. [Probes, graceful shutdown and rolling updates](#probes-graceful-shutdown-and-rolling-updates)
7. [Scaling and availability](#scaling-and-availability)
8. [Configuration and secrets](#configuration-and-secrets)
9. [Pod security](#pod-security)
10. [Observability](#observability)
11. [Alerting](#alerting)
12. [CI](#ci)
13. [Design decisions and trade-offs](#design-decisions-and-trade-offs)
14. [Testing](#testing)
15. [File map](#file-map)

---

## Quick start

Prerequisites: Docker Desktop with Kubernetes enabled (*Settings → Kubernetes → Enable
Kubernetes*), Java 21, Maven, Git Bash (on Windows) and `openssl`.

```bash
# The cluster's UIs use localhost:3000/9090/16686: stop the docker-compose stack first.
docker compose -f infrastructure/docker/docker-compose.yml stop

kubectl config use-context docker-desktop
scripts/k8s-up.sh          # build 6 images, create secrets, deploy, wait until ready
                           # (SKIP_BUILD=1 to redeploy without rebuilding)
kubectl -n payflow get pods
```

`k8s-up.sh` builds the jars and images (`payflow/<service>:local`), generates the local secrets
once (see [Configuration and secrets](#configuration-and-secrets)), installs metrics-server if
missing, applies `k8s/overlays/local` and waits for every rollout. When run again after a code
change, it also restarts the deployments so they pick up the rebuilt images.

Everything goes through `http://localhost`:

```bash
ADMIN_SECRET=$(grep admin-client-secret k8s/overlays/local/secrets/auth.env | cut -d= -f2)

# 1. operator token, 2. onboard a merchant (the clientSecret is shown once)
ADMIN=$(curl -s -u "payflow-admin:$ADMIN_SECRET" -d grant_type=client_credentials \
  http://localhost/oauth2/token | jq -r .access_token)
curl -s -X POST http://localhost/api/v1/merchants -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d '{"name":"Acme","email":"ops@acme.example"}'

# 3. merchant token, then any API
TOKEN=$(curl -s -u "$CLIENT_ID:$CLIENT_SECRET" -d grant_type=client_credentials \
  http://localhost/oauth2/token | jq -r .access_token)
curl -si -X POST http://localhost/api/v1/payments -H "Authorization: Bearer $TOKEN" \
  -H 'Idempotency-Key: order-1' -H 'Content-Type: application/json' \
  -d '{"amount":10.00,"currency":"USD"}'
```

| UI | URL | Login |
|---|---|---|
| Grafana | http://localhost:3000 | `admin` / `grafana-admin-password` in `k8s/overlays/local/secrets/auth.env` |
| Prometheus | http://localhost:9090 | — |
| Jaeger | http://localhost:16686 | — |

`scripts/k8s-down.sh` deletes the `payflow` namespace, including the Postgres and Kafka
volumes. The generated secrets are kept, so the next `k8s-up.sh` starts with empty databases
and the same credentials.

## What runs in the cluster

| Component | Kind | Replicas | Port | Notes |
|---|---|---|---|---|
| api-gateway | Deployment + **LoadBalancer** Service | 2 (HPA 2–4) | 80 → 8085 | Only public entry point |
| merchant-service | Deployment | 2 | 8084 | Authorization server; signing key from a Secret |
| payment-service | Deployment | 2 (HPA 2–4) | 8080 | Saga orchestrator and outbox relay on every replica |
| processor-simulator | Deployment | 2 | 8086 | Card processor stand-in; no gateway route |
| ledger-service | Deployment | 2 | 8082 | Kafka consumer group `ledger-service` |
| notification-service | Deployment | 2 | 8081 | No HTTP API exposed through the gateway |
| webhook-service | Deployment | 2 | 8083 | Dispatchers on every replica |
| reconciliation-service | Deployment | 2 | 8087 | Daily reconciliation; one replica runs it at a time (ShedLock) |
| payout-service | Deployment | 2 | 8088 | Payout batch (ShedLock) and payout sagas on every replica; demo timings in the local overlay (see PAYOUTS.md) |
| postgres | StatefulSet + 2Gi PVC | 1 | 5432 | `max_connections=300` for all replicas' pools |
| kafka | StatefulSet + 2Gi PVC | 1 | 9092 | KRaft (no ZooKeeper), `apache/kafka:4.0.0` |
| redis | Deployment | 1 | 6379 | No persistence; keys have TTLs |
| prometheus / grafana / jaeger | Deployment | 1 | 9090 / 3000 / 16686 | LoadBalancer in the local overlay |
| alertmanager | Deployment | 1 | 9093 | Routes alerts; LoadBalancer in the local overlay |
| alert-sink | Deployment | 1 | 9095 | Logs each notification as a JSON line |
| kafka-exporter | Deployment | 1 | 9308 | Consumer-group lag and topic offsets from the broker |

Every application pod also gets a PodDisruptionBudget (`minAvailable: 1`) and a
`wait-for-dependencies` init container. Spring Boot exits when its database is unreachable at
startup, so without that container, a fresh cluster would spend minutes in crash-loop backoff
while Postgres and Kafka start. The init container polls the TCP ports with the app image's own
busybox `nc`, so it needs no extra image.

## API gateway

`api-gateway` is a Spring Cloud Gateway (WebFlux, Netty) application. It does three jobs that
would otherwise be repeated in, or missing from, every service:

**Edge authentication.** Every `/api/**` request needs a valid PayFlow access token *before* it
is routed. The gateway checks the same things the services do: RS256 signature against
merchant-service's JWKS (cached, refetched only for an unknown `kid`), `exp`/`nbf`, `iss` and
`aud = payflow-api`. Forged, expired or foreign tokens are rejected at the edge and never use a
service's threads or connections. The services still verify the token and enforce scopes and
merchant ownership themselves (defense in depth). The gateway checks authenticity only, so each
service keeps its authorization rules in one place.

| Path | Rule |
|---|---|
| `/oauth2/**` | Public (token endpoint, JWKS): clients authenticate there with their credentials |
| `/api/**` | Valid bearer token |
| `/actuator/health/**`, `/actuator/info`, `/actuator/prometheus` | Public |
| anything else | 403 (deny by default) |

Rejections use the services' error shape plus the RFC 6750 header:

```http
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer error="invalid_token", error_description="Signed JWT rejected: Invalid signature"
Content-Type: application/json

{"timestamp":"2026-10-07T05:03:15.98","status":401,"message":"Signed JWT rejected: Invalid signature"}
```

**Rate limiting.** A token bucket per caller in Redis (`RequestRateLimiter` + `RedisRateLimiter`),
shared by all gateway replicas, so the limit doesn't multiply with the replica count. The bucket
key comes from `RateLimitKeyResolver`:

| Caller | Bucket |
|---|---|
| Merchant token | `merchant:<merchant_id>`: all of a merchant's credentials share one budget |
| Operator token | `client:<sub>` |
| No token (token endpoint) | `ip:<peer address>`: slows down client-secret guessing. `X-Forwarded-For` is ignored, because a caller could set it to get a fresh bucket per request |

Defaults: **20 requests/s sustained, bursts of 40** (`payflow.gateway.rate-limit.*`). Every
response carries `X-RateLimit-Remaining`, `-Burst-Capacity`, `-Replenish-Rate` and
`-Requested-Tokens`. Over the limit, the gateway returns **429** without calling the service.

**Request id.** Every request gets an `X-Request-Id`: the caller's own, if it is short and plain
(`[A-Za-z0-9._-]{1,64}`, so it can't inject anything into logs), otherwise a new UUID. The id is
sent to the service and echoed on the response, including the gateway's own 401, 403 and 429
responses, so a merchant can quote it to support. Traces are correlated separately through the
W3C `traceparent` header that Micrometer Tracing propagates.

**Upstream failures.** If a pod dies mid-request (crash, OOM kill, forced delete), or refuses
connections while it starts, the gateway retries **GET/HEAD** requests up to twice on another
pod (50ms, then 100ms backoff). It retries only transport failures, never an error response the
service actually sent. The Retry filter runs after the rate limiter, so retries don't use up the
caller's tokens. POSTs aren't retried at the gateway, because the original might already have
been applied; clients retry them with the same `Idempotency-Key`. Failures that remain are
reported precisely, in the same JSON shape:

| Upstream failure | Status |
|---|---|
| Connection refused (no pod listening) | **503**, nothing was sent, safe to retry |
| Connection closed before a response | **502**, a POST may or may not have been applied |
| No response within 30s (`response-timeout`) | **504** |

Connections time out after 2s (`connect-timeout`), so an unreachable pod fails fast.

Routes are configured in `api-gateway/src/main/resources/application.yaml`. The upstream URLs
(`payflow.gateway.routes.*`) default to localhost ports for running outside the cluster. The
Deployment overrides them with cluster DNS names (`http://payment-service:8080`, …).

## Token issuer and signing key

A token's `iss` must be identical everywhere it is checked, but the URL used to fetch keys
differs between outside and inside the cluster. The two are therefore configured separately:

| Setting | Cluster value | Used by |
|---|---|---|
| `payflow.auth.issuer` | `http://localhost` (the public gateway URL) | merchant-service, written into `iss` |
| `…resourceserver.jwt.issuer-uri` | `http://localhost` | gateway + services: string compare with `iss`, never called |
| `…resourceserver.jwt.jwk-set-uri` | `http://merchant-service:8084/oauth2/jwks` | gateway + services: key download over cluster DNS |

Both merchant-service replicas sign with the **same RSA key**: a PKCS#8 PEM in the
`payflow-signing-key` Secret, mounted read-only and loaded by `PemFileJwkSource`. Its `kid` is the
key's RFC 7638 thumbprint, so every replica publishes the same `kid` and a token from one replica
validates everywhere. Outside Kubernetes, when `payflow.auth.signing-key.location` is not set,
the key is the one stored in merchant-service's database (see
[MERCHANT_AUTH.md](MERCHANT_AUTH.md)).

## Kafka topics and consumer scaling

Topics are declared as `NewTopic` beans by the service that owns them. Kafka's auto-creation
would give them one partition, and then only one instance of each consumer group could ever do
work. At startup, KafkaAdmin creates missing topics and adds partitions to existing topics that
have fewer.

| Topic | Declared by | Partitions | Key |
|---|---|---|---|
| `payment-created` | payment-service | 3 | payment id (per-payment order within a partition) |
| `payment-created.DLT` | payment-service | 3 | — |
| `webhook-deliveries.DLT` | webhook-service | 3 | merchant id |
| `notification-events.DLT` | notification-service | 3 | payment id |
| `ledger-commands` (+ `.DLT`) | payment-service | 3 | payment id (saga commands to the ledger) |
| `ledger-replies` (+ `.DLT`) | ledger-service | 3 | payment id (replies to the orchestrator) |

A dead-letter topic needs **at least as many partitions as its source**, because the
dead-letter recoverer writes each record to the same partition number it came from.

With 3 partitions, the 2 replicas of ledger-service, notification-service and webhook-service
each own 1–2 partitions of `payment-created`. A third replica would own one partition each; a
fourth would sit idle as a hot standby. Partition count (`PAYFLOW_KAFKA_PARTITIONS`) caps consumer
parallelism.

The broker's `num.partitions` is also 3, so a topic that does get auto-created (for example by a
producer racing a service's startup) still gets enough partitions.

> Increasing the partition count of an existing topic changes which partition a key maps to.
> For a short time, a payment's old and new events can then sit in different partitions. The
> consumers are idempotent and don't rely on cross-event order, so this is acceptable here.

The outbox relay (payment-service), webhook dispatcher and dead-letter relays already claimed
their work with `FOR UPDATE SKIP LOCKED`, leases and optimistic versions, which is what makes
running two of each safe: an event is published once and a delivery is in flight on one replica
at a time.

## Probes, graceful shutdown and rolling updates

Every service has `management.endpoint.health.probes.enabled: true`,
`server.shutdown: graceful` and `spring.lifecycle.timeout-per-shutdown-phase: 20s`.

| Probe | Endpoint | Behaviour |
|---|---|---|
| startup | `/actuator/health/liveness` | Up to 3 minutes (36 × 5s) for the JVM and Flyway; the other probes wait for it |
| readiness | `/actuator/health/readiness` | Failing: the pod is removed from the Service's endpoints and gets no traffic, but isn't restarted |
| liveness | `/actuator/health/liveness` | Failing: restart. Reflects the application only, **never its dependencies**, so a database outage doesn't restart every pod |

Pod shutdown, in order:

1. The pod is marked terminating and removed from the Service endpoints. This takes a moment
   to reach kube-proxy and the gateway's connection pool.
2. `preStop: sleep 10`: the pod keeps serving requests that were already routed to it.
3. SIGTERM: Spring stops accepting requests and finishes the ones in flight (up to 20s).
4. `terminationGracePeriodSeconds: 45` covers both, with margin.

Rolling updates use `maxSurge: 1, maxUnavailable: 0`: a new pod has to pass readiness before an
old one is stopped, so capacity never drops below the replica count during a deploy.

## Scaling and availability

- **HorizontalPodAutoscaler** for api-gateway and payment-service: 2–4 pods at 70% CPU of the
  request. payment-service scales down only after 5 minutes of low load, so a short lull doesn't
  drop capacity just before the next burst. metrics-server supplies the CPU numbers;
  `k8s-up.sh` installs it with `--kubelet-insecure-tls`, which Docker Desktop's self-signed
  kubelet certificate needs.
- The consumer services aren't autoscaled on CPU: their throughput is capped by the partition
  count, not by CPU.
- **PodDisruptionBudgets** keep at least one pod of every application through voluntary
  disruptions (node drains, upgrades).
- **PriorityClass `payflow-infrastructure`** for Postgres, Kafka and Redis. Every application
  waits for them, so on a full node they may preempt application pods. Without it, a rollout of
  all deployments at once left the restarted Postgres pod unschedulable while the new
  application pods waited for Postgres, a deadlock seen on the single-node cluster.
- Resources: apps request 200m CPU / 512Mi (384Mi in the local overlay) and are limited to 768Mi of memory, with no CPU limit
  so startup isn't throttled. The JVM sizes its heap from the limit
  (`-XX:MaxRAMPercentage=75`) and exits on `OutOfMemoryError`, so Kubernetes restarts it
  instead of leaving it half-broken.

## Configuration and secrets

Nothing in the code knows it is running in Kubernetes: the same jars run locally with
`application.yaml` defaults (localhost) and in the cluster with environment variables.

- **`payflow-env` ConfigMap** (shared): Kafka bootstrap servers, Redis host, OTLP endpoint, JWKS
  URL, issuer, partition count and replication factor.
- **Per Deployment**: datasource URL and user, gateway upstream URLs, signing-key location.
- **Secrets**, generated by Kustomize in the local overlay from files that
  `scripts/generate-local-secrets.sh` writes once into `k8s/overlays/local/secrets/`
  (git-ignored):

| Secret | Keys | Used by |
|---|---|---|
| `payflow-db` | `postgres-password`, `<service>-password` × 8 | Postgres init script and each service's datasource |
| `payflow-auth` | `admin-client-secret`, `grafana-admin-password`, `processor-api-key`, `reconciliation-client-secret`, `payout-client-secret` | merchant-service bootstrap admin client, Grafana, payment-, payout- and reconciliation-service → processor-simulator, the reconciliation's and payout-service's OAuth2 clients |
| `payflow-signing-key` | `signing-key.pem` (RSA 2048, PKCS#8) | merchant-service |

The passwords are random, and the script never changes an existing value (keys added in later
versions are appended). `postgres-init.sh` creates the databases and users; the image runs it only
on an empty volume, so `k8s-up.sh` re-runs it (it is idempotent) to add databases introduced
later, such as `processor_db`.
Kustomize adds a content hash to generated names (`payflow-db-tt856dcb4d`), so changing a secret
rolls the pods that use it.

A real environment would source these Secrets from a secret manager (External Secrets
Operator, Sealed Secrets, Vault) instead of local files.

## Pod security

- The images run as **non-root UID 1001** (numeric, so `runAsNonRoot: true` can be verified),
  with a `RuntimeDefault` seccomp profile.
- **Read-only root filesystem**, with an `emptyDir` on `/tmp` for the JVM and Tomcat.
- `allowPrivilegeEscalation: false`, all Linux capabilities dropped.
- The signing key is mounted read-only (`0440`, group 1001 via `fsGroup`).
- Prometheus has a **namespaced Role** (get/list/watch pods in `payflow`), not cluster-wide
  read access.
- Images: two-stage build that extracts the Spring Boot jar into layers (dependencies, loader,
  application), so a code change rebuilds only the small top layer. The runtime image is
  `eclipse-temurin:21-jre-alpine` (a JRE, no JDK or build tools).

## Observability

- **Metrics:** Prometheus discovers pods through the Kubernetes API (`kubernetes_sd_configs`) and
  scrapes those annotated `prometheus.io/scrape: "true"` at `prometheus.io/path` and
  `prometheus.io/port`. New and autoscaled replicas appear without config changes. Labels: `job`
  = service name, `pod` = pod name.
- **Dashboards:** Grafana is provisioned from the same files as the docker-compose stack
  (`infrastructure/docker/grafana/`), so the dashboard has one source. Those files are outside
  `k8s/`, which Kustomize only reads with `--load-restrictor LoadRestrictionsNone`. That's why
  `k8s-up.sh` renders with `kubectl kustomize … | kubectl apply -f -` instead of
  `kubectl apply -k`.
- **Traces:** every service, the gateway included, exports OTLP over HTTP to
  `http://jaeger:4318`. A payment is **one trace**: the request's `traceparent` is stored on the
  saga and on every outbox row and continued by the saga worker and both outbox relays, so the
  API call, processor calls, ledger commands and replies, and the event consumers all join it
  (see [SAGA.md](SAGA.md#observability)).

## Alerting

Prometheus evaluates the rules in `k8s/base/observability/alerts.yml` every 15 seconds and sends
firing alerts to **Alertmanager**. Alertmanager groups them per alert and service, waits 10s to
batch related alerts, and repeats an unresolved alert every 4 hours. While a whole service is
down, it suppresses that service's warnings. Resolved notifications are sent too.

- **Where notifications go.** By default to **alert-sink**, an in-cluster receiver that logs each
  one as a JSON line (`kubectl -n payflow logs deploy/alert-sink`); in production that would be a
  pager or chat integration. The Alertmanager UI is at `http://localhost:9093`, and Grafana shows
  firing alerts.
- **Slack (optional).** Put a Slack incoming-webhook URL in
  `k8s/overlays/local/secrets/slack-webhook-url` and run `scripts/k8s-up.sh`. It then deploys the
  `local-slack` overlay, and every alert also goes to Slack. Without that file, nothing leaves
  the cluster.
- **Tested rules.** `alerts.test.yml` checks every rule with `promtool test rules`: each fires
  when it should, after its `for` duration, with the right labels and summary, and stays silent
  otherwise. CI runs it, together with `amtool check-config` on the Alertmanager configuration.

| Alert | Severity | Fires when |
|---|---|---|
| `SagaRequiresAttention` | critical | A saga is parked for an operator (1m) |
| `SagaStepStuck` | warning | A saga step has run for over 15 minutes (5m) |
| `OutboxBacklog` | warning | The oldest unpublished outbox message is over 2 minutes old, or over 1000 are waiting (2m) |
| `OutboxMessageParked` | critical | An outbox message can never be published (1m) |
| `KafkaConsumerLag` | warning | A consumer group is over 500 messages behind on a topic (5m) |
| `DeadLettersArriving` | warning | New messages on any `.DLT` topic |
| `ProcessorCircuitOpen` | critical | The card processor circuit breaker is open (1m) |
| `KafkaUnreachable` | critical | kafka-exporter can't read the broker (2m) |
| `ServiceDown` | critical | A service has no healthy instance, including when it has no pods (2m) |
| `InstanceDown` | warning | One replica is down (2m); not kafka-exporter, which is `KafkaUnreachable` |
| `GatewayErrorRate` | warning | Over 5% of gateway responses are 5xx (5m) |
| `ReconciliationDiscrepancies`, `ReconciliationNotRun`, `ReconciliationRunFailed` | critical / warning | See [RECONCILIATION.md](RECONCILIATION.md#metrics-and-alerts) |
| `PayoutBatchNotRun`, `PayoutsFailing` | warning | See [PAYOUTS.md](PAYOUTS.md#metrics-and-alerts); parked or stuck payout sagas raise the saga alerts above (application payout-service) |

Two metrics sources were added for these alerts:
- **Backlog gauges** in payment-service and the ledger: `outbox_pending`, `outbox_failed`,
  `outbox_oldest_pending_age_seconds`, and in payment-service also
  `sagas_oldest_step_age_seconds`. They are read from the database, so every replica reports the
  same values.
- **kafka-exporter**, which reads consumer lag from the broker itself, so lag shows even when
  every consumer is dead.

### Verified on the cluster

Each alert in this table fired for a real failure on the local cluster, reached alert-sink, and
resolved on its own once the failure was fixed. The rest (`OutboxMessageParked`,
`DeadLettersArriving`, `GatewayErrorRate`, `ReconciliationNotRun`, `PayoutBatchNotRun`) are
covered by the promtool tests only:

| Scenario | Alerts (time after the failure began) |
|---|---|
| ledger-service scaled to 0 while 700 payments from 7 merchants settle | `ServiceDown` (3 min; `InstanceDown` suppressed by the inhibit rule), `KafkaConsumerLag` (3,500 behind on `ledger-commands`, 6 min), `SagaStepStuck` (21.5 min; the oldest step had been running 20m 11s) |
| Processor outage of 13 minutes, under 1 payment/s | `ProcessorCircuitOpen` (within 2 min of traffic arriving); resolved when the outage ended. Without traffic the breaker can't open: it needs 10 calls |
| A manual capture requested during the outage | Its outcome was unknown for 10 minutes, so the saga was parked: `SagaRequiresAttention`. After `POST …/saga/retry` the capture completed and the alert resolved |
| Kafka scaled to 0 while 20 payments were created | `OutboxBacklog` (4.8 min) and `KafkaUnreachable` (2.7 min); after Kafka returned, all 20 settled and both resolved |
| Reconciliation with injected corruption | See [RECONCILIATION.md](RECONCILIATION.md#on-the-cluster) |
| A payout transfer stuck in transit past its timeout | `SagaRequiresAttention` for application payout-service (the same rule as payment sagas); resolved after an operator retry ([PAYOUTS.md](PAYOUTS.md#testing)) |
| Three payouts to an invalid bank account | `PayoutsFailing` within a minute (after the counter fix below) |
| A manual reconciliation with the ledger scaled to 0 | The run failed; `ReconciliationRunFailed` within a minute (after the counter fix below) |

After the incident, the day's reconciliation (916 payments, including those failed or delayed
by the outages) matched every payment, with 0 discrepancies.

These runs exposed four problems, all fixed:
- **Counter-based alerts that couldn't see a first event.** Outcome counters were created on
  their first increment, so Prometheus first saw each series at 1, and `increase()` counts
  nothing for a series that starts with its event. `PayoutsFailing` stayed silent with three
  failed payouts, and `ReconciliationRunFailed` would have missed the first failed run. The
  counters are now registered at 0 on startup, and tests check that they exist. The promtool
  tests couldn't catch this: their series start at 0.
- **Stale consumer offsets.** `KafkaConsumerLag` kept firing for `ledger-service` on
  `payment-created`, a topic the ledger stopped consuming when the sagas arrived. Its old
  committed offsets were still there, so lag grew with every payment. The alert was right:
  committed offsets that nobody consumes look exactly like dead consumers. The offsets were
  deleted, and the runbook now covers the case.
- **No Kafka alert.** With Kafka down, the only signal was a generic warning that kafka-exporter
  was down. `KafkaUnreachable` now names the actual failure, and InstanceDown leaves the
  exporter out.
- **One reconciliation alert bug** (see RECONCILIATION.md).

### Runbooks

**SagaRequiresAttention**
1. `GET /api/v1/payments/{id}/saga` (operator token) shows where the saga stopped and why
   (`lastError`, step history).
2. Fix the cause: for a capture whose outcome was never learned, ask the processor; for a ledger
   rejection, compare the ledger's postings.
3. `POST /api/v1/payments/{id}/saga/retry` resumes the step under the same idempotency key.

**SagaStepStuck**
1. Check `ProcessorCircuitOpen` and `KafkaConsumerLag`. A step only waits on the processor or the
   ledger.
2. Restore the dependency; steps resume by themselves (retries with backoff, re-sent ledger
   commands).

**OutboxBacklog / OutboxMessageParked**
1. **Backlog:** Kafka is usually unreachable from the service. Check the Kafka pod and the
   service's logs. Messages are safe in the outbox and drain once Kafka is back.
2. **Parked:** the message can't be serialized or is too large. Find it with
   `SELECT * FROM outbox_event WHERE status = 'FAILED'`, fix or discard it, and later messages of
   that payment resume.

**KafkaConsumerLag**
1. Check that the group's pods are up. If they are, check their logs: an infrastructure error
   (the database down) holds the partition by design until it recovers.
2. If they're healthy but slow, scale the deployment. Parallelism is capped by the partition
   count (3).
3. If the topic has **no consumer at all** (`kafka-consumer-groups.sh --describe --group <group>`
   shows `-` as the consumer id), the group may have stopped reading that topic in a release
   and left its committed offsets behind. Lag there grows forever. Once you've confirmed the
   subscription was retired on purpose, delete them:
   `kafka-consumer-groups.sh --delete-offsets --group <group> --topic <topic>`.

**DeadLettersArriving**
1. Inspect the message:
   `kafka-console-consumer.sh --topic <topic> --from-beginning --property print.headers=true`.
   The exception is in the headers.
2. Fix the cause, then republish to the source topic or discard.

**ProcessorCircuitOpen**
1. Check processor-simulator (or the real processor's status page).
2. Payments wait in PROCESSING; those not authorized within 2 minutes are reversed and fail with
   `processor_unavailable`.
3. The breaker closes by itself after successful trial calls.

**KafkaUnreachable**
1. `kubectl -n payflow get pod kafka-0`, then `describe` and `logs`. If Kafka is running, check
   the kafka-exporter pod itself.
2. Nothing is lost meanwhile: services keep writing messages to their outboxes (expect
   `OutboxBacklog`), and the outboxes drain once the broker is back.

**ServiceDown / InstanceDown**
1. `kubectl -n payflow get pods -l app=<service>`, then `describe` and `logs`.
2. Typical causes: crash loop (bad config or secret), unschedulable (memory), or a failing
   startup probe (database unreachable).

**GatewayErrorRate**
1. See which route the 5xx come from (`http_server_requests_seconds_count{application="api-gateway"}`
   by `uri`).
2. A 502/503 means the upstream service is dropping connections or has no ready pods (see
   `ServiceDown`); a 500 is the service's own error (its logs).

## CI

`.github/workflows/ci.yml`, on pushes to `main` and on pull requests:

1. **test**: a matrix over the six modules, each running `mvn -B verify` on Temurin 21 with a
   Maven cache. The Testcontainers tests (Postgres, Redis) use the runner's Docker. Modules are
   reported independently (`fail-fast: false`), and surefire reports are uploaded on failure.
2. **images and manifests** (after tests pass): builds all six Docker images, renders
   `k8s/overlays/local` with throwaway secrets, and validates every resource against the
   Kubernetes 1.31 API schemas with **kubeconform** (`-strict`, so unknown fields fail too).

Images aren't pushed to a registry; publishing to GHCR and deploying from CI would be the next
step. notification-service's generated `contextLoads` test needs a live Postgres and Kafka, so
its pom excludes it.

## Design decisions and trade-offs

| Decision | Why | Trade-off |
|---|---|---|
| Spring Cloud Gateway as a separate service | Same stack as the services; JWT validation and Redis rate limiting are built in | One more hop and deployment (vs. an ingress controller with auth plugins) |
| Gateway checks authenticity only; services keep scopes and ownership | Each service's authorization rules stay in one place; nothing is trusted just because it came through the gateway | Each request is verified twice (cheap: local signature check, cached keys) |
| Gateway retries only idempotent methods, and only transport failures | Absorbs a pod dying mid-request without risking a duplicate write | A POST cut off by a dying pod returns 502; the client retries it with its `Idempotency-Key` |
| Rate limiter **fails open** when Redis is down | Losing a protective limit is better than rejecting all traffic; Redis isn't in the readiness check either | No limit while Redis is down (Lettuce logs reconnect warnings; responses then show `X-RateLimit-Remaining: -1`) |
| Anonymous callers keyed by peer address, not `X-Forwarded-For` | The header is attacker-controlled | Behind a proxy or NAT, many clients share one bucket. With Docker Desktop's LoadBalancer, all outside callers may appear from one address |
| One Postgres instance, six databases and users | Database-per-service isolation kept at a fraction of the memory | One failure domain; production would use one managed instance or HA cluster per service |
| One Kafka broker, replication factor 1 | Local footprint | No broker fault tolerance; production: 3+ brokers, RF 3, `min.insync.replicas` 2 (`PAYFLOW_KAFKA_REPLICATION_FACTOR`) |
| Redis without persistence | It holds rate-limit buckets and an idempotency cache (the database stays authoritative) | A restart resets the limits |
| Init containers wait for Postgres/Kafka | Avoids crash-loop backoff on a fresh cluster | Startup ordering in the manifests; the services still handle outages after startup on their own |
| Kustomize over Helm | Plain YAML plus patches, no templating language; built into kubectl | No packaging or versioned releases |
| Dev secrets from local generated files | Random per machine, never committed, no external dependency | Not a real secret store |
| Images tagged `:local` | No registry needed with Docker Desktop (the cluster uses the local image store) | Re-deploys need a rollout restart (the script does it); real environments use immutable tags |

## Testing

**Automated** (also what CI runs):

| Module | Tests | Notes |
|---|---|---|
| payment-service | 80 | including the saga scenarios (see SAGA.md) and the audit lookup |
| ledger-service | 38 | including the audit endpoints, outbox gauges and payout commands |
| notification-service | 34 | context-load test excluded |
| webhook-service | 128 | including payout webhooks |
| merchant-service | 32 | including `PemFileJwkSourceTest` and `PemSigningKeyIntegrationTest` (Testcontainers): the PEM key signs, its thumbprint is the `kid`, no database key is created; and the configured service clients |
| api-gateway | 17 | see below |
| processor-simulator | 27 | including the settlement report and bank transfers |
| reconciliation-service | 41 | see [RECONCILIATION.md](RECONCILIATION.md#testing) |
| payout-service | 36 | see [PAYOUTS.md](PAYOUTS.md#testing) |
| alert rules | every rule | `promtool test rules alerts.test.yml`; `amtool check-config` on both Alertmanager configurations |

The api-gateway tests run the real gateway (routes, security, rate limiter on a Redis
container) in front of a stub upstream HTTP server, with mocked JWT decoding:

- 401 with JSON body and `WWW-Authenticate` for missing and forged tokens, and the upstream is
  never called;
- a valid token is routed, and the upstream receives the `X-Request-Id`;
- a safe caller-supplied id is kept, an unsafe one is replaced;
- the token endpoint needs no bearer token;
- unknown paths get 403;
- burst 2 → the third request from a merchant gets 429 (with `X-Request-Id`, and never reaching
  the upstream) while another merchant still passes;
- a GET whose connection is dropped mid-request is retried and succeeds; the same failure on a
  POST is not retried and returns 502;
- a 500 the service itself returns is passed through and not retried;
- a route with no listening pod returns 503 in the JSON error shape;
- health is public.

`RateLimitKeyResolverTest` covers the bucket keys, including that `X-Forwarded-For` is ignored.

The upstream is deliberately a separate HTTP server. A `@RestController` inside the gateway app
would be matched by WebFlux's handler mapping *before* the gateway's route mapping, so requests
would never pass through the routes and their filters. An early version of the tests passed
security checks that way while the rate limiter never ran.

**Manifests:** the rendered overlays (`local`: 62 resources; `local-slack`: 63) pass kubeconform `-strict` against the
Kubernetes 1.31 schemas.

**On the cluster** (Docker Desktop Kubernetes 1.34, all traffic through `http://localhost`):

| Check | Result |
|---|---|
| Deploy from scratch (`scripts/k8s-up.sh`) | All 18 pods ready in about 60s with zero restarts (the init containers absorbed the startup order); Prometheus discovered all 12 application pods |
| End-to-end through the gateway | Operator token → onboard merchant → merchant token (`iss` = `http://localhost`) → webhook subscription → create / process / complete payment → ledger balance 125.50 via Kafka → `PAYMENT_CREATED` and `PAYMENT_COMPLETED` delivered to an in-cluster receiver, both HMAC signatures verified. A merchant calling `/process` gets 403 from payment-service |
| Edge authentication | 20 requests with missing or forged tokens: all 401 from the gateway; payment-service's own 401 counter stayed at 0, so none reached it |
| Distributed rate limit | 100 parallel requests from one merchant spread across both gateway replicas: 60 × 200 (burst 40 + refill), 40 × 429. Both replicas drew from one Redis bucket (their 429s split 23 / 17); another merchant's bucket was untouched |
| Partitions shared across replicas | `payment-created` has 3 partitions; in each consumer group (ledger, notification, webhook) the two pods own 2 and 1 of them |
| Outbox exactly once with 2 publishers | 30 concurrent payment lifecycles → 60 events in Kafka (30 created, 30 completed), 0 duplicate event ids, across all 3 partitions. 178 outbox rows published in that window, 119 by one pod and 59 by the other: the two replicas split the work and no row was published twice |
| Ledger consistency | 109 completed payments → 109 ledger transactions, none duplicated; debits − credits = 0.0000 per currency |
| Rolling restart under load | payment-service and api-gateway restarted together under 12 req/s of reads and creates: 429 requests, 0 failed |
| Shared signing key | A merchant-service pod deleted while issuing tokens: 30/30 tokens issued before, during and after; one `kid`; all 30 accepted by the API |
| Pod killed without graceful shutdown | Before the gateway retry: 1 of 362 requests failed (a GET in flight on the killed pod, reported as 500). After: 3 forced kills, about 1,085 requests, 0 failures |
| Autoscaling | 6 merchants at about 90 req/s: payment-service CPU at 211% of its target, gateway at 141%; both HPAs scaled from 2 to 4 pods; 9,341 responses, none failed |
| Observability | Grafana dashboard provisioned and showing data; Prometheus has every service's metrics; Jaeger traces as described in [Observability](#observability) |

Found and fixed during these runs: the gateway reported an upstream that dropped a connection,
or wasn't listening yet, as a generic 500. Hence the retry and the 502/503 mapping above.
After Docker Desktop restarts, the LoadBalancer ports on localhost come back a few seconds after
the pods do, and the services take about 20s to become ready again; requests during that window
get 503.

## File map

| Path | Contents |
|---|---|
| `api-gateway/` | Gateway: `security/SecurityConfig` (edge JWT rules, decoder), `security/JsonSecurityErrorHandler`, `filter/RateLimitKeyResolver`, `filter/RequestIdFilter`, `web/UpstreamErrorHandler` (502/503 mapping), `web/JsonErrorWriter`; routes, rate limit and retry in `application.yaml` |
| `*/Dockerfile`, `*/.dockerignore` | Layered images, non-root UID 1001 |
| `k8s/base/` | Namespace, shared ConfigMap, `apps/` (Deployment + Service + PDB per app, HPAs), `infra/` (Postgres + init script, Kafka, Redis), `observability/` (Prometheus + scrape config + RBAC, Grafana, Jaeger, Alertmanager, alert-sink, kafka-exporter) |
| `k8s/base/observability/alerts.yml`, `alerts.test.yml` | Alert rules and their `promtool` unit tests |
| `k8s/base/observability/alertmanager.yml`, `alert_sink.py` | Routing, inhibition and the in-cluster receiver |
| `k8s/overlays/local/` | Image tags, generated Secrets, LoadBalancer UIs, webhook dev settings |
| `k8s/overlays/local-slack/` | `local` plus a Slack receiver, used when `secrets/slack-webhook-url` exists |
| `payout-service/` | Payouts: `saga/` (state machine, orchestrator, worker), `batch/` (daily job, ShedLock), `payout/` (payouts, destinations), `client/` (bank, ledger), `web/` |
| `scripts/build-images.sh` | `mvn package` + `docker build` per service |
| `scripts/generate-local-secrets.sh` | Random passwords and the signing key, created once |
| `scripts/k8s-up.sh` / `k8s-down.sh` | Deploy and wait / delete the namespace |
| `.github/workflows/ci.yml` | Test matrix, image build, alert-rule tests, Alertmanager config check, manifest validation |
| `.gitattributes` | LF line endings for scripts mounted into Linux containers |
| `*/config/KafkaTopicsConfig.java` | Explicit topics with partition counts |
| `merchant-service/.../auth/PemFileJwkSource`, `SigningKeyConfig` | Signing key from a mounted PEM |
