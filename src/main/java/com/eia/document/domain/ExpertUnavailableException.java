package com.eia.document.domain;

/**
 * Raised when the expert cannot be reached or fails with a 5xx. Surfaces as 503
 * so callers can distinguish infrastructure failure from a rejected command.
 */
public class ExpertUnavailableException extends RuntimeException {
    public ExpertUnavailableException(String message) {
        super(message);
    }
}
