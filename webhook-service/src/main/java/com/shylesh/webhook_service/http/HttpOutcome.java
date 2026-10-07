package com.shylesh.webhook_service.http;

/**
 * Result of one webhook HTTP attempt.
 *
 * @param statusCode null when no HTTP response was received
 */
public record HttpOutcome(Result result, Integer statusCode, String error, long durationMs) {

    public enum Result {
        SUCCESS,
        /** Worth trying again later: 5xx, 408, 429, timeouts, connection errors. */
        RETRYABLE,
        /** Retrying won't change the answer: other 4xx, 3xx (redirects aren't followed), blocked target. */
        PERMANENT
    }

    public static HttpOutcome forStatus(int statusCode, String responseSnippet, long durationMs) {
        Result result = classify(statusCode);
        String error = result == Result.SUCCESS
                ? null
                : "HTTP " + statusCode + (responseSnippet == null || responseSnippet.isBlank() ? "" : ": " + responseSnippet);
        return new HttpOutcome(result, statusCode, error, durationMs);
    }

    public static HttpOutcome retryable(String error, long durationMs) {
        return new HttpOutcome(Result.RETRYABLE, null, error, durationMs);
    }

    public static HttpOutcome permanent(String error, long durationMs) {
        return new HttpOutcome(Result.PERMANENT, null, error, durationMs);
    }

    static Result classify(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return Result.SUCCESS;
        }
        if (statusCode == 408 || statusCode == 429 || statusCode >= 500) {
            return Result.RETRYABLE;
        }
        if (statusCode >= 300 && statusCode < 500) {
            return Result.PERMANENT;
        }
        return Result.RETRYABLE;
    }
}
