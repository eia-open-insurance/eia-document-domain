package com.eia.document.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DocumentContentVersionApiTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID DOCUMENT_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final String FILE_NAME = "policy schedule.pdf";
    private static final String DISPOSITION = "attachment; filename=\"policy schedule.pdf\"";
    private static final String SHA256 = "9f2c5d0f8f0d1c9a5f0f0f3a6b8c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f607182";
    private static final Instant UPLOADED_AT = Instant.parse("2026-10-20T10:00:00Z");
    private static final byte[] CONTENT = "%PDF-1.7 document bytes".getBytes(StandardCharsets.UTF_8);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentExpertClient client;

    @Test
    void listsTheVersionsOfADocument() throws Exception {
        when(client.contentVersions(TENANT, DOCUMENT_ID)).thenReturn(versions());

        mockMvc.perform(get("/api/v1/documents/{documentId}/content/versions", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].documentId").value(DOCUMENT_ID.toString()))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].fileName").value(FILE_NAME))
                .andExpect(jsonPath("$[0].contentType").value(MediaType.APPLICATION_PDF_VALUE))
                .andExpect(jsonPath("$[0].sizeBytes").value(CONTENT.length))
                .andExpect(jsonPath("$[0].sha256").value(SHA256))
                .andExpect(jsonPath("$[0].uploadedAt").value("2026-10-20T10:00:00Z"))
                .andExpect(jsonPath("$[1].version").value(1))
                .andExpect(jsonPath("$[1].fileName").value("policy schedule v1.pdf"));

        verify(client).contentVersions(TENANT, DOCUMENT_ID);
    }

    @Test
    void downloadsVersionBytesWithContentTypeDispositionAndShaHeaders() throws Exception {
        when(client.downloadContentVersion(TENANT, DOCUMENT_ID, 2L))
                .thenReturn(new DocumentContentDownload(CONTENT, MediaType.APPLICATION_PDF,
                        DISPOSITION, SHA256));

        mockMvc.perform(get("/api/v1/documents/{documentId}/content/versions/{version}",
                        DOCUMENT_ID, 2)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        MediaType.APPLICATION_PDF_VALUE))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, DISPOSITION))
                .andExpect(header().string(DocumentContentPolicy.CONTENT_SHA_HEADER, SHA256))
                .andExpect(content().bytes(CONTENT));

        verify(client).downloadContentVersion(TENANT, DOCUMENT_ID, 2L);
    }

    @Test
    void defaultsToAttachmentWhenAVersionOmitsTheDisposition() throws Exception {
        when(client.downloadContentVersion(TENANT, DOCUMENT_ID, 2L))
                .thenReturn(new DocumentContentDownload(CONTENT, MediaType.APPLICATION_OCTET_STREAM,
                        null, null));

        mockMvc.perform(get("/api/v1/documents/{documentId}/content/versions/{version}",
                        DOCUMENT_ID, 2)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment"));
    }

    @Test
    void forwardsTheHeaderTenantAndIgnoresAQueryTenantOnVersionRoutes() throws Exception {
        when(client.contentVersions(TENANT, DOCUMENT_ID)).thenReturn(versions());
        when(client.downloadContentVersion(TENANT, DOCUMENT_ID, 2L))
                .thenReturn(new DocumentContentDownload(CONTENT, MediaType.APPLICATION_PDF,
                        DISPOSITION, SHA256));

        mockMvc.perform(get("/api/v1/documents/{documentId}/content/versions", DOCUMENT_ID)
                        .param("tenantId", OTHER_TENANT.toString())
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/documents/{documentId}/content/versions/{version}",
                        DOCUMENT_ID, 2)
                        .param("tenantId", OTHER_TENANT.toString())
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk());

        verify(client).contentVersions(TENANT, DOCUMENT_ID);
        verify(client).downloadContentVersion(TENANT, DOCUMENT_ID, 2L);
    }

    @Test
    void requiresTheTenantHeaderOnVersionRoutes() throws Exception {
        mockMvc.perform(get("/api/v1/documents/{documentId}/content/versions", DOCUMENT_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));

        mockMvc.perform(get("/api/v1/documents/{documentId}/content/versions/{version}",
                        DOCUMENT_ID, 2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));

        verifyNoInteractions(client);
    }

    @Test
    void rejectsANonNumericVersionWithoutCallingTheExpert() throws Exception {
        mockMvc.perform(get("/api/v1/documents/{documentId}/content/versions/{version}",
                        DOCUMENT_ID, "latest")
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(client);
    }

    @Test
    void preservesAnExpertNotFoundProblemOnTheVersionList() throws Exception {
        when(client.contentVersions(TENANT, DOCUMENT_ID))
                .thenThrow(problem(404, "EIA-DOC-4041", "Document not found",
                        "Document was not found"));

        mockMvc.perform(get("/api/v1/documents/{documentId}/content/versions", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Document was not found"))
                .andExpect(jsonPath("$.code").value("EIA-DOC-4041"));
    }

    @Test
    void preservesAnExpertNotFoundProblemOnAVersionDownload() throws Exception {
        when(client.downloadContentVersion(TENANT, DOCUMENT_ID, 2L))
                .thenThrow(problem(404, "EIA-DOC-4043", "Version not found",
                        "Content version 2 was not found"));

        mockMvc.perform(get("/api/v1/documents/{documentId}/content/versions/{version}",
                        DOCUMENT_ID, 2)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Content version 2 was not found"))
                .andExpect(jsonPath("$.code").value("EIA-DOC-4043"));
    }

    @Test
    void preservesAnExpertBadRequestProblemOnAVersionDownload() throws Exception {
        when(client.downloadContentVersion(TENANT, DOCUMENT_ID, 2L))
                .thenThrow(problem(400, "EIA-DOC-4002", "Validation failed",
                        "version must be positive"));

        mockMvc.perform(get("/api/v1/documents/{documentId}/content/versions/{version}",
                        DOCUMENT_ID, 2)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("version must be positive"))
                .andExpect(jsonPath("$.code").value("EIA-DOC-4002"));
    }

    private static List<DocumentContentVersion> versions() {
        return List.of(
                new DocumentContentVersion(DOCUMENT_ID, 2L, FILE_NAME,
                        MediaType.APPLICATION_PDF_VALUE, CONTENT.length, SHA256, UPLOADED_AT),
                new DocumentContentVersion(DOCUMENT_ID, 1L, "policy schedule v1.pdf",
                        MediaType.APPLICATION_PDF_VALUE, CONTENT.length, SHA256,
                        Instant.parse("2026-10-19T08:00:00Z")));
    }

    private static ExpertProblemException problem(int status, String code, String title,
                                                  String detail) {
        try {
            return new ExpertProblemException(status, MAPPER.readTree("""
                    {"type":"https://errors.eia.example/%s","title":"%s","status":%d,
                     "detail":"%s","code":"%s","category":"%s"}
                    """.formatted(code.toLowerCase(), title, status, detail, code,
                    status == 404 ? "NOT_FOUND" : "VALIDATION")));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
