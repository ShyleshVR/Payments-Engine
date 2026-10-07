package com.shylesh.api_gateway.security;

import com.shylesh.api_gateway.web.JsonErrorWriter;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * 401/403 at the edge in the same {timestamp, status, message} shape the services use, with the
 * standard RFC 6750 WWW-Authenticate: Bearer error="..." header OAuth2 clients rely on.
 * (Spring's BearerToken* handlers aren't reused: they complete the response, leaving no body.)
 */
@Component
@RequiredArgsConstructor
public class JsonSecurityErrorHandler implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {

    private final JsonErrorWriter errorWriter;

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException exception) {
        boolean tokenPresented = exception instanceof OAuth2AuthenticationException;
        String reason = tokenPresented && ((OAuth2AuthenticationException) exception).getError().getDescription() != null
                ? ((OAuth2AuthenticationException) exception).getError().getDescription()
                : "Missing or invalid bearer token";
        // RFC 6750: no error code when no token was sent at all.
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, tokenPresented
                ? "Bearer error=\"invalid_token\", error_description=\"" + quoteSafe(reason) + "\""
                : "Bearer");
        return errorWriter.write(exchange, HttpStatus.UNAUTHORIZED, reason);
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE,
                "Bearer error=\"insufficient_scope\", error_description=\"The request requires higher privileges\"");
        return errorWriter.write(exchange, HttpStatus.FORBIDDEN, "Token does not grant access to this resource");
    }

    private static String quoteSafe(String value) {
        return value.replace("\\", "").replace("\"", "'");
    }
}
