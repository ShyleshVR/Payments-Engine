package com.shylesh.merchant_service.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "merchants")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Merchant {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 320)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MerchantStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public boolean isActive() {
        return status == MerchantStatus.ACTIVE;
    }

    public void suspend(LocalDateTime now) {
        this.status = MerchantStatus.SUSPENDED;
        this.updatedAt = now;
    }

    public void activate(LocalDateTime now) {
        this.status = MerchantStatus.ACTIVE;
        this.updatedAt = now;
    }
}
