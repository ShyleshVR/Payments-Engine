package com.shylesh.webhook_service.http;

import lombok.Getter;

/**
 * A single POST to a merchant endpoint did not succeed. responseCode is the HTTP status the
 * merchant returned, or null when no response was received at all (timeout, DNS, refused).
 * Every failure is treated as retryable for V1; splitting permanent (e.g. 410 Gone) from
 * transient failures is a later refinement.
 */
@Getter
public class WebhookDeliveryException extends Exception {

    private final Integer responseCode;

    public WebhookDeliveryException(String message, Integer responseCode) {
        super(message);
        this.responseCode = responseCode;
    }

    public WebhookDeliveryException(String message, Throwable cause) {
        super(message, cause);
        this.responseCode = null;
    }
}
