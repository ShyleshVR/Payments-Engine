package com.shylesh.payout_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;

/**
 * @param delay         funds availability: only money settled at least this long ago is paid out
 *                      (recent settlements stay in the balance, where refunds can draw on them)
 * @param minimumAmount the batch skips payable balances below this
 * @param batch         when the daily batch runs
 * @param bank          the bank's transfer API (in processor-simulator)
 * @param ledger        the ledger's API (payable balances)
 */
@ConfigurationProperties(prefix = "payflow.payouts")
public record PayoutProperties(
        @DefaultValue("P2D") Duration delay,
        @DefaultValue("1.00") BigDecimal minimumAmount,
        @DefaultValue Batch batch,
        Endpoint bank,
        Endpoint ledger
) {

    /**
     * @param cron              the daily run, UTC
     * @param time              the time of day after which a missed run is caught up
     * @param catchUpInterval   how often a missed run is looked for (also once soon after startup)
     */
    public record Batch(
            @DefaultValue("0 0 4 * * *") String cron,
            @DefaultValue("04:00") LocalTime time,
            @DefaultValue("PT1H") Duration catchUpInterval
    ) {
    }

    public record Endpoint(String baseUrl, String apiKey) {
    }
}
