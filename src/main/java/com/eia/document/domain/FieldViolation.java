package com.eia.document.domain;

/**
 * One field-level validation failure inside an {@link ApiError}.
 */
public record FieldViolation(String field, String message) {}
