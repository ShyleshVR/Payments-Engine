package com.shylesh.payout_service.saga;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/** One entry of a saga's audit trail: a step outcome (held, accepted, paid, timed out, ...). */
@Entity
@Table(name = "payout_saga_step")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayoutSagaStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "saga_id", nullable = false, updatable = false)
    private UUID sagaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 40)
    private SagaState state;

    @Column(nullable = false, updatable = false, length = 40)
    private String outcome;

    @Column(length = 1000, updatable = false)
    private String detail;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;
}
