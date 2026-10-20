package com.eia.document.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DocumentController.class)
@Import(DocumentService.class)
class DocumentSearchTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID DOCUMENT_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final Instant BASE = Instant.parse("2026-10-19T12:00:00Z");
    private static final LocalDate RETENTION = LocalDate.of(2036, 10, 19);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DocumentExpertClient client;

    @Test
    void matchesByDocumentNumber() throws Exception {
        when(client.findAll(TENANT)).thenReturn(List.of(
                document(DOCUMENT_ID, TENANT, "DOC-2026-0000001", "Policy schedule", "POLICY",
                        "RECEIVED", BASE),
                document(UUID.randomUUID(), TENANT, "DOC-2026-0000002", "Loss run", "LOSS_ADVICE",
                        "RECEIVED", BASE.plusSeconds(5))));

        mvc.perform(get("/api/v1/documents/search")
                        .param("q", "doc-2026-0000001")
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(DOCUMENT_ID.toString()))
                .andExpect(jsonPath("$.items[0].label").value("DOC-2026-0000001"));
    }

    @Test
    void matchesByTitleCaseInsensitively() throws Exception {
        when(client.findAll(TENANT)).thenReturn(List.of(
                document(DOCUMENT_ID, TENANT, "DOC-2026-0000001", "Acme CGL policy 2026", "POLICY",
                        "RECEIVED", BASE),
                document(UUID.randomUUID(), TENANT, "DOC-2026-0000002", "Loss run", "LOSS_ADVICE",
                        "RECEIVED", BASE)));

        mvc.perform(get("/api/v1/documents/search")
                        .param("q", "ACME cgl")
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(DOCUMENT_ID.toString()));
    }

    @Test
    void matchesByDocumentType() throws Exception {
        when(client.findAll(TENANT)).thenReturn(List.of(
                document(DOCUMENT_ID, TENANT, "DOC-2026-0000001", "Schedule", "LOSS_ADVICE",
                        "RECEIVED", BASE),
                document(UUID.randomUUID(), TENANT, "DOC-2026-0000002", "Loss run", "POLICY",
                        "RECEIVED", BASE)));

        mvc.perform(get("/api/v1/documents/search")
                        .param("q", "loss_advice")
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(DOCUMENT_ID.toString()));
    }

    @Test
    void blankQueryReturnsLatestTenByUpdatedAtDesc() throws Exception {
        when(client.findAll(TENANT)).thenReturn(documents(12));

        mvc.perform(get("/api/v1/documents/search").header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value(""))
                .andExpect(jsonPath("$.limit").value(10))
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.items[0].label").value("DOC-2026-0000012"))
                .andExpect(jsonPath("$.items[9].label").value("DOC-2026-0000003"));
    }

    @Test
    void appliesRequestedLimit() throws Exception {
        when(client.findAll(TENANT)).thenReturn(documents(5));

        mvc.perform(get("/api/v1/documents/search")
                        .param("limit", "2")
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].label").value("DOC-2026-0000005"))
                .andExpect(jsonPath("$.items[1].label").value("DOC-2026-0000004"));
    }

    @Test
    void capsLimitAtTwentyFive() throws Exception {
        when(client.findAll(TENANT)).thenReturn(documents(30));

        mvc.perform(get("/api/v1/documents/search")
                        .param("limit", "100")
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(25))
                .andExpect(jsonPath("$.items.length()").value(25));
    }

    @Test
    void clampsNonPositiveLimit() throws Exception {
        when(client.findAll(TENANT)).thenReturn(documents(3));

        mvc.perform(get("/api/v1/documents/search")
                        .param("limit", "0")
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(1))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void doesNotReturnAnotherTenantsDocuments() throws Exception {
        DocumentDto mine = document(DOCUMENT_ID, TENANT, "DOC-2026-0000001", "Acme CGL policy 2026",
                "POLICY", "RECEIVED", BASE);
        DocumentDto foreign = document(UUID.randomUUID(), OTHER_TENANT, "DOC-2026-0000099",
                "Acme CGL policy 2025", "POLICY", "ACTIVE", BASE.plusSeconds(30));
        when(client.findAll(TENANT)).thenReturn(List.of(mine, foreign));

        mvc.perform(get("/api/v1/documents/search")
                        .param("q", "acme")
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(DOCUMENT_ID.toString()));

        verify(client).findAll(TENANT);
    }

    @Test
    void returnsCommonProjectionAndHref() throws Exception {
        when(client.findAll(TENANT)).thenReturn(List.of(document(DOCUMENT_ID, TENANT,
                "DOC-2026-0000001", "Acme CGL policy 2026", "POLICY", "RECEIVED", BASE)));

        mvc.perform(get("/api/v1/documents/search")
                        .param("q", "acme")
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("acme"))
                .andExpect(jsonPath("$.limit").value(10))
                .andExpect(jsonPath("$.items[0].id").value(DOCUMENT_ID.toString()))
                .andExpect(jsonPath("$.items[0].type").value("DOCUMENT"))
                .andExpect(jsonPath("$.items[0].label").value("DOC-2026-0000001"))
                .andExpect(jsonPath("$.items[0].secondary").value("Acme CGL policy 2026 · RECEIVED"))
                .andExpect(jsonPath("$.items[0].status").value("RECEIVED"))
                .andExpect(jsonPath("$.items[0].href").value("/document?record=" + DOCUMENT_ID));
    }

    @Test
    void rejectsRequestsWithoutTenantHeader() throws Exception {
        mvc.perform(get("/api/v1/documents/search").param("q", "acme"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
    }

    private static List<DocumentDto> documents(int count) {
        List<DocumentDto> documents = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            documents.add(document(UUID.randomUUID(), TENANT, "DOC-2026-%07d".formatted(i),
                    "Title " + i, "POLICY", "RECEIVED", BASE.plusSeconds(i)));
        }
        return documents;
    }

    private static DocumentDto document(UUID id, UUID tenantId, String number, String title,
                                        String type, String status, Instant updatedAt) {
        return new DocumentDto(id, tenantId, number, title, type, "INTERNAL", status, RETENTION,
                0, BASE, updatedAt);
    }
}
