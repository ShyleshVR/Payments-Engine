package com.shylesh.merchant_service.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Counts token requests by outcome as auth.token.requests{outcome=issued|invalid_client|rejected}.
 * The token endpoint is served by a Spring Security filter, not a controller, so the standard
 * http.server.requests metric only sees it as uri=UNKNOWN. This filter wraps the security chain
 * (higher precedence) so it sees every response, including client authentication failures.
 */
@Configuration
public class TokenEndpointMetricsConfig {

    static final String TOKEN_ENDPOINT = "/oauth2/token";

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> tokenEndpointMetricsFilter(MeterRegistry meterRegistry) {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                try {
                    chain.doFilter(request, response);
                } finally {
                    Counter.builder("auth.token.requests")
                            .tag("outcome", outcome(response.getStatus()))
                            .register(meterRegistry)
                            .increment();
                }
            }
        };

        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns(TOKEN_ENDPOINT);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    static String outcome(int status) {
        if (status == 200) {
            return "issued";
        }
        if (status == 401) {
            return "invalid_client";
        }
        return "rejected";
    }
}
