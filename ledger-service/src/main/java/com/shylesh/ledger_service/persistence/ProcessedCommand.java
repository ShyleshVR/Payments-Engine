package com.shylesh.ledger_service.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/** A command already handled, with the reply it got (sent again for a duplicate). */
@Entity
@Table(name = "processed_commands")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessedCommand {

    @Id
    @Column(name = "command_id", nullable = false, updatable = false)
    private UUID commandId;

    @Column(name = "command_type", nullable = false, updatable = false, length = 40)
    private String commandType;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    /** The reply data as JSON. */
    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String reply;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private LocalDateTime processedAt;
}
