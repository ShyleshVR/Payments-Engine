package com.shylesh.ledger_service.dto;

import java.util.List;

public record LedgerTransactionPage(List<LedgerTransactionSummary> items, int page, int size, boolean hasNext) {
}
