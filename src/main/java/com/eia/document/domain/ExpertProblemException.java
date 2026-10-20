package com.eia.document.domain;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Carries an expert {@code application/problem+json} failure through the domain
 * unchanged. Link routes are a proxy surface: a 400, 404, or 409 from the
 * expert keeps its status and problem body instead of being rewritten into a
 * domain-specific code.
 */
public class ExpertProblemException extends RuntimeException {
    private final int status;
    private final transient JsonNode body;

    public ExpertProblemException(int status, JsonNode body) {
        super(detail(body, status));
        this.status = status;
        this.body = body;
    }

    public int status() {
        return status;
    }

    public JsonNode body() {
        return body;
    }

    private static String detail(JsonNode body, int status) {
        if (body != null) {
            JsonNode detail = body.get("detail");
            if (detail != null && !detail.isNull() && !detail.asText().isBlank()) {
                return detail.asText();
            }
            JsonNode message = body.get("message");
            if (message != null && !message.isNull() && !message.asText().isBlank()) {
                return message.asText();
            }
        }
        return "The document expert rejected the request with HTTP " + status;
    }
}
