package com.eia.document.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DocumentLinkApiTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID DOCUMENT_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID LINK_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID LINKED_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final Instant CREATED_AT = Instant.parse("2026-10-20T09:30:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentExpertClient client;

    @Test
    void listsTheLinksOfADocument() throws Exception {
        when(client.findLinks(TENANT, DOCUMENT_ID)).thenReturn(List.of(link()));

        mockMvc.perform(get("/api/v1/documents/{documentId}/links", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(LINK_ID.toString()))
                .andExpect(jsonPath("$[0].documentId").value(DOCUMENT_ID.toString()))
                .andExpect(jsonPath("$[0].linkedType").value("POLICY"))
                .andExpect(jsonPath("$[0].linkedId").value(LINKED_ID.toString()))
                .andExpect(jsonPath("$[0].linkedReference").value("POL-2026-0041827"))
                .andExpect(jsonPath("$[0].linkedLabel").value("Acme CGL policy 2026"))
                .andExpect(jsonPath("$[0].createdAt").value("2026-10-20T09:30:00Z"));

        verify(client).findLinks(TENANT, DOCUMENT_ID);
    }

    @Test
    void createsALinkWithThePublicCommandAndReturns201() throws Exception {
        when(client.createLink(eq(TENANT), eq(DOCUMENT_ID), any(CreateDocumentLinkCommand.class)))
                .thenReturn(link());

        mockMvc.perform(post("/api/v1/documents/{documentId}/links", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"linkedType":"POLICY","linkedId":"44444444-4444-7444-8444-444444444444",
                                 "linkedReference":"POL-2026-0041827"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(LINK_ID.toString()))
                .andExpect(jsonPath("$.linkedType").value("POLICY"))
                .andExpect(jsonPath("$.linkedLabel").value("Acme CGL policy 2026"));

        ArgumentCaptor<CreateDocumentLinkCommand> command =
                ArgumentCaptor.forClass(CreateDocumentLinkCommand.class);
        verify(client).createLink(eq(TENANT), eq(DOCUMENT_ID), command.capture());
        assertEquals("POLICY", command.getValue().linkedType());
        assertEquals(LINKED_ID, command.getValue().linkedId());
        assertEquals("POL-2026-0041827", command.getValue().linkedReference());
    }

    @Test
    void deletesALinkAndReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/documents/{documentId}/links/{linkId}", DOCUMENT_ID, LINK_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isNoContent());

        verify(client).deleteLink(TENANT, DOCUMENT_ID, LINK_ID);
    }

    @Test
    void forwardsTheLinkFilterToTheExpert() throws Exception {
        when(client.findAll(TENANT, "POLICY", LINKED_ID.toString()))
                .thenReturn(List.of(document()));

        mockMvc.perform(get("/api/v1/documents")
                        .param("linkedType", "POLICY")
                        .param("linkedId", LINKED_ID.toString())
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(DOCUMENT_ID.toString()))
                .andExpect(jsonPath("$[0].documentNumber").value("DOC-2026-0000001"));

        verify(client).findAll(TENANT, "POLICY", LINKED_ID.toString());
    }

    @Test
    void preservesTheExpertIncompleteLinkFilterProblem() throws Exception {
        when(client.findAll(TENANT, "POLICY", null)).thenThrow(problem(400,
                "INVALID_LINK_FILTER", "Invalid link filter",
                "linkedType and linkedId must be supplied together"));

        mockMvc.perform(get("/api/v1/documents")
                        .param("linkedType", "POLICY")
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_LINK_FILTER"))
                .andExpect(jsonPath("$.detail").value("linkedType and linkedId must be supplied together"));

        verify(client).findAll(TENANT, "POLICY", null);
    }

    @Test
    void requiresTheTenantHeaderOnLinkRoutes() throws Exception {
        mockMvc.perform(get("/api/v1/documents/{documentId}/links", DOCUMENT_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));

        verifyNoInteractions(client);
    }

    @Test
    void preservesAnExpertBadRequestProblem() throws Exception {
        when(client.createLink(eq(TENANT), eq(DOCUMENT_ID), any(CreateDocumentLinkCommand.class)))
                .thenThrow(problem(400, "EIA-DOC-4001", "Validation failed",
                        "linkedType is required"));

        mockMvc.perform(post("/api/v1/documents/{documentId}/links", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"linkedType":"POLICY","linkedId":"44444444-4444-7444-8444-444444444444"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.detail").value("linkedType is required"))
                .andExpect(jsonPath("$.code").value("EIA-DOC-4001"));
    }

    @Test
    void preservesAnExpertNotFoundProblem() throws Exception {
        doThrow(problem(404, "EIA-DOC-4041", "Link not found",
                "Document link was not found")).when(client).deleteLink(TENANT, DOCUMENT_ID, LINK_ID);

        mockMvc.perform(delete("/api/v1/documents/{documentId}/links/{linkId}", DOCUMENT_ID, LINK_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Document link was not found"))
                .andExpect(jsonPath("$.code").value("EIA-DOC-4041"));
    }

    @Test
    void preservesAnExpertConflictProblem() throws Exception {
        when(client.createLink(eq(TENANT), eq(DOCUMENT_ID), any(CreateDocumentLinkCommand.class)))
                .thenThrow(problem(409, "EIA-DOC-4091", "Link already exists",
                        "The document is already linked to this policy"));

        mockMvc.perform(post("/api/v1/documents/{documentId}/links", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"linkedType":"POLICY","linkedId":"44444444-4444-7444-8444-444444444444"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("EIA-DOC-4091"));
    }

    @Test
    void rejectsAPostWithoutALinkedType() throws Exception {
        mockMvc.perform(post("/api/v1/documents/{documentId}/links", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"linkedId":"44444444-4444-7444-8444-444444444444"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("linkedType"));

        verify(client, never()).createLink(any(), any(), any());
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

    private static DocumentLinkDto link() {
        return new DocumentLinkDto(LINK_ID, DOCUMENT_ID, "POLICY", LINKED_ID,
                "POL-2026-0041827", "Acme CGL policy 2026", CREATED_AT);
    }

    private static DocumentDto document() {
        Instant timestamp = Instant.parse("2026-10-19T12:00:00Z");
        return new DocumentDto(DOCUMENT_ID, TENANT, "DOC-2026-0000001", "Policy schedule",
                "POLICY", "CONFIDENTIAL", "ACTIVE", LocalDate.of(2036, 10, 19), 1,
                timestamp, timestamp);
    }
}
