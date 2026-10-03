package com.roshan.seat_reservation.common;

import com.roshan.seat_reservation.observability.ReservationMetrics;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Map.entry;

/**
 * Turns every failure into {code, message} JSON. Domain outcomes and contention
 * are 4xx; only genuinely unexpected errors are 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ReservationMetrics metrics;


    public GlobalExceptionHandler(ReservationMetrics metrics) {
        this.metrics = metrics;
    }
    public record ApiError(String code, String message) { }

    private static final Map<String, String> MESSAGES = Map.ofEntries(
            entry("seat_taken", "One or more requested seats are already taken"),
            entry("per_user_limit", "Per-user seat limit for this show would be exceeded"),
            entry("idempotency_key_reused", "Idempotency key was already used with a different request"),
            entry("idempotency_key_required", "Provide Idempotency-Key header or idempotency_key in the body"),
            entry("unknown_seat", "One or more requested seats do not exist in this show"),
            entry("show_not_found", "Show not found"),
            entry("reservation_not_found", "Reservation not found"),
            entry("admin_only", "Admin role required"),
            entry("duplicate_seat_labels", "Seat labels must be unique"));

    /** Domain outcomes thrown by services/controllers. */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> domain(ResponseStatusException ex) {
        String code = ex.getReason() != null ? ex.getReason() : "error";
        if (ReservationMetrics.DECLINE_REASONS.contains(code)) metrics.declined(code);
        return ResponseEntity.status(ex.getStatusCode())
                .body(new ApiError(code, MESSAGES.getOrDefault(code, code)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(new ApiError("validation_failed", detail));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> malformedJson(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(new ApiError("malformed_json", "Request body is not valid JSON"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> badParam(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError("invalid_parameter", "Invalid value for '" + ex.getName() + "'"));
    }

    /** Lock timeout / deadlock under extreme contention: a clean decline, not a server error. */
    @ExceptionHandler({PessimisticLockingFailureException.class,
            PessimisticLockException.class, LockTimeoutException.class})
    ResponseEntity<ApiError> contention(Exception ex) {
        metrics.declined("seat_contended");
        log.warn("lock contention: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("seat_contended", "Seat is under heavy contention, please retry"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> integrity(DataIntegrityViolationException ex) {
        log.warn("integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("conflict", "Request conflicts with current state"));
    }

    /** DB unreachable or connection pool exhausted: honest 503 so clients/LB back off. */
    @ExceptionHandler({CannotCreateTransactionException.class, CannotGetJdbcConnectionException.class})
    ResponseEntity<ApiError> dbUnavailable(Exception ex) {
        log.error("database unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .body(new ApiError("service_unavailable", "Database temporarily unavailable"));
    }

    /** Spring MVC built-ins (404 no route, 405, 415...) keep their status; anything else is a real bug. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> other(Exception ex) {
        if (ex instanceof ErrorResponse er) {
            HttpStatusCode status = er.getStatusCode();
            return ResponseEntity.status(status).body(new ApiError(codeFor(status), ex.getMessage()));
        }
        log.error("unhandled error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("internal_error", "Unexpected error"));
    }

    private static String codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "bad_request";
            case 404 -> "not_found";
            case 405 -> "method_not_allowed";
            case 415 -> "unsupported_media_type";
            default -> "error";
        };
    }
}