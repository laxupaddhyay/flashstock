package com.flashstock.api;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(ApiException.class)
    ProblemDetail api(ApiException e) { return detail(e.getStatus(), e.getCode(), e.getMessage()); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream().map(error -> error.getField() + ": " + error.getDefaultMessage()).findFirst().orElse("Request validation failed");
        return detail(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }
    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail constraint(ConstraintViolationException e) { return detail(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed"); }
    @ExceptionHandler({MissingRequestHeaderException.class, HttpMessageNotReadableException.class})
    ProblemDetail malformed(Exception e) { return detail(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "A required header or valid JSON request body is missing"); }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail badParameter(MethodArgumentTypeMismatchException e) { return detail(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "A request path or parameter has an invalid value"); }
    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail noResource(NoResourceFoundException e) { return detail(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "The requested resource was not found"); }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ProblemDetail methodNotAllowed(HttpRequestMethodNotSupportedException e) { return detail(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "The HTTP method is not supported for this endpoint"); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException e) { return detail(HttpStatus.CONFLICT, "DATA_CONFLICT", "The request conflicts with stored data"); }
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unhandled request failure correlationId={} exceptionType={}", MDC.get("correlationId"), e.getClass().getName());
        return detail(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "The request could not be completed");
    }
    private ProblemDetail detail(HttpStatus status, String code, String message) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setType(URI.create("https://flashstock.dev/problems/" + code.toLowerCase().replace('_', '-')));
        detail.setProperty("code", code);
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) detail.setProperty("correlationId", correlationId);
        return detail;
    }
}
