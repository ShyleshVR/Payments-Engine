package com.shylesh.reconciliation_service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The reconciliation's counterparts over real HTTP: merchant-service's token endpoint, the
 * processor's settlement report, the ledger's transaction pages and open holds, and
 * payment-service's lookup. Records every request (path, auth header) for assertions.
 */
class StubSources {

    record Request(String method, String path, String query, String authorization, String apiKey, String body) {
    }

    private final HttpServer server;
    final List<Request> requests = new CopyOnWriteArrayList<>();

    volatile String report = "{\"authorizations\":[],\"refunds\":[]}";
    volatile List<String> ledgerPages = List.of("{\"items\":[],\"hasNext\":false}");
    volatile String openHolds = "[]";
    /** Answer to every lookup (the stub doesn't filter by the requested ids). */
    volatile String payments = "[]";
    volatile String payouts = "[]";
    volatile String openPayoutHolds = "[]";
    volatile boolean ledgerDown;

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
        report = "{\"authorizations\":[],\"refunds\":[]}";
        ledgerPages = List.of("{\"items\":[],\"hasNext\":false}");
        openHolds = "[]";
        payments = "[]";
        payouts = "[]";
        openPayoutHolds = "[]";
        ledgerDown = false;
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
        } else if (path.equals("/v1/reports/settlement")) {
            respond(exchange, 200, report);
        } else if (path.equals("/api/v1/ledger/transactions")) {
            if (ledgerDown) {
                respond(exchange, 503, "{\"status\":503}");
                return;
            }
            int page = Integer.parseInt(query.replaceAll(".*page=(\\d+).*", "$1"));
            respond(exchange, 200, ledgerPages.get(Math.min(page, ledgerPages.size() - 1)));
        } else if (path.equals("/api/v1/ledger/refund-holds/open")) {
            respond(exchange, 200, openHolds);
        } else if (path.equals("/api/v1/payments/lookup")) {
            respond(exchange, 200, payments);
        } else if (path.equals("/api/v1/payouts/lookup")) {
            respond(exchange, 200, payouts);
        } else if (path.equals("/api/v1/ledger/payout-holds/open")) {
            respond(exchange, 200, openPayoutHolds);
        } else {
            respond(exchange, 404, "{}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
