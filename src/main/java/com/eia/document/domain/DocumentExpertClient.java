package com.eia.document.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The only outbound path to the Document expert. Expert failures are translated
 * into domain errors so the public API exposes one error vocabulary.
 */
@Component
public class DocumentExpertClient {
    private final RestClient client;
    private final ObjectMapper objectMapper;

    public DocumentExpertClient(RestClient.Builder builder,
                                ObjectMapper objectMapper,
                                @Value("${eia.document.expert-url:http://localhost:8095}") String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
    }

    public List<DocumentDto> findAll(UUID tenantId) {
        return handle(client.get()
                .uri(uri -> uri.path("/internal/v1/documents").queryParam("tenantId", tenantId).build()))
                .body(new ParameterizedTypeReference<List<DocumentDto>>() {});
    }

    public DocumentDto findById(UUID tenantId, UUID id) {
        return handle(client.get()
                .uri(uri -> uri.path("/internal/v1/documents/{id}").queryParam("tenantId", tenantId).build(id)))
                .body(DocumentDto.class);
    }

    public DocumentDto create(UUID tenantId, CreateDocumentCommand command) {
        return handle(client.post()
                .uri("/internal/v1/documents")
                .body(new ExpertCreateDocumentRequest(tenantId, command.title(), command.documentType(),
                        command.classification(), command.retentionUntil())))
                .body(DocumentDto.class);
    }

    public DocumentDto changeStatus(UUID tenantId, UUID id, String status, int expectedVersion) {
        return handle(client.post()
                .uri(uri -> uri.path("/internal/v1/documents/{id}/status")
                        .queryParam("tenantId", tenantId).build(id))
                .body(new ExpertStatusRequest(status, expectedVersion)))
                .body(DocumentDto.class);
    }

    private RestClient.ResponseSpec handle(RestClient.RequestHeadersSpec<?> spec) {
        return spec.retrieve()
                .onStatus(status -> status.value() == 404, (request, response) -> {
                    throw new DocumentNotFoundException(readMessage(response));
                })
                .onStatus(status -> status.value() == 409, (request, response) -> {
                    throw new VersionConflictException(readMessage(response));
                })
                .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                    JsonNode body = readBody(response);
                    String code = text(body, "code", "EXPERT_REJECTED");
                    String message = text(body, "message", "The document expert rejected the request");
                    throw new ExpertRejectedException(code, message);
                })
                .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                    throw new ExpertUnavailableException("Document expert failed with status "
                            + response.getStatusCode().value());
                });
    }

    private String readMessage(ClientHttpResponse response) {
        return text(readBody(response), "message", "The document expert rejected the request");
    }

    private JsonNode readBody(ClientHttpResponse response) {
        try (InputStream body = response.getBody()) {
            byte[] bytes = body.readAllBytes();
            return bytes.length == 0 ? null : objectMapper.readTree(bytes);
        } catch (IOException ex) {
            return null;
        }
    }

    private static String text(JsonNode body, String field, String fallback) {
        return body != null && body.hasNonNull(field) ? body.get(field).asText() : fallback;
    }

    public record ExpertCreateDocumentRequest(
            UUID tenantId,
            String title,
            String documentType,
            String classification,
            LocalDate retentionUntil
    ) {}

    public record ExpertStatusRequest(String status, Integer expectedVersion) {}
}
