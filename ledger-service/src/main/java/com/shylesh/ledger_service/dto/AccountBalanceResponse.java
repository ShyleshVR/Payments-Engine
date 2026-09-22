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
    private BigDecimal balance;
}
