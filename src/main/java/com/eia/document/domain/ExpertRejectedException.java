package com.eia.document.domain;

/**
 * The expert rejected the command with a 4xx and a structured business code.
 * The code is preserved so callers see the same rule identifier from either
 * tier.
 */
public class ExpertRejectedException extends RuntimeException {
    private final String code;

    public ExpertRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
