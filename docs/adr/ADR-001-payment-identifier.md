# ADR-001: Payment Identifier Strategy

## Status

Accepted

## Context

The payment system requires globally unique identifiers that remain unique across
distributed deployments and are safe to expose through APIs.

The following options were evaluated:

1. Database Auto Increment
2. UUIDv4
3. UUIDv7
4. ULID

## Decision

Use UUIDv7 as the internal identifier for Payment entities.

## Rationale

Database auto-increment IDs were rejected because:

- Predictable identifiers expose enumeration risks.
- Difficult to merge data from multiple database instances.
- Not suitable for future distributed deployments.

UUIDv4 was rejected because:

- Completely random values reduce PostgreSQL index locality.
- Large-scale inserts lead to increased B-Tree fragmentation.

ULID was considered but rejected because:

- Lower adoption within Java ecosystems.
- Additional ecosystem maturity concerns.

UUIDv7 was selected because it:

- Provides globally unique identifiers.
- Preserves chronological ordering.
- Improves PostgreSQL index performance.
- Supports future horizontal scaling.
- Aligns with modern distributed system design.

## Consequences

Future services can generate identifiers independently without coordination.

Payment identifiers remain globally unique across regions and deployments.