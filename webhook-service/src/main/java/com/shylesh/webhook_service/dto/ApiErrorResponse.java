package com.shylesh.webhook_service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/** Same error shape as payment-service. */
@Getter
@Builder
@AllArgsConstructor
public class ApiErrorResponse {

    private LocalDateTime timestamp;

    private int status;

    private String message;

}
