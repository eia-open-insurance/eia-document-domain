package com.eia.document.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Wire-level tests for the legal-hold and disposition proxy: exact expert URLs,
 * tenant forwarding on the query string and header, the hold reason payload,
 * the extended document projection, and preservation of the expert's
 * 400/404/409 problems.
 */
class DocumentHoldClientTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID DOCUMENT_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final String EXPERT = "http://expert.test";
    private static final String HOLD_URL = EXPERT + "/api/v1/documents/"
            + DOCUMENT_ID + "/hold?tenantId=" + TENANT;
    private static final String RELEASE_URL = EXPERT + "/api/v1/documents/"
            + DOCUMENT_ID + "/hold/release?tenantId=" + TENANT;
    private static final String DISPOSE_URL = EXPERT + "/api/v1/documents/"
            + DOCUMENT_ID + "/dispose?tenantId=" + TENANT;
    private static final String REASON = "Litigation hold - matter M-2026-0042";
    private static final Instant HELD_AT = Instant.parse("2026-10-21T18:00:00Z");
    private static final Instant DISPOSED_AT = Instant.parse("2026-10-21T19:00:00Z");

    private static final String HELD_JSON = """
            {
              "id": "22222222-2222-7222-8222-222222222222",
              "tenantId": "11111111-1111-7111-8111-111111111111",
              "documentNumber": "DOC-2026-0000001",
              "title": "Policy schedule",
              "documentType": "POLICY",
              "classification": "CONFIDENTIAL",
              "status": "ON_HOLD",
              "retentionUntil": "2036-10-19",
              "version": 2,
              "createdAt": "2026-10-19T12:00:00Z",
              "updatedAt": "2026-10-21T18:00:00Z",
              "holdReason": "Litigation hold - matter M-2026-0042",
              "heldAt": "2026-10-21T18:00:00Z",
              "statusBeforeHold": "ACTIVE",
              "disposedAt": null
            }
            """;
    private static final String RELEASED_JSON = """
            {
              "id": "22222222-2222-7222-8222-222222222222",
              "tenantId": "11111111-1111-7111-8111-111111111111",
              "documentNumber": "DOC-2026-0000001",
              "title": "Policy schedule",
              "documentType": "POLICY",
              "classification": "CONFIDENTIAL",
              "status": "ACTIVE",
              "retentionUntil": "2036-10-19",
              "version": 3,
              "createdAt": "2026-10-19T12:00:00Z",
              "updatedAt": "2026-10-21T18:30:00Z",
              "holdReason": null,
              "heldAt": null,
              "statusBeforeHold": null,
              "disposedAt": null
            }
            """;
    private static final String DISPOSED_JSON = """
            {
              "id": "22222222-2222-7222-8222-222222222222",
              "tenantId": "11111111-1111-7111-8111-111111111111",
              "documentNumber": "DOC-2026-0000001",
              "title": "Policy schedule",
              "documentType": "POLICY",
              "classification": "CONFIDENTIAL",
              "status": "DISPOSED",
              "retentionUntil": "2026-10-01",
              "version": 4,
              "createdAt": "2026-10-19T12:00:00Z",
              "updatedAt": "2026-10-21T19:00:00Z",
              "holdReason": null,
              "heldAt": null,
              "statusBeforeHold": null,
              "disposedAt": "2026-10-21T19:00:00Z"
            }
            """;

    private MockRestServiceServer server;
    private DocumentExpertClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .messageConverters(converters -> {
                    ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
                            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                            .build();
                    converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
                    converters.add(0, new MappingJackson2HttpMessageConverter(mapper));
                });
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DocumentExpertClient(builder, new ObjectMapper(), EXPERT);
    }

    @Test
    void placesAHoldWithTenantForwardedAndDecodesTheProjection() {
        server.expect(requestTo(HOLD_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(DocumentExpertClient.TENANT_HEADER, TENANT.toString()))
                .andExpect(content().json("""
                        {"reason": "Litigation hold - matter M-2026-0042"}
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess(HELD_JSON, MediaType.APPLICATION_JSON));

        DocumentDto document = client.hold(TENANT, DOCUMENT_ID, REASON);

        assertEquals("ON_HOLD", document.status());
        assertEquals(REASON, document.holdReason());
        assertEquals(HELD_AT, document.heldAt());
        assertEquals("ACTIVE", document.statusBeforeHold());
        assertNull(document.disposedAt());
        assertEquals(2, document.version());
        server.verify();
    }

    @Test
    void releasesAHoldWithTenantForwardedAndRestoresTheSnapshot() {
        server.expect(requestTo(RELEASE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(DocumentExpertClient.TENANT_HEADER, TENANT.toString()))
                .andRespond(withSuccess(RELEASED_JSON, MediaType.APPLICATION_JSON));

        DocumentDto document = client.releaseHold(TENANT, DOCUMENT_ID);

        assertEquals("ACTIVE", document.status());
        assertNull(document.holdReason());
        assertNull(document.heldAt());
        assertNull(document.statusBeforeHold());
        assertEquals(3, document.version());
        server.verify();
    }

    @Test
    void disposesWithTenantForwardedAndDecodesTheTombstone() {
        server.expect(requestTo(DISPOSE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(DocumentExpertClient.TENANT_HEADER, TENANT.toString()))
                .andRespond(withSuccess(DISPOSED_JSON, MediaType.APPLICATION_JSON));

        DocumentDto document = client.dispose(TENANT, DOCUMENT_ID);

        assertEquals("DISPOSED", document.status());
        assertEquals(DISPOSED_AT, document.disposedAt());
        assertNull(document.holdReason());
        server.verify();
    }

    @Test
    void preservesAnExpertBadRequestProblemOnHold() {
        server.expect(requestTo(HOLD_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("""
                                {"type": "https://errors.eia.example/eia-doc-4001",
                                 "title": "Validation failed", "status": 400,
                                 "detail": "reason is required",
                                 "code": "INVALID_HOLD_REASON", "category": "VALIDATION"}
                                """));

        ExpertProblemException exception = assertThrows(ExpertProblemException.class,
                () -> client.hold(TENANT, DOCUMENT_ID, " "));

        assertEquals(400, exception.status());
        assertEquals("INVALID_HOLD_REASON", exception.body().get("code").asText());
        assertEquals("reason is required", exception.getMessage());
        server.verify();
    }

    @Test
    void preservesAnExpertNotFoundProblemOnRelease() {
        server.expect(requestTo(RELEASE_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("""
                                {"type": "https://errors.eia.example/eia-doc-4041",
                                 "title": "Document not found", "status": 404,
                                 "detail": "Document 22222222-2222-7222-8222-222222222222 was not found",
                                 "code": "EIA-DOC-4041", "category": "NOT_FOUND"}
                                """));

        ExpertProblemException exception = assertThrows(ExpertProblemException.class,
                () -> client.releaseHold(TENANT, DOCUMENT_ID));

        assertEquals(404, exception.status());
        assertEquals("EIA-DOC-4041", exception.body().get("code").asText());
        server.verify();
    }

    @Test
    void preservesAnExpertConflictProblemOnDispose() {
        server.expect(requestTo(DISPOSE_URL))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("""
                                {"type": "https://errors.eia.example/eia-doc-4091",
                                 "title": "Document on legal hold", "status": 409,
                                 "detail": "document is under legal hold; disposition is blocked (SPEC DOC-06)",
                                 "code": "DOCUMENT_ON_LEGAL_HOLD", "category": "CONFLICT"}
                                """));

        ExpertProblemException exception = assertThrows(ExpertProblemException.class,
                () -> client.dispose(TENANT, DOCUMENT_ID));

        assertEquals(409, exception.status());
        assertEquals("DOCUMENT_ON_LEGAL_HOLD", exception.body().get("code").asText());
        server.verify();
    }

    @Test
    void preservesAnExpertConflictProblemOnAHoldThatAlreadyExists() {
        server.expect(requestTo(HOLD_URL))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("""
                                {"type": "https://errors.eia.example/eia-doc-4092",
                                 "title": "Already on hold", "status": 409,
                                 "detail": "document 22222222-2222-7222-8222-222222222222 is already ON_HOLD",
                                 "code": "DOCUMENT_ALREADY_ON_HOLD", "category": "CONFLICT"}
                                """));

        ExpertProblemException exception = assertThrows(ExpertProblemException.class,
                () -> client.hold(TENANT, DOCUMENT_ID, REASON));

        assertEquals(409, exception.status());
        assertEquals("DOCUMENT_ALREADY_ON_HOLD", exception.body().get("code").asText());
        server.verify();
    }

    @Test
    void mapsAnExpertServerErrorToUnavailable() {
        server.expect(requestTo(DISPOSE_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThrows(ExpertUnavailableException.class,
                () -> client.dispose(TENANT, DOCUMENT_ID));

        server.verify();
    }
}
