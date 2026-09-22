package com.shylesh.ledger_service.dto;

import com.shylesh.ledger_service.persistence.LedgerTransactionType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
public class LedgerTransactionResponse {

    private UUID transactionId;
    private UUID paymentId;
    private LedgerTransactionType type;
    private LocalDateTime createdAt;
    private List<LedgerEntryResponse> entries;
}
