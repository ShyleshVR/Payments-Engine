package com.shylesh.ledger_service.controller;

import com.shylesh.ledger_service.dto.LedgerTransactionPage;
import com.shylesh.ledger_service.dto.LedgerTransactionSummary;
import com.shylesh.ledger_service.dto.LedgerTransactionResponse;
import com.shylesh.ledger_service.persistence.LedgerEntryRepository.PayableBalance;
import com.shylesh.ledger_service.persistence.LedgerTransactionRepository;
import com.shylesh.ledger_service.service.LedgerQueryService;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Read-only views across all merchants, for operators, the daily reconciliation and the payout
 * batch: every transaction in a period, refund and payout holds that never completed, and payable
 * balances.
 */
@RestController
@RequestMapping("/api/v1/ledger")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority(T(com.shylesh.ledger_service.security.Scopes).LEDGER_ADMIN)")
public class LedgerAuditController {

    static final int MAX_PAGE_SIZE = 1000;
    static final Duration MAX_PERIOD = Duration.ofDays(3);

    private final LedgerTransactionRepository transactionRepository;
    private final LedgerQueryService queryService;

    /** Transactions created in [from, to), oldest first, a page at a time. */
    @GetMapping("/transactions")
    @Transactional(readOnly = true)
    public LedgerTransactionPage transactions(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "500") int size) {
        if (!to.isAfter(from) || Duration.between(from, to).compareTo(MAX_PERIOD) > 0) {
            throw new ResponseStatusException(BAD_REQUEST, "to must be after from, at most " + MAX_PERIOD.toDays() + " days later");
        }
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(BAD_REQUEST, "page >= 0 and 1 <= size <= " + MAX_PAGE_SIZE);
        }
        Slice<LedgerTransactionSummary> slice = transactionRepository.findSummariesBetween(from, to, PageRequest.of(page, size));
        return new LedgerTransactionPage(slice.getContent(), page, size, slice.hasNext());
    }

    /** Refund holds created before the cutoff that were neither released nor finalized. */
    @GetMapping("/refund-holds/open")
    @Transactional(readOnly = true)
    public List<LedgerTransactionSummary> openRefundHolds(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdBefore) {
        return transactionRepository.findOpenRefundHoldsCreatedBefore(createdBefore);
    }

    /** Payout holds created before the cutoff that were neither released nor paid out. */
    @GetMapping("/payout-holds/open")
    @Transactional(readOnly = true)
    public List<LedgerTransactionSummary> openPayoutHolds(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdBefore) {
        return transactionRepository.findOpenPayoutHoldsCreatedBefore(createdBefore);
    }

    /**
     * Merchant balances with at least the minimum payable: the balance minus settlements at or
     * after the cutoff. The payout batch pays these.
     */
    @GetMapping("/payable-balances")
    public List<PayableBalance> payableBalances(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cutoff,
                                                @RequestParam(defaultValue = "0.01") BigDecimal minimum) {
        if (minimum.signum() <= 0) {
            throw new ResponseStatusException(BAD_REQUEST, "minimum must be positive");
        }
        return queryService.getPayableBalances(cutoff, minimum);
    }

    /** One merchant's payable balance in a currency. */
    @GetMapping("/merchants/{merchantId}/payable")
    public PayableView payable(@PathVariable UUID merchantId, @RequestParam String currency,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cutoff) {
        return new PayableView(merchantId, currency, queryService.getPayable(merchantId, currency, cutoff));
    }

    public record PayableView(UUID merchantId, String currency, BigDecimal payable) {
    }

    /** A payout's postings: hold, then release, or payout and maybe a return. */
    @GetMapping("/payouts/{payoutId}/transactions")
    public List<LedgerTransactionResponse> payoutTransactions(@PathVariable UUID payoutId) {
        return queryService.getTransactionsForPayout(payoutId);
    }
}
