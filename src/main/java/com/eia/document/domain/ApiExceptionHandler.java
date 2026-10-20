package com.eia.document.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * Turns domain failures into the structured {@link ApiError} envelope. Link
 * and filter failures that the expert already expressed as a problem keep their
 * status and {@code application/problem+json} body instead.
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

    /**
     * Link routes are a proxy surface. A 400, 404, or 409 from the expert is
     * returned with its original status and an
     * {@code application/problem+json} body; only the members needed for a
     * valid problem are filled in when the expert omitted them.
     */
    @ExceptionHandler(ExpertProblemException.class)
    public ResponseEntity<JsonNode> expertProblem(ExpertProblemException ex) {
        ObjectNode problem = ex.body() instanceof ObjectNode expertBody
                ? expertBody.deepCopy()
                : JsonNodeFactory.instance.objectNode();
        problem.put("status", ex.status());
        if (isBlank(problem.get("title"))) {
            HttpStatus status = HttpStatus.resolve(ex.status());
            problem.put("title", status == null ? "Document expert request failed"
                    : status.getReasonPhrase());
        }
        if (isBlank(problem.get("detail"))) {
            String message = problem.hasNonNull("message")
                    ? problem.get("message").asText() : null;
            problem.put("detail", message == null || message.isBlank()
                    ? "The document expert rejected the request" : message);
        }
        if (isBlank(problem.get("type"))) {
            problem.put("type", "about:blank");
        }
        return ResponseEntity.status(ex.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static boolean isBlank(JsonNode node) {
        return node == null || node.isNull() || node.asText().isBlank();
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
