package com.shylesh.api_gateway.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;

import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.ConnectException;

/**
 * Maps failures to reach a service (after any retries) to the status that says what happened,
 * instead of a generic 500:
 * - 503: the service refused the connection (no pod listening, e.g. all replicas restarting);
 *   nothing was sent, so the client can safely retry;
 * - 502: the connection broke before a response (pod killed mid-request); a POST may or may not
 *   have been applied, which is what Idempotency-Key retries are for.
 * Timeouts already arrive as 504 from the gateway's HTTP client. Runs before Spring Boot's
 * default handler (order -1).
 */
@Slf4j
@Component
@Order(-2)
@RequiredArgsConstructor
public class UpstreamErrorHandler implements WebExceptionHandler {

    private final JsonErrorWriter errorWriter;

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        HttpStatus status;
        String message;
        if (ex instanceof ConnectException) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            message = "Service temporarily unavailable, please retry";
        } else if (ex instanceof IOException) {
            status = HttpStatus.BAD_GATEWAY;
            message = "Upstream service closed the connection before responding";
        } else {
            return Mono.error(ex);
        }
        log.warn("{} {} -> {}: {}", exchange.getRequest().getMethod(), exchange.getRequest().getPath(),
                status.value(), ex.toString());
        return errorWriter.write(exchange, status, message);
    }
}
