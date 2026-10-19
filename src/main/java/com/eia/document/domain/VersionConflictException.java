package com.eia.document.domain;

/**
 * Raised when the expert reports an optimistic concurrency conflict (HTTP 409).
 */
public class VersionConflictException extends RuntimeException {
    public VersionConflictException(String message) {
        super(message);
    }
}
