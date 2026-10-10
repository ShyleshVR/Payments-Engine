package com.shylesh.payout_service.payout;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/** Where a merchant's payouts go: a bank account token from the merchant's bank. */
@Entity
@Table(name = "payout_destination")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayoutDestination {

    @Id
    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "bank_account", nullable = false, length = 64)
    private String bankAccount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void change(String bankAccount, LocalDateTime now) {
        this.bankAccount = bankAccount;
        this.updatedAt = now;
    }
}
