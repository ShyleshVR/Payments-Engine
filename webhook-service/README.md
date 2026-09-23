# webhook-service

Delivers signed HTTP webhooks to merchants for payment lifecycle events.

Port **8083** · DB `webhook_db` on `localhost:55434` · consumes `payment-created` (group `webhook-service`) · dead letters to `webhook-deliveries.DLT`

## Flow

```
payment-created ──► PaymentEventConsumer ──► WebhookEventService
                     (idempotent via processed_events)
                                                 │ merchant has an active subscription?
                                                 ▼
                                    webhook_deliveries (PENDING, payload rendered once)
                                                 │
            WebhookDispatcher (every 5s) ──► WebhookDeliveryService
                                                 │ sign + POST (3s connect / 10s read timeout)
                              2xx ──► DELIVERED  │  non-2xx / network error
                                                 ▼
                     RETRYING (30s base, x2, 8min cap, ±20% jitter) ── after 5 attempts ──► FAILED + DLT
```

A subscription deactivated while a delivery is still pending turns that delivery into `CANCELLED` without calling the merchant.

## Merchant contract

`POST <subscription url>` with `Content-Type: application/json`:

```json
{
  "payloadVersion": "1",
  "eventId": "…",
  "eventType": "PAYMENT_COMPLETED",
  "paymentId": "…",
  "merchantId": "…",
  "amount": 200.00,
  "currency": "USD",
  "occurredAt": "2026-09-23T04:12:14.461Z"
}
```

- `payloadVersion` is a string. A breaking change ships as a new version merchants opt into.
- Delivery is **at-least-once**, so merchants should dedupe on `eventId`.
- Any 2xx counts as success. Any other status, or no response, is retried.

### Verifying the signature

Header: `X-Webhook-Signature: sha256=<hex HMAC-SHA256(secret, rawBody)>`

Compute the HMAC over the **raw request bytes** before any JSON parsing. Compare in constant time:

```java
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(rawBody));
boolean valid = MessageDigest.isEqual(expected.getBytes(UTF_8), header.getBytes(UTF_8));
```

## API (unauthenticated for this phase)

| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/webhooks/subscriptions` | `{merchantId, url}` → 201 with `secret` (shown once). 409 if already subscribed. |
| GET | `/api/v1/webhooks/subscriptions/{merchantId}` | Active subscription, no secret. |
| DELETE | `/api/v1/webhooks/subscriptions/{merchantId}` | Soft delete → 204. |
| GET | `/api/v1/webhooks/deliveries/payment/{paymentId}` | Audit view: deliveries and their attempts. |

## Metrics

`webhooks.delivered`, `webhooks.retried`, `webhooks.dlt` (tagged by `eventType`) and the `webhooks.delivery.latency` timer. They appear in the "Webhooks" row of the PayFlow Overview dashboard.

## Deliberately deferred

- **Auth on the subscription API** is the next roadmap item (merchant identity/auth), ahead of Kubernetes + API gateway.
- **Secret rotation** waits until after full microservice onboarding. For now, rotate by deleting and re-creating the subscription.
- **Per-event-type filtering**: V1 delivers every event type.
- **Multi-instance safety**: the dispatcher assumes a single replica. SKIP LOCKED/ShedLock arrives with the Kubernetes phase.
- **Permanent vs transient failures**: every failure is retried for now, including 4xx.
