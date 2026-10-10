package com.shylesh.payout_service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One HTTP server standing in for the bank (transfers), the ledger's read API (payable balances)
 * and merchant-service's token endpoint. The bank behaves by test account like the simulator,
 * on a fast clock: ba_test_ok is paid after settleMillis, ba_test_closed fails, ba_test_returned
 * is paid and then returned, ba_test_slow stays pending until released, ba_test_invalid is
 * declined, and ba_test_lost_answer creates the transfer but loses the first answer (500).
 */
class StubServer {

    record Request(String method, String path, String idempotencyKey, String body) {
    }

    private record Transfer(String id, String account, BigDecimal amount, String reference, long createdAt) {
    }

    private final HttpServer server;
    private final ObjectMapper objectMapper = new ObjectMapper();
    final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Transfer> transfersByKey = new ConcurrentHashMap<>();
    private final Map<String, Transfer> transfersById = new ConcurrentHashMap<>();

    volatile long settleMillis = 300;
    volatile long returnMillis = 700;
    volatile boolean bankDown;
    volatile boolean slowReleased;
    /** What the ledger reports as payable: merchant -> amount (USD). */
    final Map<UUID, BigDecimal> payable = new ConcurrentHashMap<>();

    StubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    void reset() {
        bankDown = false;
        slowReleased = false;
        payable.clear();
    }

    List<Request> transferRequests(String reference) {
        return requests.stream().filter(r -> r.method().equals("POST") && r.path().equals("/v1/transfers")
                && r.body().contains(reference)).toList();
    }

    long transfersFor(String reference) {
        return transfersById.values().stream().filter(t -> t.reference().equals(reference)).count();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Request(exchange.getRequestMethod(), path, exchange.getRequestHeaders().getFirst("Idempotency-Key"), body));

        if (path.equals("/oauth2/token")) {
            respond(exchange, 200, "{\"access_token\":\"service-token\",\"token_type\":\"Bearer\",\"expires_in\":900,\"scope\":\"ledger:admin\"}");
        } else if (path.equals("/api/v1/ledger/payable-balances")) {
            StringBuilder json = new StringBuilder("[");
            payable.forEach((merchant, amount) -> json.append(json.length() > 1 ? "," : "")
                    .append("{\"merchantId\":\"").append(merchant).append("\",\"currency\":\"USD\",\"payable\":").append(amount).append("}"));
            respond(exchange, 200, json.append("]").toString());
        } else if (path.startsWith("/api/v1/ledger/merchants/") && path.endsWith("/payable")) {
            UUID merchant = UUID.fromString(path.split("/")[5]);
            respond(exchange, 200, "{\"merchantId\":\"" + merchant + "\",\"currency\":\"USD\",\"payable\":"
                    + payable.getOrDefault(merchant, BigDecimal.ZERO) + "}");
        } else if (bankDown && path.startsWith("/v1/transfers")) {
            respond(exchange, 503, "{\"status\":\"ERROR\",\"code\":\"processor_unavailable\"}");
        } else if (path.equals("/v1/transfers") && exchange.getRequestMethod().equals("POST")) {
            transfer(exchange, exchange.getRequestHeaders().getFirst("Idempotency-Key"), objectMapper.readTree(body));
        } else if (path.startsWith("/v1/transfers/")) {
            Transfer transfer = transfersById.get(path.substring("/v1/transfers/".length()));
            if (transfer == null) {
                respond(exchange, 404, "{\"status\":\"ERROR\",\"code\":\"transfer_not_found\"}");
            } else {
                respond(exchange, 200, body(transfer));
            }
        } else {
            respond(exchange, 404, "{}");
        }
    }

    private void transfer(HttpExchange exchange, String key, JsonNode request) throws IOException {
        String account = request.get("bankAccount").asText();
        if (account.equals("ba_test_invalid")) {
            respond(exchange, 402, "{\"status\":\"DECLINED\",\"code\":\"invalid_account\",\"message\":\"can't route\"}");
            return;
        }
        boolean fresh = !transfersByKey.containsKey(key);
        Transfer transfer = transfersByKey.computeIfAbsent(key, k -> {
            Transfer created = new Transfer("tr_" + UUID.randomUUID().toString().replace("-", ""), account,
                    request.get("amount").decimalValue(), request.get("reference").asText(), System.currentTimeMillis());
            transfersById.put(created.id(), created);
            return created;
        });
        if (fresh && account.equals("ba_test_lost_answer")) {
            // created, but the answer never makes it back
            respond(exchange, 500, "{\"status\":\"ERROR\",\"code\":\"internal\"}");
            return;
        }
        respond(exchange, 201, body(transfer));
    }

    private String body(Transfer transfer) {
        long age = System.currentTimeMillis() - transfer.createdAt();
        String status;
        String code = null;
        switch (transfer.account()) {
            case "ba_test_closed" -> {
                status = age >= settleMillis ? "FAILED" : "PENDING";
                code = age >= settleMillis ? "account_closed" : null;
            }
            case "ba_test_returned" -> {
                status = age >= returnMillis ? "RETURNED" : age >= settleMillis ? "PAID" : "PENDING";
                code = age >= returnMillis ? "account_frozen" : null;
            }
            case "ba_test_slow" -> status = slowReleased ? "PAID" : "PENDING";
            default -> status = age >= settleMillis ? "PAID" : "PENDING";
        }
        return "{\"id\":\"" + transfer.id() + "\",\"status\":\"" + status + "\",\"bankAccount\":\"" + transfer.account()
                + "\",\"amount\":" + transfer.amount() + ",\"currency\":\"USD\",\"reference\":\"" + transfer.reference()
                + "\",\"failureCode\":" + (code == null ? "null" : "\"" + code + "\"") + "}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
