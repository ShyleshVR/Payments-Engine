package com.shylesh.ledger_service.dto;

import com.shylesh.ledger_service.persistence.LedgerDirection;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Builder
public class LedgerEntryResponse {

    private UUID accountId;
    private LedgerDirection direction;
    private BigDecimal amount;
    private String currency;
}
