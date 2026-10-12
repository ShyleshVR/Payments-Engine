package com.shylesh.reconciliation_service.client;

import com.shylesh.reconciliation_service.domain.Records;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Reads the systems through their APIs (never their databases): the processor's settlement
 * report (card payments and payout transfers), the ledger's transactions and open holds, and
 * payment-service's and payout-service's view of the payments and payouts concerned.
 *
 * A period's records are never returned as one list: a busy day has millions. They are read a
 * page at a time and each page is handed to the caller, so memory stays at one page.
 */
@Component
public class SourceClient {

    static final int PAYMENT_LOOKUP_BATCH = 1000;
    private static final String PUBLIC_PAYMENT_PREFIX = "pay_";
    private static final String PUBLIC_PAYOUT_PREFIX = "po_";

    private final RestClient processor;
    private final RestClient ledger;
    private final RestClient payments;
    private final RestClient payouts;

    public SourceClient(@Qualifier("processorRestClient") RestClient processor,
                        @Qualifier("ledgerRestClient") RestClient ledger,
                        @Qualifier("paymentsRestClient") RestClient payments,
                        @Qualifier("payoutsRestClient") RestClient payouts) {
        this.processor = processor;
        this.ledger = ledger;
        this.payments = payments;
        this.payouts = payouts;
    }

    // wire formats of the APIs
    record ReportAuthorization(String id, String reference, String status, BigDecimal amount, String currency,
                               BigDecimal capturedAmount, BigDecimal refundedAmount, LocalDateTime createdAt,
                               LocalDateTime capturedAt, LocalDateTime voidedAt) {
    }

    record ReportRefund(String id, String authorizationId, String reference, BigDecimal amount, String currency,
                        LocalDateTime createdAt) {
    }

    record ReportTransfer(String id, String reference, String status, BigDecimal amount, String currency,
                          String failureCode, LocalDateTime createdAt, LocalDateTime paidAt, LocalDateTime failedAt,
                          LocalDateTime returnedAt) {
    }

    record LedgerTransaction(UUID transactionId, UUID paymentId, UUID payoutId, UUID sagaId, String type, BigDecimal amount,
                             String currency, LocalDateTime createdAt) {
    }

    /** @param next sent back as "after" to get the following page; null on the last page */
    record Page<T>(List<T> items, String next) {
    }

    record PayoutView(String payoutId, String status, BigDecimal amount, String currency, boolean inFlight) {
    }

    record PaymentView(String paymentId, String status, BigDecimal amount, String currency, boolean sagaActive,
                       boolean processorBacked) {
    }

    /** Authorizations created, captured or voided in [from, to), each once. */
    public void forEachAuthorizationPage(LocalDateTime from, LocalDateTime to, int pageSize,
                                         Consumer<List<Records.Authorization>> consumer) {
        forEachPage(processor, "/v1/reports/settlement/authorizations", "limit", from, to, pageSize,
                new ParameterizedTypeReference<Page<ReportAuthorization>>() {
                },
                a -> new Records.Authorization(a.id(), paymentId(a.reference()), a.status(),
                        a.capturedAt() != null ? a.capturedAmount() : a.amount(), a.currency(), a.refundedAmount(),
                        a.createdAt(), a.capturedAt(), a.voidedAt()),
                consumer);
    }

    public void forEachRefundPage(LocalDateTime from, LocalDateTime to, int pageSize, Consumer<List<Records.Refund>> consumer) {
        forEachPage(processor, "/v1/reports/settlement/refunds", "limit", from, to, pageSize,
                new ParameterizedTypeReference<Page<ReportRefund>>() {
                },
                r -> new Records.Refund(r.id(), paymentId(r.reference()), r.amount(), r.currency(), r.createdAt()),
                consumer);
    }

