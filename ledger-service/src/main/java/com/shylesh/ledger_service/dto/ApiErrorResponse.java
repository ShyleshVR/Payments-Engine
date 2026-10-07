package com.shylesh.ledger_service.dto;

import java.time.LocalDateTime;

/** Same error shape as the other services: {timestamp, status, message}. */
public record ApiErrorResponse(LocalDateTime timestamp, int status, String message) {
}
