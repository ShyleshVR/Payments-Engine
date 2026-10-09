package com.shylesh.processor_simulator.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "refunds")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Refund {

    @Id
    @Column(nullable = false, updatable = false, length = 40)
    private String id;

    @Column(name = "authorization_id", nullable = false, updatable = false, length = 40)
    private String authorizationId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
