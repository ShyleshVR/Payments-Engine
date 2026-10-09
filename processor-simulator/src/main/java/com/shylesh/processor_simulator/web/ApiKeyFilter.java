package com.shylesh.processor_simulator.web;

import com.shylesh.processor_simulator.config.ProcessorProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** The processor API (/v1/**) needs the shared API key; actuator endpoints stay open for probes. */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";

    private final byte[] apiKey;

    public ApiKeyFilter(ProcessorProperties properties) {
        this.apiKey = properties.apiKey().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        // constant-time comparison, so response timing doesn't reveal how much of a guess matched
        if (presented == null || !MessageDigest.isEqual(apiKey, presented.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"status\":\"ERROR\",\"code\":\"invalid_api_key\",\"message\":\"Missing or invalid X-Api-Key\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
