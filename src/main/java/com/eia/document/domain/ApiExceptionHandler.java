package com.eia.document.domain;

import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Comparator;
import java.util.List;

/**
 * Turns every failure into the structured {@link ApiError} envelope.
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(MissingRequestHeaderException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError missingHeader(MissingRequestHeaderException ex) {
        return new ApiError("TENANT_REQUIRED",
                "Required header '" + ex.getHeaderName() + "' is missing");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError invalidArgument(MethodArgumentTypeMismatchException ex) {
        if ("X-EIA-Tenant".equalsIgnoreCase(ex.getName())) {
            return new ApiError("INVALID_TENANT_HEADER", "X-EIA-Tenant must be a UUID");
        }
        return new ApiError("INVALID_PARAMETER", "Parameter '" + ex.getName() + "' has an invalid value");
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError notFound(DocumentNotFoundException ex) {
        return new ApiError("DOCUMENT_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(DocumentRuleException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError ruleViolation(DocumentRuleException ex) {
        return new ApiError(ex.code(), ex.getMessage());
    }

    @ExceptionHandler(ExpertRejectedException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError expertRejected(ExpertRejectedException ex) {
        return new ApiError(ex.code(), ex.getMessage());
    }

    @ExceptionHandler(VersionConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError versionConflict(VersionConflictException ex) {
        return new ApiError("VERSION_CONFLICT", ex.getMessage());
    }

    @ExceptionHandler(ExpertUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiError expertUnavailable(ExpertUnavailableException ex) {
        return new ApiError("EXPERT_UNAVAILABLE", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError validation(MethodArgumentNotValidException ex) {
        List<FieldViolation> details = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .sorted(Comparator.comparing(FieldViolation::field))
                .toList();
        return new ApiError("VALIDATION_ERROR", "Request validation failed", details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError malformed(HttpMessageNotReadableException ex) {
        return new ApiError("MALFORMED_REQUEST", "Request body is missing or is not valid JSON");
    }
}