    public void forEachTransferPage(LocalDateTime from, LocalDateTime to, int pageSize, Consumer<List<Records.Transfer>> consumer) {
        forEachPage(processor, "/v1/reports/settlement/transfers", "limit", from, to, pageSize,
                new ParameterizedTypeReference<Page<ReportTransfer>>() {
                },
                t -> new Records.Transfer(t.id(), payoutId(t.reference()), t.status(), t.amount(), t.currency(),
                        t.failureCode(), t.createdAt(), t.paidAt(), t.failedAt(), t.returnedAt()),
                consumer);
    }

    public void forEachLedgerPage(LocalDateTime from, LocalDateTime to, int pageSize,
                                  Consumer<List<Records.LedgerTransaction>> consumer) {
        forEachPage(ledger, "/api/v1/ledger/transactions", "size", from, to, pageSize,
                new ParameterizedTypeReference<Page<LedgerTransaction>>() {
                },
                SourceClient::toRecord, consumer);
    }

    /** Follows the "next" cursors of a paged listing to its end, handing over each page that has rows. */
    private <W, R> void forEachPage(RestClient client, String path, String sizeParameter, LocalDateTime from, LocalDateTime to,
                                    int pageSize, ParameterizedTypeReference<Page<W>> type, Function<W, R> toRecord,
                                    Consumer<List<R>> consumer) {
        String after = null;
        while (true) {
            String cursor = after;
            Page<W> page = client.get()
                    .uri(u -> {
                        UriBuilder uri = u.path(path).queryParam("from", from).queryParam("to", to)
                                .queryParam(sizeParameter, pageSize);
                        return (cursor == null ? uri : uri.queryParam("after", cursor)).build();
                    })
                    .retrieve()
                    .body(type);
            if (page == null || page.items() == null) {
                return;
            }
            if (!page.items().isEmpty()) {
                consumer.accept(page.items().stream().map(toRecord).toList());
            }
            if (page.next() == null) {
                return;
            }
            if (page.next().equals(after)) {
                // would otherwise read the same page for ever
                throw new IllegalStateException(path + " returned the cursor it was given");
            }
            after = page.next();
        }
    }

    public List<Records.LedgerTransaction> openRefundHolds(LocalDateTime createdBefore) {
        return openHolds("/api/v1/ledger/refund-holds/open", createdBefore);
    }

    public List<Records.LedgerTransaction> openPayoutHolds(LocalDateTime createdBefore) {
        return openHolds("/api/v1/ledger/payout-holds/open", createdBefore);
    }

    private List<Records.LedgerTransaction> openHolds(String path, LocalDateTime createdBefore) {
        List<LedgerTransaction> holds = ledger.get()
                .uri(u -> u.path(path).queryParam("createdBefore", createdBefore).build())
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

    public Map<UUID, Records.Payout> payouts(Collection<UUID> payoutIds) {
        Map<UUID, Records.Payout> found = new HashMap<>();
        List<UUID> ids = new ArrayList<>(payoutIds);
        for (int start = 0; start < ids.size(); start += PAYMENT_LOOKUP_BATCH) {
            List<String> batch = ids.subList(start, Math.min(start + PAYMENT_LOOKUP_BATCH, ids.size())).stream()
                    .map(id -> PUBLIC_PAYOUT_PREFIX + id).toList();
            List<PayoutView> views = payouts.post()
                    .uri("/api/v1/payouts/lookup")
                    .body(Map.of("payoutIds", batch))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (views != null) {
                views.forEach(v -> {
                    UUID id = payoutId(v.payoutId());
                    found.put(id, new Records.Payout(id, v.status(), v.amount(), v.currency(), v.inFlight()));
                });
            }
        }
        return found;
    }

    private static Records.LedgerTransaction toRecord(LedgerTransaction t) {
        return new Records.LedgerTransaction(t.transactionId(), t.paymentId(), t.payoutId(), t.sagaId(), t.type(), t.amount(),
                t.currency(), t.createdAt());
    }

    /** "po_<uuid>" -> uuid; anything else -> null. */
    static UUID payoutId(String reference) {
        if (reference == null || !reference.startsWith(PUBLIC_PAYOUT_PREFIX)) {
            return null;
        }
        try {
            return UUID.fromString(reference.substring(PUBLIC_PAYOUT_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
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
