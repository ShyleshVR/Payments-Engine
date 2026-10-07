package com.shylesh.webhook_service.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "merchant_webhook_subscriptions")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MerchantWebhookSubscription {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(nullable = false, updatable = false, length = 2048)
    private String url;

    @Column(nullable = false, updatable = false, length = 128)
    private String secret;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "deactivated_at")
    private LocalDateTime deactivatedAt;

    public void deactivate(LocalDateTime now) {
        this.active = false;
        this.deactivatedAt = now;
    }
}
