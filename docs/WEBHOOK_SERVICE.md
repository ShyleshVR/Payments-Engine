# Webhook Service

Notifies merchants' servers when their payments change state. Each event reaches the
merchant **at least once**, is **signed**, and is never silently dropped: a delivery either
succeeds, is retried, or is recorded as failed and dead-lettered.

- Port: **8083**
- Database: `webhook_db` (container `webhook-postgres`, host port **55434**)
- Consumes: Kafka topic `payment-created` (consumer group `webhook-service`)
- Produces: `webhook-deliveries.DLT` (deliveries that failed for good), `payment-created.DLT` (unprocessable messages)

```
payment-service ──outbox──▶ Kafka "payment-created" ──▶ webhook-service ──HTTPS POST──▶ merchant server
                                                         │
                                                         ├─ webhook_db (subscriptions, deliveries, attempts)
                                                         └─ Kafka "webhook-deliveries.DLT" (failed for good)
```

## Contents

1. [Data model](#data-model)
2. [Event intake](#event-intake)
3. [Dispatching](#dispatching)
4. [Delivering one webhook](#delivering-one-webhook)
5. [Dead letters](#dead-letters)
6. [Merchant contract](#merchant-contract)
7. [API](#api)
8. [Security](#security)
9. [Configuration](#configuration)
10. [Observability](#observability)
11. [Guarantees and known limits](#guarantees-and-known-limits)
12. [Running locally](#running-locally)
13. [Testing](#testing)
14. [Related changes in other services](#related-changes-in-other-services)
15. [Code map](#code-map)

---

## Data model

Migrations: [webhook-service/src/main/resources/db/migration/](../webhook-service/src/main/resources/db/migration/)

| Table | Purpose | Key points |
|---|---|---|
| `merchant_webhook_subscriptions` | Where to send a merchant's events, and the signing secret | One **active** row per merchant, enforced by a partial unique index. Unsubscribing keeps the row (`active = false`, `deactivated_at` set) as an audit trail for its deliveries. |
| `webhook_deliveries` | One row per (event, merchant); the unit of work | `UNIQUE (event_id, merchant_id)`. `payload` stores the exact JSON body, rendered once, so every retry sends identical bytes. Also holds `status`, `attempt_count`, `next_attempt_at`, `last_error`, `version` (optimistic lock) and `dlt_published_at`. |
| `webhook_delivery_attempts` | One row per HTTP attempt | `response_code` (null when no response was received), `error_message`, `duration_ms`, `attempted_at`. |
| `processed_events` | Each Kafka event is handled once | Same pattern as ledger-service and notification-service. |

Delivery statuses:

| Status | Meaning |
|---|---|
| `PENDING` | Created, not yet attempted |
| `RETRYING` | At least one retryable failure; waiting for `next_attempt_at` |
| `DELIVERED` | Merchant answered 2xx |
| `FAILED` | Retries exhausted, or a permanent failure. Dead-lettered. |
| `CANCELLED` | Subscription was deleted before the delivery went out. Not dead-lettered. |

```
             ┌──────────── retryable, attempts left ───────────┐
             ▼                                                 │
PENDING ──claim──▶ (sending) ──2xx──────────────────────▶ DELIVERED
   │                  │   └──retryable, attempts left──▶ RETRYING ──claim──▶ (sending) …
   │                  └──permanent, or retries exhausted──▶ FAILED ──relay──▶ webhook-deliveries.DLT
   └──subscription deleted (PENDING or RETRYING)──▶ CANCELLED
```

---

## Event intake

[PaymentEventConsumer](../webhook-service/src/main/java/com/shylesh/webhook_service/consumer/PaymentEventConsumer.java)
→ [PaymentEventParser](../webhook-service/src/main/java/com/shylesh/webhook_service/event/PaymentEventParser.java)
→ [WebhookEventServiceImpl](../webhook-service/src/main/java/com/shylesh/webhook_service/service/impl/WebhookEventServiceImpl.java)

1. **Parse and validate before any database work.** Malformed JSON, or a missing `eventId`,
   `eventType`, `data.paymentId`, `data.merchantId`, `data.amount` or `data.currency`, raises
   `InvalidEventException`. `data` is deserialized straight into a typed class so amounts are
   built as `BigDecimal` from the JSON text and never pass through `double`.
2. **In one transaction:**
   - already processed → skip;
   - event type not delivered as a webhook → mark processed, stop
     (delivered types: `PAYMENT_CREATED`, `PAYMENT_COMPLETED`, `PAYMENT_FAILED`, `PAYMENT_REFUNDED`);
   - merchant has no active subscription → mark processed, stop;
   - otherwise render the payload once, insert a `PENDING` delivery, and mark the event processed.

The delivery row and the processed marker commit together, so an event is either fully
accepted or redelivered by Kafka and handled again.

### Consumer error handling

[KafkaConsumerConfig](../webhook-service/src/main/java/com/shylesh/webhook_service/config/KafkaConsumerConfig.java),
[ConsumerFailureClassifier](../webhook-service/src/main/java/com/shylesh/webhook_service/config/ConsumerFailureClassifier.java)

| Failure | Behaviour |
|---|---|
| `InvalidEventException` (bad message) | Sent to `payment-created.DLT` **immediately**; retrying can't fix it |
| Infrastructure down (database unreachable, connection pool timeout, query timeout…) | Retried with backoff (1s, doubling, capped at 60s) **for as long as the outage lasts**. The partition waits, so no valid event is skipped, and consumer lag makes the outage visible. |
| Anything else | 4 quick retries (1s doubling, 10s cap), then `payment-created.DLT` |

Each backoff step (≤ 60s) plus a database connection timeout (30s) stays under Kafka's
`max.poll.interval.ms` (5 min), so retrying never gets the consumer evicted from its group.

---

## Dispatching

[WebhookDispatcher](../webhook-service/src/main/java/com/shylesh/webhook_service/dispatch/WebhookDispatcher.java)
polls every 5s for due deliveries (`PENDING` or `RETRYING` with `next_attempt_at <= now`) and
hands them to a worker pool **without waiting for them to finish**.

- A slow merchant endpoint ties up one worker; the next poll still runs on schedule and other
  merchants' deliveries keep flowing.
- At most `max-in-flight` (100) deliveries are queued or running per instance, and a delivery
  already in flight is never submitted twice.
- Sends run on `workers` (8) threads. The scheduler has its own pool of 3 threads, so the
  dispatcher and the DLT relay never starve each other.

---

## Delivering one webhook

[WebhookDeliveryServiceImpl](../webhook-service/src/main/java/com/shylesh/webhook_service/service/impl/WebhookDeliveryServiceImpl.java)

```
1. CLAIM  (short tx)  SELECT … FOR UPDATE SKIP LOCKED, only if still due
                      subscription inactive? → CANCELLED, stop
                      lease: next_attempt_at = now + 2 min → commit
2. SEND   (no tx)     re-check the target address → sign → POST
3. RECORD (short tx)  reload; stale (cancelled, or another instance took over)? → discard
                      insert the attempt row, update the status → commit
```

No transaction is open during the HTTP call, so database connections aren't held while a
merchant responds. **Two instances can't send the same attempt**, for three reasons:

- `SKIP LOCKED` keeps instances off a row another one is claiming.
- The lease hides a claimed delivery from other dispatchers while its request runs.
- The optimistic-lock `version`, together with the stale-outcome check, discards a result if
  another instance took over.

If an instance crashes mid-send, the lease expires after 2 minutes and the delivery is retried.

### What each response means

[HttpOutcome](../webhook-service/src/main/java/com/shylesh/webhook_service/http/HttpOutcome.java)

| Merchant response | Result | Status |
|---|---|---|
| 2xx | Success | `DELIVERED` |
| 5xx, 408, 429, timeout, connection refused, DNS failure | Retryable | `RETRYING`, or `FAILED` when attempts are used up |
| Other 4xx; 3xx (redirects are not followed) | Permanent | `FAILED` immediately |
| Target now resolves to a private address, or the URL is no longer allowed by policy | Permanent | `FAILED`, never sent |
| Unexpected exception | Retryable | Recorded as an attempt; never a silent loop |

### Retry schedule

[WebhookRetryPolicy](../webhook-service/src/main/java/com/shylesh/webhook_service/retry/WebhookRetryPolicy.java)

Five attempts. The gaps between them are about 30s, 60s, 120s and 240s, each with ±20% jitter
and capped at 8 minutes. A delivery that keeps failing becomes `FAILED` about 7.5 minutes after
the first try. Jitter stops many deliveries to a recovering merchant from retrying at the same
instant.

### HTTP client

[WebhookHttpClient](../webhook-service/src/main/java/com/shylesh/webhook_service/http/WebhookHttpClient.java),
[WebhookHttpConfig](../webhook-service/src/main/java/com/shylesh/webhook_service/config/WebhookHttpConfig.java)

JDK `HttpClient`: connect timeout 3s, read timeout 10s, HTTP/1.1, redirects never followed.
It reads at most 512 bytes of the merchant's response body (enough to explain a failure) and
never throws: every outcome becomes a recorded attempt.

---

## Dead letters

[WebhookDeadLetterRelay](../webhook-service/src/main/java/com/shylesh/webhook_service/dlt/WebhookDeadLetterRelay.java),
[WebhookDeadLetterPublisher](../webhook-service/src/main/java/com/shylesh/webhook_service/dlt/WebhookDeadLetterPublisher.java)

Every 5s the relay locks `FAILED` deliveries that haven't been dead-lettered yet
(`SKIP LOCKED`), publishes each to `webhook-deliveries.DLT`, waits for Kafka's
acknowledgement, and only then sets `dlt_published_at`. As a result:

- a `FAILED` status that rolled back is never dead-lettered;
- a dead letter is never lost while Kafka is down; it is retried on the next poll;
- the producer's own `delivery.timeout.ms` (10s) is shorter than the relay's wait (15s), so a
  send treated as failed has really been dropped and won't arrive later as a duplicate.

Dead letter body (key: `merchantId`):

```json
{
  "eventId": "…", "deliveryId": "…", "paymentId": "…", "merchantId": "…",
  "eventType": "PAYMENT_COMPLETED", "url": "https://merchant.example/hooks",
  "attemptCount": 5, "lastError": "HTTP 503: maintenance", "failedAt": "2026-10-05T09:24:11"
}
```

Delivery is at-least-once, so DLT consumers should deduplicate on `deliveryId`. `CANCELLED`
deliveries are not dead-lettered.

---

## Merchant contract

This is the public interface merchants integrate against.

### Request

```
POST <merchant url>
Content-Type: application/json
User-Agent: PayFlow-Webhooks/1.0
X-Webhook-Timestamp: 1791190000
X-Webhook-Signature: sha256=<hex(HMAC-SHA256(secret, "1791190000." + rawBody))>

{"payloadVersion":"1","eventId":"…","eventType":"PAYMENT_COMPLETED",
 "paymentId":"pay_…","merchantId":"…","amount":200.00,"currency":"USD",
 "occurredAt":"2026-10-05T08:30:15Z"}
```

| Field | Notes |
|---|---|
| `payloadVersion` | String (`"1"`), so it can move to `"1.1"` or `"2"` without implying numeric ordering. A breaking change ships as a new version merchants opt into. |
| `eventId` | Unique per event; use it to deduplicate |
| `eventType` | `PAYMENT_CREATED`, `PAYMENT_COMPLETED`, `PAYMENT_FAILED`, `PAYMENT_REFUNDED` |
| `paymentId` | Public `pay_` form, the same identifier the payment API returns |
| `amount` | Exact decimal with its scale preserved (`75.50`, not `75.5`) |
| `occurredAt` | ISO-8601 UTC instant |

The body is rendered once when the delivery is created; every retry sends the same bytes. The
timestamp and signature are recomputed for each attempt.

### What merchants must do

1. **Verify the signature** over the **raw** body bytes, compare in constant time, and reject
   timestamps older than a few minutes. The timestamp is part of the signed content, so a
   captured request can't be replayed later.
2. **Deduplicate on `eventId`.** Delivery is at-least-once.
3. **Don't assume ordering** between events. Use `occurredAt`, or fetch the payment.
4. **Return 2xx quickly.** Do slow work asynchronously. A 4xx is treated as final; 5xx and
   timeouts are retried.

Verification example (Python):

```python
import hashlib, hmac, time

def verify(secret: str, timestamp: str, signature: str, raw_body: bytes, tolerance_s: int = 300) -> bool:
    if abs(time.time() - int(timestamp)) > tolerance_s:
        return False
    expected = "sha256=" + hmac.new(secret.encode(), timestamp.encode() + b"." + raw_body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, signature)
```

---

## API

[WebhookSubscriptionController](../webhook-service/src/main/java/com/shylesh/webhook_service/controller/WebhookSubscriptionController.java),
[WebhookDeliveryController](../webhook-service/src/main/java/com/shylesh/webhook_service/controller/WebhookDeliveryController.java)

Every call needs a merchant access token with the `webhooks:manage` scope
(see [MERCHANT_AUTH.md](MERCHANT_AUTH.md)). The merchant always comes from the token: there is no
`merchantId` in the body or path, and a merchant can only see its own subscription and deliveries.

| Endpoint | Behaviour |
|---|---|
| `POST /api/v1/webhooks/subscriptions` with `{"url"}` | Validates the URL. **201** with the `secret` (`whsec_` + 64 hex chars), **shown only in this response**. **409** if the merchant already has an active subscription; **400** for a bad or unsafe URL. |
| `GET /api/v1/webhooks/subscriptions` | The caller's active subscription, without the secret, or **404** |
| `DELETE /api/v1/webhooks/subscriptions` | **204**. Deactivates the subscription and **cancels pending deliveries** in the same transaction. **404** if none is active. |
| `GET /api/v1/webhooks/deliveries/payment/{paymentId}` | Audit trail of the caller's deliveries and attempts for a payment. Accepts `pay_<uuid>` or a raw UUID. Another merchant's payment yields an empty list. |

To change a URL, delete the subscription and create a new one. Missing or invalid tokens get
**401**, a missing scope or an operator token without a merchant gets **403**.

Errors use the same shape as payment-service:

```json
{ "timestamp": "2026-10-05T09:20:11.123", "status": 400, "message": "Webhook URL must use https" }
```

---

## Security

### SSRF protection

[WebhookTargetValidator](../webhook-service/src/main/java/com/shylesh/webhook_service/security/WebhookTargetValidator.java)

The service POSTs to whatever URL it is given, so without a check anyone could make it call
internal services or cloud metadata endpoints. Rejected targets:

- loopback, private (10/8, 172.16/12, 192.168/16), link-local (including cloud metadata at
  169.254.169.254), carrier-grade NAT (100.64/10), 0.0.0.0/8, IPv6 unique-local (fc00::/7),
  multicast;
- URLs with credentials (`user:pass@`) or a fragment;
- schemes other than http/https, and plain http unless `require-https` is off.

The check runs **when the subscription is created** and **again before every send**, in case
the merchant's DNS record changes later (DNS rebinding). Redirects are never followed, because
a redirect could point at an address the check never saw.

### Secrets

32 bytes from `SecureRandom`, formatted `whsec_<64 hex>`, returned once at creation. Stored in
plain text for now; encryption at rest is deferred together with secret rotation.

---

## Configuration

[WebhookProperties](../webhook-service/src/main/java/com/shylesh/webhook_service/config/WebhookProperties.java),
[application.yaml](../webhook-service/src/main/resources/application.yaml)

| Property | Default | Notes |
|---|---|---|
| `webhook.delivery.poll-interval` | `PT5S` | Dispatcher poll interval |
| `webhook.delivery.max-in-flight` | `100` | Per instance |
| `webhook.delivery.workers` | `8` | Parallel sends |
| `webhook.delivery.connect-timeout` | `3s` | |
| `webhook.delivery.read-timeout` | `10s` | |
| `webhook.delivery.lease` | `2m` | Startup **fails** if this is less than 2 × (connect + read) timeout, since a shorter lease could let another instance re-send a request still in flight |
| `webhook.retry.max-attempts` | `5` | |
| `webhook.retry.base-delay` | `30s` | Doubles per attempt, ±20% jitter |
| `webhook.retry.max-delay` | `8m` | |
| `webhook.security.require-https` | `true` | Set `false` only for local development |
| `webhook.security.allow-private-targets` | `false` | Set `true` only for local development |
| `spring.task.scheduling.pool.size` | `3` | Dispatcher and DLT relay don't share one thread |
| `spring.datasource.hikari.maximum-pool-size` | `15` | Workers, relay and consumer each hold a connection briefly |

---

## Observability

**Metrics** (Prometheus at `/actuator/prometheus`; Grafana dashboard "PayFlow Overview", webhook row):

| Metric | Tags | Meaning |
|---|---|---|
| `webhooks.delivered` | `eventType` | Delivered (2xx) |
| `webhooks.retried` | `eventType` | Retryable failure, will retry |
| `webhooks.failed` | `eventType`, `reason` (`permanent` / `exhausted`) | Moved to `FAILED` |
| `webhooks.dlt` | `eventType` | Dead letter acknowledged by Kafka |
| `webhooks.delivery.latency` | none | Delivery creation to delivered |

**Tracing:** OpenTelemetry via Micrometer, exported to Jaeger, same as the other services.

**Logs:** every state change is logged with `deliveryId`, `eventId` and `merchantId`.

---

## Guarantees and known limits

**Guaranteed**

- At-least-once delivery to the merchant.
- No double send across instances (`SKIP LOCKED` + lease + optimistic version).
- No event lost to a database or Kafka outage.
- Every attempt is recorded with its response code, error and duration.
- Exact amounts (no floating point anywhere on the path).

**Not guaranteed (documented)**

- **Ordering per payment.** Deliveries are sent in parallel and retried independently, so a
  payment's events can arrive out of order; Stripe has the same model. Per-payment ordering
  is possible but would let one failing delivery hold back that payment's later events.
- **More consumers than partitions.** `payment-created` has 3 partitions, declared explicitly by
  payment-service together with `payment-created.DLT` (same count, because the dead-letter
  recoverer keeps the source partition number); webhook-service declares
  `webhook-deliveries.DLT`. Up to 3 instances consume; any further instances only deliver.
- **DNS rebinding.** A small window remains between the address check and the HTTP client's
  own lookup. Closing it fully needs a resolver hook in the client or an egress proxy.
- **Secret rotation and encryption.** Deferred.

---

## Running locally

```bash
# database (from infrastructure/docker)
docker compose up -d postgres-webhook

# service: dev settings allow plain http and localhost targets (never use these in production)
cd webhook-service
mvn package -DskipTests
java -jar target/webhook-service-0.0.1-SNAPSHOT.jar \
  --webhook.security.require-https=false \
  --webhook.security.allow-private-targets=true \
  --webhook.retry.base-delay=2s --webhook.retry.max-delay=8s   # optional: faster retries for testing
```

Requires Kafka (`localhost:9092`) and payment-service publishing to `payment-created`.

Quick check:

```bash
# TOKEN: a merchant access token (see MERCHANT_AUTH.md)
curl -s -X POST localhost:8083/api/v1/webhooks/subscriptions -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"url":"http://127.0.0.1:9101/hooks"}'
# then create a payment with the same token and watch:
curl -s localhost:8083/api/v1/webhooks/deliveries/payment/pay_<uuid> -H "Authorization: Bearer $TOKEN"
```

---

## Testing

**Unit tests:** `mvn test` in `webhook-service` (123 tests). Highlights:

- signature matches an independently computed HMAC test vector, and the timestamp is part of
  the signed content;
- the HTTP client is exercised against a real local HTTP server: status classification,
  timeouts, connection refused, large error bodies;
- the delivery service: success, retry, permanent failure, exhaustion, cancellation, stale
  outcomes, blocked targets, and no transaction open during the send;
- the dispatcher: parallel sends, no double submission, in-flight cap;
- the consumer: failure classification and backoff, and amounts kept exact
  (`12345678901234567.89`, `75.50`).

**Manual end-to-end verification** (live Kafka, Postgres, payment, ledger and notification
services, plus a test merchant endpoint that verifies signatures):

| Scenario | Result |
|---|---|
| All four event types | Delivered; every signature verified; timestamps fresh; payload fields as specified |
| 500, 500, then 200 | Delivered on attempt 3 with identical bodies |
| 400 / 302 | `FAILED` after 1 attempt, dead-lettered once; redirect not followed |
| Five 500s | `FAILED` after 5 attempts, dead-lettered once |
| Slow merchant (timeout) | Another merchant delivered 5s later while the slow request was in flight |
| Unsubscribe with a retry pending | Delivery `CANCELLED`; in-flight outcome discarded; nothing more sent |
| Webhook database down 2.5 minutes | Event delivered after recovery; **not** dead-lettered |
| Malformed message / null `eventId` | Dead-lettered immediately with `InvalidEventException`; partition kept flowing |
| Two instances, 300 deliveries due at once | 300 requests for 300 distinct events; work split across both instances; no double sends |
| Production security defaults | Internal, loopback, metadata, plain-http and credential URLs rejected; blocked targets never contacted |
| Grafana | All webhook panel queries return data |

---

## Related changes in other services

Made alongside this service:

- **payment-service emits `PAYMENT_FAILED`** from the `/fail` transition (via the outbox), so all
  four planned event types exist. Ledger records it as processed without posting; notification
  sends its email rule.
- **payment-service amount precision fix.** The Kafka publisher re-parsed the outbox payload with
  `readTree`, which turned decimals into doubles and stripped trailing zeros. Every consumer
  received `75.5` for `75.50`, and large amounts could lose cents. It now keeps amounts exact.
  Ledger and notification still parse amounts through `double` on their side; that fix is in
  the backlog.
- **Infrastructure:** `postgres-webhook` in `docker-compose.yml`, a Prometheus scrape target for
  8083, and a webhook row on the Grafana dashboard.

---

## Code map

`webhook-service/src/main/java/com/shylesh/webhook_service/`

| Package | Contents |
|---|---|
| `consumer` | `PaymentEventConsumer`: Kafka listener |
| `event` | Envelope and payload types, `PaymentEventParser` (validation), `InvalidEventException` |
| `service` / `service.impl` | `WebhookEventServiceImpl` (intake), `WebhookDeliveryServiceImpl` (claim/send/record), `WebhookSubscriptionServiceImpl`, `WebhookDeliveryQueryServiceImpl` |
| `dispatch` | `WebhookDispatcher`: polling and worker hand-off |
| `http` | `WebhookHttpClient`, `HttpOutcome` (response classification) |
| `signing` | `WebhookSigner`: HMAC signatures and secret generation |
| `payload` | `WebhookPayload` (merchant contract), `WebhookPayloadFactory` |
| `security` | `WebhookTargetValidator`: SSRF checks |
| `retry` | `WebhookRetryPolicy` |
| `dlt` | `WebhookDeadLetterRelay`, `WebhookDeadLetterPublisher`, dead letter event and topic |
| `persistence` | Entities, statuses, repositories (including the `SKIP LOCKED` claim queries) |
| `controller` / `dto` / `exception` | REST API, request/response types, error mapping |
| `config` | Properties, HTTP client and worker pool, Kafka error handling, JPA auditing |
