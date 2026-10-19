package com.eia.document.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Structured error envelope: a stable business code, a human-readable message,
 * and optional field-level details.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(String code, String message, List<FieldViolation> details) {
    public ApiError(String code, String message) {
        this(code, message, List.of());
    }
}
