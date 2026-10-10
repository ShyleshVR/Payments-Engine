package com.shylesh.reconciliation_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param cron                 when the previous day is reconciled (UTC)
 * @param catchUpDays          days back that are reconciled if they have no completed run
 *                             (missed or failed runs; checked hourly and at startup)
 * @param matchingMargin       how far past either end of the day a counterpart may be (a capture at
 *                             23:59 settled at 00:01); a day is reconciled only once this has passed
 * @param authorizationGrace   how long a failed payment's authorization may stay open
 * @param refundHoldStaleAfter how long a ledger refund hold may stay open
 * @param payoutHoldStaleAfter how long a ledger payout hold may stay open (a transfer takes days)
 * @param payoutReturnGrace    how long a payout returned by the bank may wait for the payout
 *                             saga to notice and book it
 */
@ConfigurationProperties(prefix = "payflow.reconciliation")
public record ReconciliationProperties(
        @DefaultValue("0 0 2 * * *") String cron,
        @DefaultValue("3") int catchUpDays,
        @DefaultValue("1h") Duration matchingMargin,
        @DefaultValue("1h") Duration authorizationGrace,
        @DefaultValue("1h") Duration refundHoldStaleAfter,
        @DefaultValue("4d") Duration payoutHoldStaleAfter,
        @DefaultValue("2h") Duration payoutReturnGrace,
        Endpoint processor,
        Endpoint ledger,
        Endpoint payments,
        Endpoint payouts
) {

    /** @param apiKey processor only (the ledger and payment APIs take OAuth2 tokens) */
    public record Endpoint(String baseUrl, String apiKey) {
    }
}
