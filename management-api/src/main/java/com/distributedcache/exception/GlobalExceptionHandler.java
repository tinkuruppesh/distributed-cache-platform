package com.distributedcache.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.stream.Collectors;

/** One place that turns exceptions into consistent, safe JSON errors (no stack traces leak to clients). */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ApiError(String timestamp, int status, String error, String message, String path) {}

    private ResponseEntity<ApiError> build(HttpStatus s, String msg, HttpServletRequest req) {
        return ResponseEntity.status(s).body(new ApiError(Instant.now().toString(), s.value(), s.getReasonPhrase(), msg, req.getRequestURI()));
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ApiError> invalid(InvalidRequestException e, HttpServletRequest r) { return build(HttpStatus.BAD_REQUEST, e.getMessage(), r); }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException e, HttpServletRequest r) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, msg, r);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> unreadable(Exception e, HttpServletRequest r) { return build(HttpStatus.BAD_REQUEST, "Malformed request", r); }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> notFound(NotFoundException e, HttpServletRequest r) { return build(HttpStatus.NOT_FOUND, e.getMessage(), r); }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ApiError> conflict(ConflictException e, HttpServletRequest r) { return build(HttpStatus.CONFLICT, e.getMessage(), r); }

    @ExceptionHandler(ForbiddenOperationException.class)
    ResponseEntity<ApiError> forbidden(ForbiddenOperationException e, HttpServletRequest r) { return build(HttpStatus.FORBIDDEN, e.getMessage(), r); }

    @ExceptionHandler({ClusterUnavailableException.class, NodeUnavailableException.class})
    ResponseEntity<ApiError> unavailable(CacheException e, HttpServletRequest r) { return build(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), r); }

    @ExceptionHandler(CacheException.class)
    ResponseEntity<ApiError> cache(CacheException e, HttpServletRequest r) {
        log.warn("Cache error: {}", e.getMessage());
        return build(HttpStatus.BAD_GATEWAY, e.getMessage(), r);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> other(Exception e, HttpServletRequest r) {
        log.error("Unexpected error on {}", r.getRequestURI(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error", r);   // details only in the server log
    }
}
