package com.shylesh.payment_service.saga;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A card processor over real HTTP for the saga tests, with the behaviours that matter to the
 * orchestrator: idempotent replay per key, declines by payment method, an outage switch, and a
 * first answer that arrives after the client's read timeout (pm_card_timeout_once).
 */
class StubProcessor {

    record Call(String method, String path, String idempotencyKey, String body) {
    }

    private final HttpServer server;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    /** Idempotency-Key -> stored response (status, body). */
    private final Map<String, String[]> responses = new ConcurrentHashMap<>();
    /** authorization id -> payment method */
    private final Map<String, String> authorizations = new ConcurrentHashMap<>();
    private final AtomicLong outageUntil = new AtomicLong();
    private volatile long slowFirstAnswerMillis = 1_500;

    StubProcessor() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    void outageFor(long millis) {
        outageUntil.set(System.currentTimeMillis() + millis);
    }

    void endOutage() {
        outageUntil.set(0);
    }

    List<Call> calls() {
        return calls;
    }

    List<Call> callsFor(String pathFragment) {
        return calls.stream().filter(c -> c.path().contains(pathFragment)).toList();
    }

    /** Calls whose Idempotency-Key starts with the prefix (keys are sagaId:step). */
    List<Call> callsWithKey(String keyPrefix) {
        return calls.stream().filter(c -> c.idempotencyKey() != null && c.idempotencyKey().startsWith(keyPrefix)).toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getPath();
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        calls.add(new Call(exchange.getRequestMethod(), path, key, body));

        if (System.currentTimeMillis() < outageUntil.get()) {
            respond(exchange, 503, "{\"status\":\"ERROR\",\"code\":\"processor_unavailable\"}");
            return;
        }
        String[] stored = responses.get(key);
        if (stored != null) {
            respond(exchange, Integer.parseInt(stored[0]), stored[1]);
            return;
        }

        String[] response = process(path, objectMapper.readTree(body.isEmpty() ? "{}" : body));
        responses.put(key, response);
        if (path.equals("/v1/authorizations") && body.contains("pm_card_timeout_once")) {
            sleep(slowFirstAnswerMillis);
        }
        respond(exchange, Integer.parseInt(response[0]), response[1]);
    }

    private String[] process(String path, JsonNode body) {
        if (path.equals("/v1/authorizations")) {
            String method = body.get("paymentMethod").asText();
            if (method.equals("pm_card_declined")) {
                return new String[]{"402", "{\"status\":\"DECLINED\",\"code\":\"do_not_honor\"}"};
            }
            String id = "auth_" + UUID.randomUUID().toString().replace("-", "");
            authorizations.put(id, method);
            return new String[]{"201", "{\"id\":\"" + id + "\",\"status\":\"AUTHORIZED\"}"};
        }
        if (path.endsWith("/capture")) {
            String id = path.split("/")[3];
            if ("pm_card_capture_fails".equals(authorizations.get(id))) {
                return new String[]{"402", "{\"status\":\"DECLINED\",\"code\":\"capture_declined\"}"};
            }
            return new String[]{"200", "{\"id\":\"" + id + "\",\"status\":\"CAPTURED\"}"};
        }
        if (path.equals("/v1/reversals")) {
            return new String[]{"200", "{\"status\":\"REVERSED\",\"authorizationId\":null}"};
        }
        if (path.equals("/v1/refunds")) {
            String id = body.get("authorizationId").asText();
            if ("pm_card_refund_fails".equals(authorizations.get(id))) {
                return new String[]{"402", "{\"status\":\"DECLINED\",\"code\":\"refund_declined\"}"};
            }
            return new String[]{"201", "{\"id\":\"re_" + UUID.randomUUID().toString().replace("-", "") + "\",\"status\":\"SUCCEEDED\"}"};
        }
        return new String[]{"404", "{\"status\":\"ERROR\",\"code\":\"not_found\"}"};
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException e) {
            // the client gave up (read timeout): exactly the case being simulated
        } finally {
            exchange.close();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
