package com.shylesh.webhook_service.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * payout-service's payout event data (amount parsed straight from the JSON text: exact). Fields
 * this consumer doesn't use (transferId, ...) are ignored, so the producer can add fields freely.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class PayoutEventData {

    private UUID payoutId;

    private UUID merchantId;

    private BigDecimal amount;

    private String currency;

    /** PENDING, IN_TRANSIT, PAID, FAILED, RETURNED */
    private String status;

    /** BATCH or INSTANT */
    private String trigger;

    /** FAILED / RETURNED: why (e.g. account_closed, account_frozen). */
    private String failureCode;
}
