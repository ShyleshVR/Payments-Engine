package com.shylesh.ledger_service.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "ledger_transactions")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LedgerTransaction {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    /** The payment posted for (null for a payout's postings). */
    @Column(name = "payment_id", updatable = false)
    private UUID paymentId;

    /** The payout posted for (null for a payment's postings). */
    @Column(name = "payout_id", updatable = false)
    private UUID payoutId;

    /** The saga this posting belongs to (null for postings made before sagas). */
    @Column(name = "saga_id", updatable = false)
    private UUID sagaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private LedgerTransactionType type;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
