package com.shylesh.payment_service.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** The processor client against a local HTTP server. */
class ProcessorClientTest {

    private HttpServer server;
    private ProcessorClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"id\":\"auth_1\",\"status\":\"AUTHORIZED\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = new ProcessorClient(RestClient.builder(),
                new ProcessorProperties("http://127.0.0.1:" + server.getAddress().getPort(), "key", Duration.ofSeconds(1), Duration.ofSeconds(2)),
                CircuitBreakerRegistry.ofDefaults(), new ObjectMapper(), new SimpleMeterRegistry());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void requestBodiesAreNotWrittenOnANewThreadPerCall() {
        // warm up: the HTTP client starts its own threads once
        client.authorize("warm-up", "pm_card_visa", new BigDecimal("10.00"), "USD", "pay_x");
        long startedBefore = ManagementFactory.getThreadMXBean().getTotalStartedThreadCount();

        for (int i = 0; i < 50; i++) {
            ProcessorResponse response = client.authorize("key-" + i, "pm_card_visa", new BigDecimal("10.00"), "USD", "pay_x");
            assertThat(response.outcome()).isEqualTo(ProcessorResponse.Outcome.SUCCEEDED);
        }

        // without an executor of its own, Spring's JDK request factory starts a thread per request
        long started = ManagementFactory.getThreadMXBean().getTotalStartedThreadCount() - startedBefore;
        assertThat(started).isLessThan(10);
    }

    @Test
    void requestMetricsAreTaggedByUriTemplateNotByAuthorizationId() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        ProcessorClient observed = new ProcessorClient(RestClient.builder().observationRegistry(observations),
                new ProcessorProperties("http://127.0.0.1:" + server.getAddress().getPort(), "key", Duration.ofSeconds(1), Duration.ofSeconds(2)),
                CircuitBreakerRegistry.ofDefaults(), new ObjectMapper(), meters);

        observed.capture("capture-1", "auth_1");
        observed.capture("capture-2", "auth_2");

        // one series for every capture, rather than one per authorization (unbounded cardinality)
        assertThat(meters.find("http.client.requests").timers())
                .extracting(timer -> timer.getId().getTag("uri"))
                .containsExactly("/v1/authorizations/{id}/capture");
    }
}
