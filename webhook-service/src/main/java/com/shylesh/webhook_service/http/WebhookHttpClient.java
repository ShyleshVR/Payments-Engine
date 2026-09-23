package com.shylesh.webhook_service.http;

import com.shylesh.webhook_service.signing.WebhookSigner;

import lombok.RequiredArgsConstructor;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;

/**
 * Thin wrapper over RestClient that turns "anything other than a 2xx" into a
 * WebhookDeliveryException, so the delivery service only deals with one failure type.
 */
@Component
@RequiredArgsConstructor
public class WebhookHttpClient {

    private final RestClient webhookRestClient;

    /** @return the 2xx status code the merchant responded with */
    public int post(String url, String signature, byte[] body) throws WebhookDeliveryException {
        int statusCode;
        try {
            statusCode = webhookRestClient.post()
                    .uri(URI.create(url))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(WebhookSigner.SIGNATURE_HEADER, signature)
                    .body(body)
                    .exchange((request, response) -> response.getStatusCode().value());
        } catch (RestClientException | IllegalArgumentException e) {
            throw new WebhookDeliveryException("Request to merchant endpoint failed: " + e.getMessage(), e);
        }

        if (statusCode < 200 || statusCode >= 300) {
            throw new WebhookDeliveryException("Merchant endpoint responded with HTTP " + statusCode, statusCode);
        }
        return statusCode;
    }
}
