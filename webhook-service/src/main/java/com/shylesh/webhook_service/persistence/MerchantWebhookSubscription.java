package com.shylesh.webhook_service.persistence;

import jakarta.persistence.*;
import lombok.*;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One active subscription per merchant for V1; every payment event type is delivered to
 * it (no per-type filtering yet). Deactivation is a soft delete so past deliveries keep
 * pointing at a real row.
 */
@Entity
@Table(name = "merchant_webhook_subscriptions")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class MerchantWebhookSubscription {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(nullable = false, length = 2048, updatable = false)
    private String url;

    @Column(nullable = false, length = 128, updatable = false)
    private String secret;

    @Column(nullable = false)
    private boolean active;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void deactivate() {
        this.active = false;
    }
}
