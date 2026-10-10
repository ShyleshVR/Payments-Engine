package com.shylesh.reconciliation_service.persistence;

import com.shylesh.reconciliation_service.domain.DiscrepancyType;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

/** Something the processor, the ledger and payment-service disagree on. */
@Entity
@Table(name = "reconciliation_discrepancy")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReconciliationDiscrepancy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 40)
    private DiscrepancyType type;

    @Column(name = "payment_id", updatable = false)
    private UUID paymentId;

    @Column(name = "payout_id", updatable = false)
    private UUID payoutId;

    @Column(name = "processor_amount", updatable = false, precision = 19, scale = 4)
    private BigDecimal processorAmount;

    @Column(name = "ledger_amount", updatable = false, precision = 19, scale = 4)
    private BigDecimal ledgerAmount;

    @Column(name = "payment_status", updatable = false, length = 30)
    private String paymentStatus;

    @Column(updatable = false, length = 1000)
    private String detail;
}
