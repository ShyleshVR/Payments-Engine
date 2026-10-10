package com.shylesh.payout_service.web;

import com.shylesh.payout_service.payout.PayoutNotFoundException;
import com.shylesh.payout_service.payout.PayoutRequestException;
import com.shylesh.payout_service.payout.PayoutStateException;
import com.shylesh.payout_service.security.MerchantContextRequiredException;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.client.RestClientException;

import java.time.LocalDateTime;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PayoutNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(PayoutNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(PayoutRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleRequest(PayoutRequestException ex) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, ex.code() + ": " + ex.getMessage());
    }

    @ExceptionHandler(PayoutStateException.class)
    public ResponseEntity<ApiErrorResponse> handleState(PayoutStateException ex) {
        return error(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingRequestHeaderException.class,
            HandlerMethodValidationException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiErrorResponse> handleBadRequest(Exception ex) {
        String message = ex instanceof IllegalArgumentException ? ex.getMessage() : "Invalid request";
        if (ex instanceof MissingRequestHeaderException missing) {
            message = "Missing header " + missing.getHeaderName();
        } else if (ex instanceof MethodArgumentNotValidException invalid && invalid.getFieldError() != null) {
            message = invalid.getFieldError().getField() + " " + invalid.getFieldError().getDefaultMessage();
        }
        return error(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(MerchantContextRequiredException.class)
    public ResponseEntity<ApiErrorResponse> handleNoMerchant(MerchantContextRequiredException ex) {
        return error(HttpStatus.FORBIDDEN, "This endpoint needs a merchant token");
    }

    /** @PreAuthorize denials surface inside the controller call. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return error(HttpStatus.FORBIDDEN, "Token does not grant access to this resource");
    }

    /** The ledger couldn't be asked (payable balance): try again later. */
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ApiErrorResponse> handleDependency(RestClientException ex) {
        log.warn("Ledger call failed: {}", ex.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, "The ledger is unavailable, try again later");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.valueOf(errorResponse.getStatusCode().value());
            return error(status, errorResponse.getBody().getDetail());
        }
        log.error("Unexpected error", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    private static ResponseEntity<ApiErrorResponse> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(LocalDateTime.now(), status.value(), message));
    }
}
