package com.shylesh.processor_simulator.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Outcome of one processor operation: HTTP status and JSON body, plus the authorization it
 * concerns (stored with the Idempotency-Key, so reversals and inquiries can find it).
 */
public record ProcessorResult(int status, Map<String, Object> body, String authorizationId) {

    public static ProcessorResult of(int status, Map<String, Object> body, String authorizationId) {
        return new ProcessorResult(status, body, authorizationId);
    }

    /** A business decline (402): the request was valid, the answer is no. */
    public static ProcessorResult declined(String code, String message, String authorizationId) {
        return new ProcessorResult(402, error("DECLINED", code, message), authorizationId);
    }

    public static ProcessorResult error(int status, String code, String message) {
        return new ProcessorResult(status, error("ERROR", code, message), null);
    }

    public static Map<String, Object> error(String outcome, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", outcome);
        body.put("code", code);
        body.put("message", message);
        return body;
    }
}
