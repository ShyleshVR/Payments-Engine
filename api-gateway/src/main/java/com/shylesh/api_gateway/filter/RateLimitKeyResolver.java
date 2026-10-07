package com.shylesh.api_gateway.filter;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * Who a request counts against for rate limiting:
 * - a merchant token: the merchant (all of a merchant's credentials share one budget, so
 *   rotating or adding a credential doesn't double its allowance);
 * - an operator token (no merchant): its client id;
 * - no token (the token endpoint): the client IP, which slows down secret guessing.
 * The IP is the TCP peer address, not X-Forwarded-For, which a client could set to anything.
 */
@Component("rateLimitKeyResolver")
public class RateLimitKeyResolver implements KeyResolver {

    static final String MERCHANT_ID_CLAIM = "merchant_id";

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(authentication -> {
                    String merchantId = authentication.getToken().getClaimAsString(MERCHANT_ID_CLAIM);
                    return merchantId != null
                            ? "merchant:" + merchantId
                            : "client:" + authentication.getToken().getSubject();
                })
                .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + clientIp(exchange)));
    }

    private static String clientIp(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
    }
}
