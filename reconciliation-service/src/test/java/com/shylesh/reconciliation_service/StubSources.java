package com.shylesh.reconciliation_service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The reconciliation's counterparts over real HTTP: merchant-service's token endpoint, the
 * processor's paged settlement report, the ledger's transaction pages and open holds, and
 * payment-service's and payout-service's lookups. Records every request (path, auth header) for
 * assertions.
 *
 * Each source is set as a JSON array of its records. The paged endpoints serve it as the real
 * ones do: "limit"/"size" rows a page (never more than maxPage), with a "next" cursor to send
 * back as "after", null on the last page.
 */
class StubSources {

    record Request(String method, String path, String query, String authorization, String apiKey, String body) {
    }

    // amounts are served as they were written (25.00 stays 25.00)
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private final HttpServer server;
    final List<Request> requests = new CopyOnWriteArrayList<>();

    volatile String authorizations = "[]";
    volatile String refunds = "[]";
    volatile String transfers = "[]";
    volatile String ledger = "[]";
    volatile String openHolds = "[]";
    /** The lookups answer with those of these that were asked for. */
    volatile String payments = "[]";
    volatile String payouts = "[]";
    volatile String openPayoutHolds = "[]";
    /** The most rows a page holds, whatever the caller asks for. */
    volatile int maxPage = 1000;
    volatile boolean ledgerDown;
    volatile boolean payoutsDown;
    /** Runs when the ledger's transactions are asked for (something happening in the middle of a run). */
    volatile Runnable onLedgerRead = () -> {
    };

    StubSources() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    List<Request> requestsTo(String pathPrefix) {
        return requests.stream().filter(r -> r.path().startsWith(pathPrefix)).toList();
    }

    void reset() {
        requests.clear();
        authorizations = "[]";
        refunds = "[]";
        transfers = "[]";
        ledger = "[]";
        openHolds = "[]";
        payments = "[]";
        payouts = "[]";
        openPayoutHolds = "[]";
        maxPage = 1000;
        ledgerDown = false;
        payoutsDown = false;
        onLedgerRead = () -> {
        };
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Request(exchange.getRequestMethod(), path, query,
                exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestHeaders().getFirst("X-Api-Key"), body));

        if (path.equals("/oauth2/token")) {
            respond(exchange, 200, "{\"access_token\":\"service-token\",\"token_type\":\"Bearer\",\"expires_in\":900,"
                    + "\"scope\":\"ledger:admin payments:audit\"}");
        } else if (path.equals("/v1/reports/settlement/authorizations")) {
            respond(exchange, 200, page(authorizations, query, "limit"));
        } else if (path.equals("/v1/reports/settlement/refunds")) {
            respond(exchange, 200, page(refunds, query, "limit"));
        } else if (path.equals("/v1/reports/settlement/transfers")) {
            respond(exchange, 200, page(transfers, query, "limit"));
        } else if (path.equals("/api/v1/ledger/transactions")) {
            if (ledgerDown) {
                respond(exchange, 503, "{\"status\":503}");
                return;
            }
            onLedgerRead.run();
            respond(exchange, 200, page(ledger, query, "size"));
        } else if (path.equals("/api/v1/ledger/refund-holds/open")) {
            respond(exchange, 200, openHolds);
        } else if (path.equals("/api/v1/payments/lookup")) {
            respond(exchange, 200, askedFor(payments, "paymentId", body, "paymentIds"));
        } else if (path.equals("/api/v1/payouts/lookup")) {
            if (payoutsDown) {
                respond(exchange, 503, "{\"status\":503}");
                return;
            }
            respond(exchange, 200, askedFor(payouts, "payoutId", body, "payoutIds"));
        } else if (path.equals("/api/v1/ledger/payout-holds/open")) {
            respond(exchange, 200, openPayoutHolds);
        } else {
            respond(exchange, 404, "{}");
        }
    }

    /** The cursor is "c" + the position of the next row. */
    private String page(String records, String query, String sizeParameter) throws IOException {
        JsonNode all = JSON.readTree(records);
        int size = Math.min(Integer.parseInt(parameter(query, sizeParameter)), maxPage);
        String after = parameter(query, "after");
        int start = after == null ? 0 : Integer.parseInt(after.substring(1));
        int end = Math.min(start + size, all.size());
        List<String> items = new ArrayList<>();
        for (int i = start; i < end; i++) {
            items.add(all.get(i).toString());
        }
        return "{\"items\":[" + String.join(",", items) + "],\"next\":" + (end < all.size() ? "\"c" + end + "\"" : "null") + "}";
    }

    private static String parameter(String query, String name) {
        for (String pair : query.split("&")) {
            if (pair.startsWith(name + "=")) {
                return pair.substring(name.length() + 1);
            }
        }
        return null;
    }

    private static String askedFor(String records, String idField, String body, String idsField) throws IOException {
        Set<String> asked = new HashSet<>();
        JSON.readTree(body).path(idsField).forEach(id -> asked.add(id.asText()));
        List<String> found = new ArrayList<>();
        for (JsonNode item : JSON.readTree(records)) {
            if (asked.contains(item.path(idField).asText())) {
                found.add(item.toString());
            }
        }
        return "[" + String.join(",", found) + "]";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
