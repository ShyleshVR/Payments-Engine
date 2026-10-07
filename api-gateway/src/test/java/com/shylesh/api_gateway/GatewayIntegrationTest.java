package com.shylesh.api_gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import reactor.core.publisher.Mono;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * The real gateway (routes, edge security, Redis rate limiter on a real Redis) proxying to a
 * separate upstream HTTP server that stands in for the services and records what reaches it.
 * (A @RestController inside the app would not do: Spring MVC-style handler mappings win over the
 * gateway's route mapping, so requests would bypass the routes and their filters entirely.)
 */
@SpringBootTest(properties = {
        "payflow.gateway.rate-limit.replenish-rate=1",
        "payflow.gateway.rate-limit.burst-capacity=2",
        "management.tracing.enabled=false"
})
@Testcontainers
class GatewayIntegrationTest {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            // Docker Desktop's port proxy accepts connections before redis-server listens
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\n", 1));

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    private WebTestClient client;

    private static HttpServer upstreamServer;
    private static final AtomicInteger upstreamCalls = new AtomicInteger();
    /** When set, the next request to .../drop-once is cut off without a response, like a pod killed mid-request. */
    private static final AtomicBoolean dropNext = new AtomicBoolean();
    /** A port nothing listens on: a service with no running pod. */
    private static int closedPort;

    /**
     * Stands in for the services behind the gateway: echoes the X-Request-Id it received;
     * .../drop-once closes the connection without answering while dropNext is set;
     * .../error500 answers 500 (an error the service itself returned).
     */
    @BeforeAll
    static void startUpstream() throws IOException {
        upstreamServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstreamServer.createContext("/", exchange -> {
            upstreamCalls.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/drop-once") && dropNext.compareAndSet(true, false)) {
                exchange.close(); // no status line: the gateway sees the connection close early
                return;
            }
            if (path.endsWith("/error500")) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            String requestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
            byte[] body = ("{\"requestId\":\"" + (requestId == null ? "" : requestId) + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstreamServer.start();
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
    }

    @AfterAll
    static void stopUpstream() {
        upstreamServer.stop(0);
    }

    @DynamicPropertySource
    static void routeToUpstream(DynamicPropertyRegistry registry) {
        String url = "http://127.0.0.1:" + upstreamServer.getAddress().getPort();
        for (String route : new String[]{"merchant", "payment", "ledger"}) {
            registry.add("payflow.gateway.routes." + route, () -> url);
        }
        registry.add("payflow.gateway.routes.webhook", () -> "http://127.0.0.1:" + closedPort);
    }

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).apply(springSecurity()).configureClient().build();
        upstreamCalls.set(0);
        dropNext.set(false);
        when(jwtDecoder.decode(anyString())).thenReturn(Mono.error(new BadJwtException("Signed JWT rejected: Invalid signature")));
    }

    private static String merchant() {
        return UUID.randomUUID().toString();
    }

    @Test
    void missingTokenIsRejectedAtTheEdgeAndNeverReachesTheService() {
        client.get().uri("/api/v1/payments/pay_1").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueMatches(HttpHeaders.WWW_AUTHENTICATE, "Bearer.*")
                .expectHeader().exists("X-Request-Id")
                .expectBody().jsonPath("$.status").isEqualTo(401);

        assertThat(upstreamCalls.get()).isZero();
    }

    @Test
    void forgedTokenIsRejectedAtTheEdge() {
        client.get().uri("/api/v1/ledger/balance?currency=USD")
                .header(HttpHeaders.AUTHORIZATION, "Bearer forged.token.value")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.message").value(message -> assertThat((String) message).contains("Invalid signature"));

        assertThat(upstreamCalls.get()).isZero();
    }

    @Test
    void validTokenIsRoutedWithARequestId() {
        client.mutateWith(mockJwt().jwt(jwt -> jwt.claim("merchant_id", merchant())))
                .get().uri("/api/v1/payments/pay_1").exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectBody().jsonPath("$.requestId").isNotEmpty();

        assertThat(upstreamCalls.get()).isEqualTo(1);
    }

    @Test
    void callerSuppliedRequestIdIsKeptButUnsafeOnesAreReplaced() {
        client.mutateWith(mockJwt().jwt(jwt -> jwt.claim("merchant_id", merchant())))
                .get().uri("/api/v1/payments/pay_1").header("X-Request-Id", "order-42")
                .exchange()
                .expectHeader().valueEquals("X-Request-Id", "order-42");

        client.mutateWith(mockJwt().jwt(jwt -> jwt.claim("merchant_id", merchant())))
                .get().uri("/api/v1/payments/pay_1").header("X-Request-Id", "bad id\nwith newline")
                .exchange()
                .expectHeader().value("X-Request-Id", id -> assertThat(id).doesNotContain(" ").hasSize(36));
    }

    @Test
    void tokenEndpointNeedsNoBearerToken() {
        client.post().uri("/oauth2/token").exchange().expectStatus().isOk();
        assertThat(upstreamCalls.get()).isEqualTo(1);
    }

    @Test
    void unknownPathsAreDenied() {
        client.mutateWith(mockJwt()).get().uri("/internal/admin").exchange().expectStatus().isForbidden();
    }

    @Test
    void rateLimitIsPerMerchant() {
        String busy = merchant();
        String other = merchant();

        // burst capacity 2: the third immediate request from the same merchant is throttled
        for (int i = 0; i < 2; i++) {
            client.mutateWith(mockJwt().jwt(jwt -> jwt.claim("merchant_id", busy)))
                    .get().uri("/api/v1/payments/pay_1").exchange().expectStatus().isOk();
        }
        client.mutateWith(mockJwt().jwt(jwt -> jwt.claim("merchant_id", busy)))
                .get().uri("/api/v1/payments/pay_1").exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().exists("X-RateLimit-Remaining")
                .expectHeader().exists("X-Request-Id");
        assertThat(upstreamCalls.get()).isEqualTo(2);

        // another merchant has its own bucket
        client.mutateWith(mockJwt().jwt(jwt -> jwt.claim("merchant_id", other)))
                .get().uri("/api/v1/payments/pay_1").exchange().expectStatus().isOk();
    }

    @Test
    void readInterruptedByADyingPodIsRetried() {
        dropNext.set(true);

        client.mutateWith(mockJwt().jwt(jwt -> jwt.claim("merchant_id", merchant())))
                .get().uri("/api/v1/payments/drop-once").exchange()
                .expectStatus().isOk();

        assertThat(upstreamCalls.get()).isEqualTo(2);
    }

    @Test
    void writeInterruptedByADyingPodIsNotRetriedAndReports502() {
        dropNext.set(true);

        client.mutateWith(mockJwt().jwt(jwt -> jwt.claim("merchant_id", merchant())))
                .post().uri("/api/v1/payments/drop-once").exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectHeader().exists("X-Request-Id")
                .expectBody().jsonPath("$.status").isEqualTo(502);

        // the POST may have been applied before the connection broke: retrying is the client's
        // call, with the same Idempotency-Key
        assertThat(upstreamCalls.get()).isEqualTo(1);
    }

    @Test
    void errorReturnedByTheServiceIsPassedThroughNotRetried() {
        client.mutateWith(mockJwt().jwt(jwt -> jwt.claim("merchant_id", merchant())))
                .get().uri("/api/v1/payments/error500").exchange()
                .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(upstreamCalls.get()).isEqualTo(1);
    }

    @Test
    void serviceWithNoPodListeningReports503() {
        client.mutateWith(mockJwt().jwt(jwt -> jwt.claim("merchant_id", merchant())))
                .get().uri("/api/v1/webhooks/subscriptions").exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody().jsonPath("$.message").isEqualTo("Service temporarily unavailable, please retry");
    }

    @Test
    void healthIsPublic() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

}
