package com.shylesh.api_gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Every request leaving the gateway carries an X-Request-Id (the caller's, if it sent a sane one,
 * otherwise a new UUID), and the response echoes it, so a merchant can quote it in a support
 * request and it can be found in every service's logs. Traces are correlated separately via
 * the W3C traceparent header that Micrometer Tracing propagates.
 *
 * A WebFilter ahead of Spring Security rather than a gateway GlobalFilter, so responses the
 * gateway produces itself (401, 403, 429) carry the id too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter implements WebFilter {

    public static final String HEADER = "X-Request-Id";

    /** Accept caller-supplied ids only if they are short and plain, so they can't pollute logs. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String requestId = incoming != null && SAFE_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(HEADER, requestId))
                .build();
        // Set at commit time: the proxied response's headers replace anything set earlier.
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER, requestId);
            return Mono.empty();
        });

        return chain.filter(exchange.mutate().request(request).build());
    }
}
