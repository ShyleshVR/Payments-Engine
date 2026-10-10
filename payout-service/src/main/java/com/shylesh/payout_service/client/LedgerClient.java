package com.shylesh.payout_service.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** The ledger's read API: what each merchant can be paid out (as of a cutoff). */
@Component
public class LedgerClient {

    private final RestClient restClient;

    public LedgerClient(@Qualifier("ledgerRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public record PayableBalance(UUID merchantId, String currency, BigDecimal payable) {
    }

    /** Every merchant balance with at least the minimum payable: settlements before the cutoff, minus what left since. */
    public List<PayableBalance> payableBalances(LocalDateTime cutoff, BigDecimal minimum) {
        List<PayableBalance> balances = restClient.get()
                .uri(uri -> uri.path("/api/v1/ledger/payable-balances")
                        .queryParam("cutoff", cutoff)
                        .queryParam("minimum", minimum.toPlainString())
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        return balances == null ? List.of() : balances;
    }

    public BigDecimal payable(UUID merchantId, String currency, LocalDateTime cutoff) {
        PayableBalance balance = restClient.get()
                .uri(uri -> uri.path("/api/v1/ledger/merchants/{merchantId}/payable")
                        .queryParam("currency", currency)
                        .queryParam("cutoff", cutoff)
                        .build(merchantId))
                .retrieve()
                .body(PayableBalance.class);
        return balance == null ? BigDecimal.ZERO : balance.payable();
    }
}
