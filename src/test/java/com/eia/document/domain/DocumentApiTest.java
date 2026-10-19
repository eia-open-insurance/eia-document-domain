package com.eia.document.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DocumentApiTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID DOCUMENT_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final LocalDate RETENTION = LocalDate.of(2036, 10, 19);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentExpertClient client;

    @Test
    void rejectsRequestsWithoutTenantHeader() throws Exception {
        mockMvc.perform(get("/api/v1/documents"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));

        verifyNoInteractions(client);
    }

    @Test
    void rejectsNonUuidTenantHeader() throws Exception {
        mockMvc.perform(get("/api/v1/documents").header("X-EIA-Tenant", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TENANT_HEADER"));

        verifyNoInteractions(client);
    }

    @Test
    void listsDocumentsForTenant() throws Exception {
        when(client.findAll(TENANT)).thenReturn(List.of(dto("ACTIVE", RETENTION, 1)));

        mockMvc.perform(get("/api/v1/documents").header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].documentNumber").value("DOC-2026-0000001"))
                .andExpect(jsonPath("$[0].documentType").value("POLICY"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void createsDocumentThroughExpert() throws Exception {
        when(client.create(eq(TENANT), any(CreateDocumentCommand.class)))
                .thenReturn(dto("RECEIVED", RETENTION, 0));

        mockMvc.perform(post("/api/v1/documents")
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Policy schedule","documentType":"POLICY",
                                 "classification":"CONFIDENTIAL","retentionUntil":"2036-10-19"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentNumber").value("DOC-2026-0000001"))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.version").value(0));

        verify(client).create(eq(TENANT), any(CreateDocumentCommand.class));
    }

    @Test
    void rejectsUnknownDocumentTypeBeforeCallingExpert() throws Exception {
        mockMvc.perform(post("/api/v1/documents")
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Mystery","documentType":"NOT_A_TYPE","classification":"INTERNAL"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DOCUMENT_TYPE"));

        verifyNoInteractions(client);
    }

    @Test
    void rejectsUnknownClassificationBeforeCallingExpert() throws Exception {
        mockMvc.perform(post("/api/v1/documents")
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Policy","documentType":"POLICY","classification":"TOP_SECRET"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CLASSIFICATION"));

        verifyNoInteractions(client);
    }

    @Test
    void rejectsRetentionDateBeforeCreation() throws Exception {
        mockMvc.perform(post("/api/v1/documents")
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Old policy","documentType":"POLICY",
                                 "classification":"INTERNAL","retentionUntil":"2001-01-01"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RETENTION_BEFORE_CREATION"));

        verifyNoInteractions(client);
    }

    @Test
    void rejectsMissingTitleWithFieldDetails() throws Exception {
        mockMvc.perform(post("/api/v1/documents")
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"","documentType":"POLICY","classification":"INTERNAL"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("title"));

        verifyNoInteractions(client);
    }

    @Test
    void mapsExpertNotFoundTo404() throws Exception {
        when(client.findById(TENANT, DOCUMENT_ID))
                .thenThrow(new DocumentNotFoundException("Document not found: " + DOCUMENT_ID));

        mockMvc.perform(get("/api/v1/documents/{id}", DOCUMENT_ID).header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void rejectsIllegalLifecycleTransition() throws Exception {
        when(client.findById(TENANT, DOCUMENT_ID)).thenReturn(dto("RECEIVED", RETENTION, 0));

        mockMvc.perform(post("/api/v1/documents/{id}/status", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ACTIVE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        verifyNoInteractionsForStatusChange();
    }

    @Test
    void requiresRetentionBeforeActivation() throws Exception {
        when(client.findById(TENANT, DOCUMENT_ID)).thenReturn(dto("CLASSIFIED", null, 0));

        mockMvc.perform(post("/api/v1/documents/{id}/status", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ACTIVE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RETENTION_REQUIRED_FOR_ACTIVE"));

        verifyNoInteractionsForStatusChange();
    }

    @Test
    void advancesStatusWithOptimisticVersion() throws Exception {
        when(client.findById(TENANT, DOCUMENT_ID)).thenReturn(dto("CLASSIFIED", RETENTION, 1));
        when(client.changeStatus(TENANT, DOCUMENT_ID, "ACTIVE", 1)).thenReturn(dto("ACTIVE", RETENTION, 2));

        mockMvc.perform(post("/api/v1/documents/{id}/status", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ACTIVE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(2));

        verify(client).changeStatus(TENANT, DOCUMENT_ID, "ACTIVE", 1);
    }

    @Test
    void mapsExpertVersionConflictTo409() throws Exception {
        when(client.findById(TENANT, DOCUMENT_ID)).thenReturn(dto("ACTIVE", RETENTION, 1));
        when(client.changeStatus(TENANT, DOCUMENT_ID, "ARCHIVED", 1))
                .thenThrow(new VersionConflictException("document changed concurrently"));

        mockMvc.perform(post("/api/v1/documents/{id}/status", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ARCHIVED"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/v1/documents")
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    private void verifyNoInteractionsForStatusChange() {
        verify(client).findById(TENANT, DOCUMENT_ID);
        verify(client, never()).changeStatus(any(), any(), any(), anyInt());
    }

    private static DocumentDto dto(String status, LocalDate retentionUntil, int version) {
        Instant timestamp = Instant.parse("2026-10-19T12:00:00Z");
        return new DocumentDto(DOCUMENT_ID, TENANT, "DOC-2026-0000001", "Policy schedule",
                "POLICY", "CONFIDENTIAL", status, retentionUntil, version, timestamp, timestamp);
    }
}
