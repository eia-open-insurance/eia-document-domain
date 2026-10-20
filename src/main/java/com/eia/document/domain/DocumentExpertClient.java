package com.eia.document.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The only outbound path to the Document expert. Expert failures are translated
 * into domain errors so the public API exposes one error vocabulary.
 */
@Component
public class DocumentExpertClient {
    public static final String TENANT_HEADER = "X-EIA-Tenant";

    private final RestClient client;
    private final ObjectMapper objectMapper;

    public DocumentExpertClient(RestClient.Builder builder,
                                ObjectMapper objectMapper,
                                @Value("${eia.document.expert-url:http://localhost:8095}") String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
    }

    public List<DocumentDto> findAll(UUID tenantId) {
        List<DocumentDto> documents = handle(client.get()
                .uri(uri -> uri.path("/internal/v1/documents").queryParam("tenantId", tenantId).build()))
                .body(new ParameterizedTypeReference<List<DocumentDto>>() {});
        return documents == null ? List.of() : documents;
    }

    /**
     * Tenant-scoped list, optionally narrowed to the documents that link to one
     * target. The filter is evaluated by the expert so the domain never holds a
     * second copy of the association table. Expert 400/404/409 problems are
     * preserved for the caller.
     */
    public List<DocumentDto> findAll(UUID tenantId, String linkedType, String linkedId) {
        if (isBlank(linkedType) && isBlank(linkedId)) {
            return findAll(tenantId);
        }
        List<DocumentDto> documents = handle(client.get()
                .uri(uri -> {
                    var builder = uri.path("/internal/v1/documents").queryParam("tenantId", tenantId);
                    if (!isBlank(linkedType)) {
                        builder.queryParam("linkedType", linkedType);
                    }
                    if (!isBlank(linkedId)) {
                        builder.queryParam("linkedId", linkedId);
                    }
                    return builder.build();
                })
                .header(TENANT_HEADER, tenantId.toString()), true)
                .body(new ParameterizedTypeReference<List<DocumentDto>>() {});
        return documents == null ? List.of() : documents;
    }

    public List<DocumentLinkDto> findLinks(UUID tenantId, UUID documentId) {
        List<DocumentLinkDto> links = handle(client.get()
                .uri(uri -> uri.path("/internal/v1/documents/{documentId}/links")
                        .queryParam("tenantId", tenantId).build(documentId))
                .header(TENANT_HEADER, tenantId.toString()), true)
                .body(new ParameterizedTypeReference<List<DocumentLinkDto>>() {});
        return links == null ? List.of() : links;
    }

    public DocumentLinkDto createLink(UUID tenantId, UUID documentId,
                                      CreateDocumentLinkCommand command) {
        return handle(client.post()
                .uri(uri -> uri.path("/internal/v1/documents/{documentId}/links")
                        .queryParam("tenantId", tenantId).build(documentId))
                .header(TENANT_HEADER, tenantId.toString())
                .body(new ExpertCreateDocumentLinkRequest(command.linkedType(),
                        command.linkedId(), command.linkedReference())), true)
                .body(DocumentLinkDto.class);
    }

    public void deleteLink(UUID tenantId, UUID documentId, UUID linkId) {
        handle(client.delete()
                .uri(uri -> uri.path("/internal/v1/documents/{documentId}/links/{linkId}")
                        .queryParam("tenantId", tenantId).build(documentId, linkId))
                .header(TENANT_HEADER, tenantId.toString()), true)
                .toBodilessEntity();
    }

    /**
     * Expert-backed search. The expert matches document metadata and link
     * references, so this is the preferred path when the request term may be a
     * {@code linkedReference}. An absent route (404/405) is reported as
     * {@link Optional#empty()} so the service can fall back to the legacy
     * tenant list projection.
     */
    public Optional<DocumentSearchResponse> search(UUID tenantId, String q, int limit) {
        try {
            DocumentSearchResponse response = client.get()
                    .uri(uri -> {
                        var builder = uri.path("/internal/v1/documents/search")
                                .queryParam("tenantId", tenantId)
                                .queryParam("limit", limit);
                        if (!isBlank(q)) {
                            builder.queryParam("q", q);
                        }
                        return builder.build();
                    })
                    .header(TENANT_HEADER, tenantId.toString())
                    .retrieve()
                    .body(DocumentSearchResponse.class);
            return Optional.ofNullable(response);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 404 || status == 405) {
                return Optional.empty();
            }
            if (ex.getStatusCode().is5xxServerError()) {
                throw new ExpertUnavailableException("Document expert failed with status " + status);
            }
            throw new ExpertProblemException(status, readBody(ex.getResponseBodyAsString()));
        } catch (ResourceAccessException ex) {
            throw new ExpertUnavailableException("Document expert is unavailable");
        }
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
        return handle(spec, false);
    }

    /**
     * @param preserveProblems when true, every 4xx keeps the expert's status and
     *                         {@code application/problem+json} body; otherwise
     *                         the legacy document/status translation applies.
     */
    private RestClient.ResponseSpec handle(RestClient.RequestHeadersSpec<?> spec,
                                           boolean preserveProblems) {
        RestClient.ResponseSpec response = spec.retrieve();
        if (preserveProblems) {
            response = response.onStatus(HttpStatusCode::is4xxClientError, (request, expertResponse) -> {
                throw new ExpertProblemException(expertResponse.getStatusCode().value(),
                        readBody(expertResponse));
            });
        }
        return response
                .onStatus(status -> status.value() == 404, (request, expertResponse) -> {
                    throw new DocumentNotFoundException(readMessage(expertResponse));
                })
                .onStatus(status -> status.value() == 409, (request, expertResponse) -> {
                    throw new VersionConflictException(readMessage(expertResponse));
                })
                .onStatus(HttpStatusCode::is4xxClientError, (request, expertResponse) -> {
                    JsonNode body = readBody(expertResponse);
                    String code = text(body, "code", "EXPERT_REJECTED");
                    String message = text(body, "message", "The document expert rejected the request");
                    throw new ExpertRejectedException(code, message);
                })
                .onStatus(HttpStatusCode::is5xxServerError, (request, expertResponse) -> {
                    throw new ExpertUnavailableException("Document expert failed with status "
                            + expertResponse.getStatusCode().value());
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

    private JsonNode readBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (IOException ex) {
            return null;
        }
    }

    private static String text(JsonNode body, String field, String fallback) {
        return body != null && body.hasNonNull(field) ? body.get(field).asText() : fallback;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record ExpertCreateDocumentRequest(
            UUID tenantId,
            String title,
            String documentType,
            String classification,
            LocalDate retentionUntil
    ) {}

    public record ExpertStatusRequest(String status, Integer expectedVersion) {}

    /**
     * Internal create payload. The tenant travels on the query parameter and
     * the {@code X-EIA-Tenant} header; like the expert's own request record, the
     * body carries only the three public fields.
     */
    public record ExpertCreateDocumentLinkRequest(
            String linkedType,
            UUID linkedId,
            String linkedReference
    ) {}
}
