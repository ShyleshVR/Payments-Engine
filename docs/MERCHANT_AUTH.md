# Merchant Identity & Auth

Every PayFlow API call is authenticated with a short-lived **OAuth2 access token (JWT)** issued
by `merchant-service`. The merchant a request acts for always comes from the verified token,
never from the request body or path, so a merchant can only see and change its own data.

- Authorization server: **merchant-service**, port **8084**, database `merchant_db`
  (container `merchant-postgres`, host port **55435**)
- Grant: **OAuth2 client credentials** (machine-to-machine; merchants' servers call PayFlow)
- Token: **RS256-signed JWT**, **15-minute** lifetime, no refresh tokens
- Resource servers: payment-service, ledger-service, webhook-service validate tokens **locally**
  against the published public keys (JWKS), with no call to merchant-service per request

```
merchant server ──client_id/secret──▶ merchant-service  POST /oauth2/token ──▶ JWT (15 min)
       │                                     │
       │                                     └── GET /oauth2/jwks (public keys, cached by services)
       ▼
 Authorization: Bearer <JWT> ──▶ payment / ledger / webhook services
                                  verify signature, exp, iss, aud locally → scope + merchant checks
```

## Contents

1. [Token model](#token-model)
2. [Scopes](#scopes)
3. [Onboarding and credential lifecycle](#onboarding-and-credential-lifecycle)
4. [How services enforce access](#how-services-enforce-access)
5. [API changes](#api-changes)
6. [Design decisions and trade-offs](#design-decisions-and-trade-offs)
7. [Configuration](#configuration)
8. [Observability](#observability)
9. [Running locally](#running-locally)
10. [Testing](#testing)
11. [Code map](#code-map)

---

## Token model

Request a token (HTTP Basic with the credential; `client_secret_post` also accepted):

```bash
curl -u "$CLIENT_ID:$CLIENT_SECRET" -d grant_type=client_credentials http://localhost:8084/oauth2/token
# {"access_token":"eyJ...","token_type":"Bearer","expires_in":899}
```

Claims of a merchant token:

| Claim | Value |
|---|---|
| `iss` | `http://localhost:8084` (validated by every service) |
| `sub` | the credential's `client_id` (`mch_…`) |
| `aud` | `["payflow-api"]` (validated: a token minted for anything else is rejected) |
| `merchant_id` | the merchant the token acts for; **absent on operator tokens** |
| `scope` | the granted scopes (all of the client's scopes unless it requests a subset) |
| `exp` / `iat` / `nbf` / `jti` | standard; 15-minute lifetime |

A client may request fewer scopes (`-d scope=payments:read`) for least privilege; asking for a
scope it doesn't have is rejected with `invalid_scope`.

## Scopes

| Scope | Granted to | Allows |
|---|---|---|
| `payments:write` | merchant | create, cancel, refund **own** payments |
| `payments:read` | merchant | read **own** payments |
| `ledger:read` | merchant | **own** balance; own payments' ledger transactions |
| `webhooks:manage` | merchant | **own** webhook subscription and delivery audit |
| `payments:operate` | operator | `process` / `complete` / `fail` **any** payment |
| `ledger:admin` | operator | platform-clearing balance; any merchant's balance and transactions |
| `merchants:admin` | operator | merchant and credential admin API |

`process`, `complete` and `fail` are processing outcomes, not merchant actions. A merchant can't
mark its own payment as paid; until a payment-processor integration drives these transitions,
they require the operator scope.

## Onboarding and credential lifecycle

A **bootstrap admin client** (`payflow.auth.bootstrap-admin.*`, from configuration) holds the
operator scopes and onboards merchants. Its secret must come from the environment
(`PAYFLOW_ADMIN_CLIENT_SECRET`) anywhere but local development.

Admin API (`merchants:admin`):

| Endpoint | Behaviour |
|---|---|
| `POST /api/v1/merchants` `{name, email}` | **201** with the merchant and its first credential: `clientId` (`mch_` + 16 hex) and `clientSecret` (`sk_` + 64 hex), **shown only in this response**. **409** for a duplicate email (case-insensitive). |
| `GET /api/v1/merchants/{merchantId}` | Merchant and its credentials (never secrets) |
| `POST /api/v1/merchants/{merchantId}/credentials` | **201** with a new credential (secret shown once). **409** if the merchant already has 2 active credentials, or is suspended. |
| `DELETE /api/v1/merchants/{merchantId}/credentials/{clientId}` | **204**. Revokes the credential (idempotent). |
| `POST /api/v1/merchants/{merchantId}/suspend` / `activate` | Suspended merchants can't obtain tokens |

**Zero-downtime rotation:** issue a second credential, switch the merchant's servers to it,
revoke the old one. Two active credentials per merchant is the limit, enforced under a row lock
on the merchant so concurrent requests can't exceed it.

**Secrets** are stored only as `{bcrypt}` hashes (Spring's `DelegatingPasswordEncoder`, so the
algorithm can change later). They can't be recovered; a lost secret means issuing a new credential.

**Revocation and suspension** take effect on the **next token request** (`invalid_client`). Tokens
already issued remain valid until they expire, at most 15 minutes later.

## How services enforce access

Each resource server (payment, ledger, webhook) has the same security setup:

1. **Signature and claims.** `NimbusJwtDecoder` with the JWKS from merchant-service, RS256 only,
   plus validators for `exp`/`nbf` (60s clock skew), `iss` and `aud = payflow-api`. Unsigned
   (`alg: none`) and tampered tokens fail here.
2. **Scope per endpoint.** `@PreAuthorize("hasAuthority('SCOPE_…')")` on each controller method.
3. **Merchant from the token.** A `@CurrentMerchant UUID merchantId` controller parameter is
   resolved from the `merchant_id` claim. An operator token on a merchant endpoint gets
   **403 "This endpoint requires a merchant token"**.
4. **Ownership in the data layer.** Queries include the merchant (`findByIdAndMerchantId`, …).
   Another merchant's payment is a **404**, exactly like a missing one, so ids can't be probed;
   collections (ledger transactions, webhook deliveries) are filtered and come back empty.

Errors keep the standard `{timestamp, status, message}` shape: **401** for a missing or invalid
token (with the standard `WWW-Authenticate: Bearer error="invalid_token", …` header), **403** for
a missing scope or merchant.

Public endpoints: `/actuator/health`, `/actuator/info`, `/actuator/prometheus` (and Swagger UI on
payment-service, which has a bearer-token button).

### Key caching and outages

Services fetch the JWKS on first use and cache it **without expiry**, so tokens keep validating
while merchant-service is down; only new token *issuance* stops. A token signed with a key id the
cache doesn't know triggers a refetch, which is how key rotation will propagate.

## API changes

Breaking changes for API clients:

| Service | Before | After |
|---|---|---|
| payment | `merchantId` in the create body | removed; taken from the token (a value in the body is ignored) |
| payment | anyone could call `process` / `complete` / `fail` | operator scope `payments:operate` |
| ledger | `GET /ledger/merchants/{merchantId}/balance` for merchants | merchants use `GET /ledger/balance?currency=`; the `{merchantId}` form is operator-only |
| ledger | `platform-clearing/balance` public | operator-only (`ledger:admin`) |
| webhook | `POST /webhooks/subscriptions {merchantId, url}` | `{url}`; merchant from the token |
| webhook | `GET` / `DELETE /webhooks/subscriptions/{merchantId}` | `GET` / `DELETE /webhooks/subscriptions` (own subscription) |
| all | unauthenticated | `Authorization: Bearer <JWT>` on every API call |

## Design decisions and trade-offs

| Decision | Why | Trade-off |
|---|---|---|
| OAuth2 client credentials + JWT, own authorization server (Spring Authorization Server) | Standard machine-to-machine flow (PayPal's API uses it); services verify tokens locally, so merchant-service isn't on the request path; the API gateway (next phase) can validate the same tokens at the edge | Merchants need a token request before API calls (vs. a static API key) |
| Tokens are **not stored** (`StatelessAuthorizationService`) | JWTs are self-contained and there's no introspection or refresh; Spring's default in-memory store would grow by one entry per token forever | A single token can't be revoked; revocation stops new tokens and existing ones expire within 15 min |
| Signing key from a **mounted PEM** (Kubernetes Secret) when `signing-key.location` is set, otherwise **in the database**, created on first start | Every replica signs with the same key; tokens survive restarts; the kid is the key's RFC 7638 thumbprint, so it is stable and identical on every replica | The database fallback stores the private key unencrypted (local runs only); the Secret is only as safe as the cluster's secret handling (production: a KMS or secret manager) |
| Clients read **live** from the merchant tables (`MerchantRegisteredClientRepository`) | Revocation and suspension apply to the very next token request | One DB lookup per token request |
| bcrypt for client secrets | Spring's default, versioned via `{id}` prefix | ~100ms per token request; fine at one token per merchant per 15 min |
| Scope defaults to all of the client's scopes when none requested | Spring grants an empty scope set in that case, which would produce useless tokens | Clients wanting least privilege must request a subset explicitly |
| JWKS cached without expiry, refetch on unknown kid | Tolerates merchant-service outages; still picks up rotated keys | A compromised, removed key stays trusted until services restart (acceptable before rotation is built) |
| Another merchant's resource → 404 | Doesn't reveal that the id exists | — |

## Configuration

merchant-service (`payflow.auth.*`):

| Property | Default | Notes |
|---|---|---|
| `issuer` | `http://localhost:8084` | `iss` claim; must match the resource servers' `issuer-uri` |
| `audience` | `payflow-api` | `aud` claim |
| `access-token-ttl` | `15m` | |
| `max-active-credentials` | `2` | Rotation without downtime |
| `bootstrap-admin.client-id` | `payflow-admin` | |
| `bootstrap-admin.client-secret` | `${PAYFLOW_ADMIN_CLIENT_SECRET:local-dev-admin-secret}` | Logs a warning when the local default is in use |
| `signing-key.location` | unset | Spring resource of a PKCS#8 PEM RSA private key (e.g. `file:/etc/payflow/signing-key/signing-key.pem`). Set: that key signs and the database key is never created. Unset: database key. A PKCS#1 file (`BEGIN RSA PRIVATE KEY`) is rejected at startup with the `openssl pkcs8` command to convert it. |

Resource servers (`spring.security.oauth2.resourceserver.jwt.*`): `jwk-set-uri`
(`http://localhost:8084/oauth2/jwks`), `issuer-uri` (`http://localhost:8084`), `audiences`
(`payflow-api`).

**On Kubernetes** (see [DEPLOYMENT.md](DEPLOYMENT.md)) the issuer is the public URL clients use,
`http://localhost` (the API gateway), on merchant-service and on every resource server, while
the keys are fetched over cluster DNS from `http://merchant-service:8084/oauth2/jwks`. Resource
servers validate `iss` as a string and never call the issuer URL, so the two can differ. The
signing key comes from the `payflow-signing-key` Secret.

## Observability

| Metric | Where | Meaning |
|---|---|---|
| `auth.token.requests{outcome=issued\|invalid_client\|rejected}` | merchant-service | Token endpoint outcomes (the endpoint is a security filter, so the standard HTTP metric only sees `uri=UNKNOWN`) |
| `http.server.requests{status="401"\|"403"}` | every service | Rejected API calls |

Grafana dashboard "PayFlow Overview" has an auth row with both.

## Running locally

```bash
# database (from infrastructure/docker)
docker compose up -d postgres-merchant

# merchant-service (generates its signing key on first start)
cd merchant-service && mvn package -DskipTests
java -jar target/merchant-service-0.0.1-SNAPSHOT.jar

# 1. operator token
ADMIN=$(curl -s -u payflow-admin:local-dev-admin-secret -d grant_type=client_credentials \
  localhost:8084/oauth2/token | jq -r .access_token)

# 2. onboard a merchant (keep the clientSecret: it is shown once)
curl -s -X POST localhost:8084/api/v1/merchants -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d '{"name":"Acme","email":"ops@acme.example"}'

# 3. merchant token, then call any API
TOKEN=$(curl -s -u "$CLIENT_ID:$CLIENT_SECRET" -d grant_type=client_credentials \
  localhost:8084/oauth2/token | jq -r .access_token)
curl -s -X POST localhost:8080/api/v1/payments -H "Authorization: Bearer $TOKEN" \
  -H 'Idempotency-Key: order-1' -H 'Content-Type: application/json' -d '{"amount":10.00,"currency":"USD"}'
```

## Testing

**Automated**

- merchant-service: unit tests for onboarding (secret returned once, stored as `{bcrypt}`),
  the 2-active-credential limit, suspension and revocation, and the live client lookup; plus
  **integration tests running the real authorization server against Postgres via
  Testcontainers**: token claims and TTL, scope subsets and `invalid_scope`, `invalid_client`,
  401/403 on the admin API, revocation, suspension, rotation, JWKS publishing only the public
  key, duplicate email, token metrics.
- payment, ledger and webhook services: `@WebMvcTest` with the real `SecurityConfig` and test
  JWTs (`spring-security-test`): 401 without a token, 403 for a missing scope or an operator token
  on merchant endpoints, merchant taken from the token even when the body says otherwise,
  operator-only payment transitions, another merchant's resources hidden.

**Manual end-to-end** (live stack: all five services, Kafka, Postgres; token TTL set to 30s
for the run):

| Scenario | Result |
|---|---|
| Onboard two merchants via the admin API | `mch_` / `sk_` credentials; secret stored only as a bcrypt hash; merchant token has its `merchant_id` and exactly the 4 merchant scopes; admin token has the operator scopes and no merchant |
| Merchant A creates a payment, body claims merchant B | Payment stored under A |
| B reads / cancels A's payment | 404 |
| A calls `/process` or `/complete` | 403; the operator token succeeds |
| Ledger | A's balance 120.0000, B's 0; merchants get 403 on platform-clearing and other merchants; operator reads all; payment transactions visible to A and the operator, not B |
| Webhooks | A subscribes with its token, delivery signed and verified; B sees neither A's subscription nor its deliveries |
| Forged tokens | Flipped signature byte, swapped `merchant_id`, RS256 token signed by an unknown key, `alg: none`, garbage: all 401 on every service, with `WWW-Authenticate: Bearer` |
| Expiry | Token accepted before expiry, 401 "expired" after exp + 60s clock skew |
| Revocation | Revoked credential gets `invalid_client`; a token issued before revocation keeps working until it expires |
| Rotation | Second active credential 201, third 409 |
| Suspension | Suspended merchant gets no tokens; reactivated merchant does |
| merchant-service down | Existing tokens still accepted by payment, ledger and webhook (cached keys); forged tokens still rejected; token endpoint unreachable |
| merchant-service restart | Same signing key reloaded from the database (no new key); tokens issued before the restart still valid; new tokens accepted everywhere |
| Grafana | Auth panels show token requests by outcome and 401/403 by service |

## Code map

`merchant-service/src/main/java/com/shylesh/merchant_service/`

| Package | Contents |
|---|---|
| `auth` | `MerchantRegisteredClientRepository` (live client lookup), `PayflowTokenCustomizer` (claims), `StatelessAuthorizationService`, `SigningKeyConfig` (chooses the key source), `PemFileJwkSource` (key from a mounted PEM), `DatabaseJwkSource` (persisted key, local runs) |
| `config` | `SecurityConfig` (authorization-server and admin-API filter chains, password encoder, decoder), `AuthProperties`, `TokenEndpointMetricsConfig` |
| `service` | `MerchantAdminServiceImpl` (onboarding, credential issue/revoke under row lock, suspend/activate), `CredentialGenerator` |
| `controller` / `dto` / `exception` | Admin API, request/response types, error mapping |
| `security` | `Scopes`, `JsonSecurityErrorHandler` |

In each resource server, `security/`: `SecurityConfig` (filter chain + `JwtDecoder`), `Scopes`,
`CurrentMerchant` + `CurrentMerchantArgumentResolver`, `JsonSecurityErrorHandler`.
