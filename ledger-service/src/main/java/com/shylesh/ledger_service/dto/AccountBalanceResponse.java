package com.shylesh.ledger_service.dto;

import com.shylesh.ledger_service.persistence.LedgerAccountType;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Builder
public class AccountBalanceResponse {

    private LedgerAccountType ownerType;
    private UUID ownerId;
    private String currency;
    /** Available balance (for a merchant: what refunds can be paid from). */
    private BigDecimal balance;

    /** Merchant only: refunds held while the processor refund is pending (null for other owners). */
    private BigDecimal reserved;
}
