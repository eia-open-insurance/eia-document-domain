package com.eia.document.domain;

/**
 * Raised when the expert reports that a document does not exist for the tenant.
 */
public class DocumentNotFoundException extends RuntimeException {
    public DocumentNotFoundException(String message) {
        super(message);
    }
}
