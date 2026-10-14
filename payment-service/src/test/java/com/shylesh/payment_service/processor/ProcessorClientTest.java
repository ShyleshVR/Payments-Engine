package com.shylesh.payment_service.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aCallWithNoAnswerToTrustIsLoggedWithItsReason(CapturedOutput output) throws IOException {
        HttpServer failing = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        failing.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"code\":\"processor_unavailable\",\"message\":\"Simulated outage\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        failing.start();
        int closedPort = failing.getAddress().getPort() + 1;
        try {
            ProcessorResponse answered = clientFor("http://127.0.0.1:" + failing.getAddress().getPort(), CircuitBreakerRegistry.ofDefaults())
                    .authorize("key-1", "pm_card_visa", new BigDecimal("10.00"), "USD", "pay_x");
            ProcessorResponse unanswered = clientFor("http://127.0.0.1:" + closedPort, CircuitBreakerRegistry.ofDefaults())
                    .capture("key-2", "auth_1");

            assertThat(answered.outcome()).isEqualTo(ProcessorResponse.Outcome.UNKNOWN);
            assertThat(unanswered.outcome()).isEqualTo(ProcessorResponse.Outcome.UNKNOWN);
            assertThat(output.getOut()).contains(
                    "Processor call got no answer to trust: operation=authorize, UNKNOWN 503 processor_unavailable: Simulated outage",
                    "Processor call got no answer to trust: operation=capture, UNKNOWN unreachable: ConnectException");
        } finally {
            failing.stop(0);
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void theBreakerOpeningIsLoggedOnceAndTheCallsItRefusesAreNot(CapturedOutput output) {
        // opens after 2 failed calls out of 2
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(2).minimumNumberOfCalls(2).failureRateThreshold(50)
                .recordExceptions(RuntimeException.class).build());
        ProcessorClient unreachable = clientFor("http://127.0.0.1:" + (server.getAddress().getPort() + 1), breakers);

        for (int i = 0; i < 6; i++) {
            unreachable.capture("key-" + i, "auth_1");
        }

        assertThat(output.getOut()).containsOnlyOnce("Processor circuit breaker changed state: State transition from CLOSED to OPEN");
        // the two calls that were sent are logged; the four the breaker refused are not
        assertThat(output.getOut().split("Processor call got no answer to trust", -1)).hasSize(3);
    }

    private static ProcessorClient clientFor(String baseUrl, CircuitBreakerRegistry breakers) {
        return new ProcessorClient(RestClient.builder(),
                new ProcessorProperties(baseUrl, "key", Duration.ofSeconds(1), Duration.ofSeconds(2)),
                breakers, new ObjectMapper(), new SimpleMeterRegistry());
    }
}
