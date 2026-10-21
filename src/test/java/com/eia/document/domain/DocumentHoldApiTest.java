package com.eia.document.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Public API tests for legal holds and disposition. The expert client is
 * mocked so the route, tenant header, projection fields, validation, and
 * problem preservation are exercised without a running expert.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DocumentHoldApiTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID DOCUMENT_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final Instant CREATED_AT = Instant.parse("2026-10-19T12:00:00Z");
    private static final Instant HELD_AT = Instant.parse("2026-10-21T18:00:00Z");
    private static final Instant DISPOSED_AT = Instant.parse("2026-10-21T19:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentExpertClient client;

    @Test
    void placesALegalHoldAndReturnsTheRecordedProjection() throws Exception {
        when(client.hold(TENANT, DOCUMENT_ID, "Litigation hold - matter M-2026-0042"))
                .thenReturn(held());

        mockMvc.perform(post("/api/v1/documents/{documentId}/hold", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"Litigation hold - matter M-2026-0042"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ON_HOLD"))
                .andExpect(jsonPath("$.holdReason").value("Litigation hold - matter M-2026-0042"))
                .andExpect(jsonPath("$.heldAt").value("2026-10-21T18:00:00Z"))
                .andExpect(jsonPath("$.statusBeforeHold").value("ACTIVE"))
                .andExpect(jsonPath("$.disposedAt").doesNotExist())
                .andExpect(jsonPath("$.version").value(2));

        verify(client).hold(TENANT, DOCUMENT_ID, "Litigation hold - matter M-2026-0042");
    }

    @Test
    void trimsTheHoldReasonBeforeForwarding() throws Exception {
        when(client.hold(TENANT, DOCUMENT_ID, "Litigation hold"))
                .thenReturn(held());

        mockMvc.perform(post("/api/v1/documents/{documentId}/hold", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"  Litigation hold  "}
                                """))
                .andExpect(status().isOk());

        verify(client).hold(TENANT, DOCUMENT_ID, "Litigation hold");
    }

    @Test
    void releasesTheHoldAndRestoresThePriorStatus() throws Exception {
        when(client.releaseHold(TENANT, DOCUMENT_ID)).thenReturn(released());

        mockMvc.perform(post("/api/v1/documents/{documentId}/hold/release", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.holdReason").doesNotExist())
                .andExpect(jsonPath("$.heldAt").doesNotExist())
                .andExpect(jsonPath("$.statusBeforeHold").doesNotExist())
                .andExpect(jsonPath("$.version").value(3));

        verify(client).releaseHold(TENANT, DOCUMENT_ID);
    }

    @Test
    void disposesAndReturnsTheTombstone() throws Exception {
        when(client.dispose(TENANT, DOCUMENT_ID)).thenReturn(disposed());

        mockMvc.perform(post("/api/v1/documents/{documentId}/dispose", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISPOSED"))
                .andExpect(jsonPath("$.disposedAt").value("2026-10-21T19:00:00Z"))
                .andExpect(jsonPath("$.version").value(4));

        verify(client).dispose(TENANT, DOCUMENT_ID);
    }

    @Test
    void requiresAReasonToPlaceAHold() throws Exception {
        mockMvc.perform(post("/api/v1/documents/{documentId}/hold", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("reason"));

        verify(client, never()).hold(any(), any(), any());
    }

    @Test
    void requiresTheTenantHeaderOnHoldRoutes() throws Exception {
        mockMvc.perform(post("/api/v1/documents/{documentId}/hold", DOCUMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"Litigation hold"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));

        verifyNoInteractions(client);
    }

    @Test
    void preservesAnExpertBadRequestProblemOnHold() throws Exception {
        when(client.hold(eq(TENANT), eq(DOCUMENT_ID), any()))
                .thenThrow(problem(400, "INVALID_HOLD_REASON", "Validation failed",
                        "reason is required"));

        mockMvc.perform(post("/api/v1/documents/{documentId}/hold", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"Litigation hold"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_HOLD_REASON"))
                .andExpect(jsonPath("$.detail").value("reason is required"));
    }

    @Test
    void preservesAnExpertNotFoundProblemOnRelease() throws Exception {
        when(client.releaseHold(TENANT, DOCUMENT_ID))
                .thenThrow(problem(404, "EIA-DOC-4041", "Document not found",
                        "Document was not found"));

        mockMvc.perform(post("/api/v1/documents/{documentId}/hold/release", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("EIA-DOC-4041"));
    }

    @Test
    void preservesAnExpertConflictProblemOnDispose() throws Exception {
        when(client.dispose(TENANT, DOCUMENT_ID))
                .thenThrow(problem(409, "DOCUMENT_ON_LEGAL_HOLD", "Document on legal hold",
                        "document is under legal hold; disposition is blocked (SPEC DOC-06)"));

        mockMvc.perform(post("/api/v1/documents/{documentId}/dispose", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("DOCUMENT_ON_LEGAL_HOLD"))
                .andExpect(jsonPath("$.detail")
                        .value("document is under legal hold; disposition is blocked (SPEC DOC-06)"));
    }

    @Test
    void turnsTheExpertsConflictApiErrorIntoAProblemJson() throws Exception {
        when(client.hold(TENANT, DOCUMENT_ID, "Litigation hold"))
                .thenThrow(new ExpertProblemException(409, MAPPER.readTree("""
                        {"code": "DOCUMENT_ALREADY_ON_HOLD",
                         "message": "document 22222222-2222-7222-8222-222222222222 is already ON_HOLD",
                         "details": []}
                        """)));

        mockMvc.perform(post("/api/v1/documents/{documentId}/hold", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"Litigation hold"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.code").value("DOCUMENT_ALREADY_ON_HOLD"))
                .andExpect(jsonPath("$.detail")
                        .value("document 22222222-2222-7222-8222-222222222222 is already ON_HOLD"));
    }

    private static ExpertProblemException problem(int status, String code, String title,
                                                  String detail) {
        try {
            return new ExpertProblemException(status, MAPPER.readTree("""
                    {"type":"https://errors.eia.example/%s","title":"%s","status":%d,
                     "detail":"%s","code":"%s","category":"%s"}
                    """.formatted(code.toLowerCase(), title, status, detail, code,
                    status == 409 ? "CONFLICT" : status == 404 ? "NOT_FOUND" : "VALIDATION")));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static DocumentDto held() {
        return new DocumentDto(DOCUMENT_ID, TENANT, "DOC-2026-0000001", "Policy schedule",
                "POLICY", "CONFIDENTIAL", "ON_HOLD", LocalDate.of(2036, 10, 19),
                "Litigation hold - matter M-2026-0042", HELD_AT, "ACTIVE", null,
                2, CREATED_AT, HELD_AT);
    }

    private static DocumentDto released() {
        Instant releasedAt = HELD_AT.plusSeconds(1800);
        return new DocumentDto(DOCUMENT_ID, TENANT, "DOC-2026-0000001", "Policy schedule",
                "POLICY", "CONFIDENTIAL", "ACTIVE", LocalDate.of(2036, 10, 19),
                null, null, null, null, 3, CREATED_AT, releasedAt);
    }

    private static DocumentDto disposed() {
        return new DocumentDto(DOCUMENT_ID, TENANT, "DOC-2026-0000001", "Policy schedule",
                "POLICY", "CONFIDENTIAL", "DISPOSED", LocalDate.of(2026, 10, 1),
                null, null, null, DISPOSED_AT, 4, CREATED_AT, DISPOSED_AT);
    }
}
