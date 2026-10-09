package com.shylesh.reconciliation_service.client;

import com.shylesh.reconciliation_service.domain.Records;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads the three systems through their APIs (never their databases): the processor's
 * settlement report, the ledger's transactions and open refund holds, and payment-service's
 * view of the payments concerned.
 */
@Component
public class SourceClient {

    static final int LEDGER_PAGE_SIZE = 1000;
    static final int PAYMENT_LOOKUP_BATCH = 1000;
    private static final String PUBLIC_PAYMENT_PREFIX = "pay_";

    private final RestClient processor;
    private final RestClient ledger;
    private final RestClient payments;

    public SourceClient(@Qualifier("processorRestClient") RestClient processor,
                        @Qualifier("ledgerRestClient") RestClient ledger,
                        @Qualifier("paymentsRestClient") RestClient payments) {
        this.processor = processor;
        this.ledger = ledger;
        this.payments = payments;
    }

    // wire formats of the three APIs
    record ReportAuthorization(String id, String reference, String status, BigDecimal amount, String currency,
                               BigDecimal capturedAmount, BigDecimal refundedAmount, LocalDateTime createdAt,
                               LocalDateTime capturedAt, LocalDateTime voidedAt) {
    }

    record ReportRefund(String id, String authorizationId, String reference, BigDecimal amount, String currency,
                        LocalDateTime createdAt) {
    }

    record SettlementReport(List<ReportAuthorization> authorizations, List<ReportRefund> refunds) {
    }

    record LedgerTransaction(UUID transactionId, UUID paymentId, UUID sagaId, String type, BigDecimal amount,
                             String currency, LocalDateTime createdAt) {
    }

    record LedgerPage(List<LedgerTransaction> items, boolean hasNext) {
    }

    record PaymentView(String paymentId, String status, BigDecimal amount, String currency, boolean sagaActive,
                       boolean processorBacked) {
    }

    public record ProcessorRecords(List<Records.Authorization> authorizations, List<Records.Refund> refunds) {
    }

    public ProcessorRecords processorReport(LocalDateTime from, LocalDateTime to) {
        SettlementReport report = processor.get()
                .uri(u -> u.path("/v1/reports/settlement").queryParam("from", from).queryParam("to", to).build())
                .retrieve()
                .body(SettlementReport.class);
        if (report == null) {
            return new ProcessorRecords(List.of(), List.of());
        }
        return new ProcessorRecords(
                report.authorizations().stream().map(a -> new Records.Authorization(a.id(), paymentId(a.reference()), a.status(),
                        a.capturedAt() != null ? a.capturedAmount() : a.amount(), a.currency(), a.refundedAmount(), a.createdAt(), a.capturedAt(), a.voidedAt())).toList(),
                report.refunds().stream().map(r -> new Records.Refund(r.id(), paymentId(r.reference()), r.amount(),
                        r.currency(), r.createdAt())).toList());
    }

    public List<Records.LedgerTransaction> ledgerTransactions(LocalDateTime from, LocalDateTime to) {
        List<Records.LedgerTransaction> all = new ArrayList<>();
        for (int page = 0; ; page++) {
            int current = page;
            LedgerPage result = ledger.get()
                    .uri(u -> u.path("/api/v1/ledger/transactions").queryParam("from", from).queryParam("to", to)
                            .queryParam("page", current).queryParam("size", LEDGER_PAGE_SIZE).build())
                    .retrieve()
                    .body(LedgerPage.class);
            if (result == null) {
                return all;
            }
            result.items().forEach(t -> all.add(toRecord(t)));
            if (!result.hasNext()) {
                return all;
            }
        }
    }

    public List<Records.LedgerTransaction> openRefundHolds(LocalDateTime createdBefore) {
        List<LedgerTransaction> holds = ledger.get()
                .uri(u -> u.path("/api/v1/ledger/refund-holds/open").queryParam("createdBefore", createdBefore).build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        return holds == null ? List.of() : holds.stream().map(SourceClient::toRecord).toList();
    }

    public Map<UUID, Records.Payment> payments(Collection<UUID> paymentIds) {
        Map<UUID, Records.Payment> found = new HashMap<>();
        List<UUID> ids = new ArrayList<>(paymentIds);
        for (int start = 0; start < ids.size(); start += PAYMENT_LOOKUP_BATCH) {
            List<String> batch = ids.subList(start, Math.min(start + PAYMENT_LOOKUP_BATCH, ids.size())).stream()
                    .map(id -> PUBLIC_PAYMENT_PREFIX + id).toList();
            List<PaymentView> views = payments.post()
                    .uri("/api/v1/payments/lookup")
                    .body(Map.of("paymentIds", batch))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (views != null) {
                views.forEach(v -> {
                    UUID id = paymentId(v.paymentId());
                    found.put(id, new Records.Payment(id, v.status(), v.amount(), v.currency(), v.sagaActive(),
                            v.processorBacked()));
                });
            }
        }
        return found;
    }

    private static Records.LedgerTransaction toRecord(LedgerTransaction t) {
        return new Records.LedgerTransaction(t.transactionId(), t.paymentId(), t.sagaId(), t.type(), t.amount(),
                t.currency(), t.createdAt());
    }

    /** "pay_<uuid>" -> uuid; anything else (a foreign reference) -> null. */
    static UUID paymentId(String reference) {
        if (reference == null || !reference.startsWith(PUBLIC_PAYMENT_PREFIX)) {
            return null;
        }
        try {
            return UUID.fromString(reference.substring(PUBLIC_PAYMENT_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
