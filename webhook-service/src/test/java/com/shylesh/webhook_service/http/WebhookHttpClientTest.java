package com.shylesh.webhook_service.http;

import com.shylesh.webhook_service.signing.WebhookSigner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WebhookHttpClientTest {

    private static final String URL = "https://merchant.example.com/hooks";
    private static final byte[] BODY = "{\"payloadVersion\":\"1\"}".getBytes(StandardCharsets.UTF_8);

    private MockRestServiceServer server;
    private WebhookHttpClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new WebhookHttpClient(builder.build());
    }

    @Test
    void postsSignedJsonBodyAndReturnsStatusOn2xx() throws WebhookDeliveryException {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(WebhookSigner.SIGNATURE_HEADER, "sha256=abc"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().bytes(BODY))
                .andRespond(withSuccess());

        int status = client.post(URL, "sha256=abc", BODY);

        assertThat(status).isEqualTo(200);
        server.verify();
    }

    @Test
    void throwsWithResponseCodeOnNon2xx() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.post(URL, "sha256=abc", BODY))
                .isInstanceOf(WebhookDeliveryException.class)
                .satisfies(e -> assertThat(((WebhookDeliveryException) e).getResponseCode()).isEqualTo(503))
                .hasMessageContaining("503");
    }
}
