package com.shylesh.webhook_service.dto;

import java.time.LocalDateTime;

/** Same error shape as payment-service: {timestamp, status, message}. */
public record ApiErrorResponse(LocalDateTime timestamp, int status, String message) {
}
