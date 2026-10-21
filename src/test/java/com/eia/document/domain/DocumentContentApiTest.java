package com.eia.document.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DocumentContentApiTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID DOCUMENT_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final String FILE_NAME = "policy schedule.pdf";
    private static final String DISPOSITION = "attachment; filename=\"policy schedule.pdf\"";
    private static final String SHA256 = "9f2c5d0f8f0d1c9a5f0f0f3a6b8c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f607182";
    private static final Instant UPLOADED_AT = Instant.parse("2026-10-20T09:30:00Z");
    private static final byte[] CONTENT = "%PDF-1.7 document bytes".getBytes(StandardCharsets.UTF_8);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentExpertClient client;

    @Test
    void uploadsRawBytesAndReturnsTheMetadataProjection() throws Exception {
        when(client.uploadContent(eq(TENANT), eq(DOCUMENT_ID), eq(FILE_NAME),
                startsWith(MediaType.APPLICATION_PDF_VALUE), any(byte[].class)))
                .thenReturn(metadata());

        mockMvc.perform(put("/api/v1/documents/{documentId}/content", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-File-Name", FILE_NAME)
                        .contentType(MediaType.APPLICATION_PDF)
                        .content(CONTENT))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.documentId").value(DOCUMENT_ID.toString()))
                .andExpect(jsonPath("$.fileName").value(FILE_NAME))
                .andExpect(jsonPath("$.contentType").value(MediaType.APPLICATION_PDF_VALUE))
                .andExpect(jsonPath("$.sizeBytes").value(CONTENT.length))
                .andExpect(jsonPath("$.sha256").value(SHA256))
                .andExpect(jsonPath("$.uploadedAt").value("2026-10-20T09:30:00Z"));

        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(client).uploadContent(eq(TENANT), eq(DOCUMENT_ID), eq(FILE_NAME),
                startsWith(MediaType.APPLICATION_PDF_VALUE), body.capture());
        assertArrayEquals(CONTENT, body.getValue());
    }

    @Test
    void downloadsBytesWithContentTypeDispositionAndShaHeaders() throws Exception {
        when(client.downloadContent(TENANT, DOCUMENT_ID))
                .thenReturn(new DocumentContentDownload(CONTENT, MediaType.APPLICATION_PDF,
                        DISPOSITION, SHA256));

        mockMvc.perform(get("/api/v1/documents/{documentId}/content", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        MediaType.APPLICATION_PDF_VALUE))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, DISPOSITION))
                .andExpect(header().string(DocumentContentPolicy.CONTENT_SHA_HEADER, SHA256))
                .andExpect(content().bytes(CONTENT));

        verify(client).downloadContent(TENANT, DOCUMENT_ID);
    }

    @Test
    void returnsContentMetadata() throws Exception {
        when(client.contentMetadata(TENANT, DOCUMENT_ID)).thenReturn(metadata());

        mockMvc.perform(get("/api/v1/documents/{documentId}/content/metadata", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(DOCUMENT_ID.toString()))
                .andExpect(jsonPath("$.fileName").value(FILE_NAME))
                .andExpect(jsonPath("$.contentType").value(MediaType.APPLICATION_PDF_VALUE))
                .andExpect(jsonPath("$.sizeBytes").value(CONTENT.length))
                .andExpect(jsonPath("$.sha256").value(SHA256))
                .andExpect(jsonPath("$.uploadedAt").value("2026-10-20T09:30:00Z"));

        verify(client).contentMetadata(TENANT, DOCUMENT_ID);
    }

    @Test
    void defaultsToAttachmentWhenTheExpertOmitsTheDisposition() throws Exception {
        when(client.downloadContent(TENANT, DOCUMENT_ID))
                .thenReturn(new DocumentContentDownload(CONTENT, MediaType.APPLICATION_OCTET_STREAM,
                        null, null));

        mockMvc.perform(get("/api/v1/documents/{documentId}/content", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment"));
    }

    @Test
    void requiresTheTenantHeaderOnContentRoutes() throws Exception {
        mockMvc.perform(get("/api/v1/documents/{documentId}/content", DOCUMENT_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));

        verifyNoInteractions(client);
    }

    @Test
    void requiresTheFileNameHeaderOnUpload() throws Exception {
        mockMvc.perform(put("/api/v1/documents/{documentId}/content", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_PDF)
                        .content(CONTENT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(DocumentContentPolicy.FILE_NAME_REQUIRED));

        verifyNoInteractions(client);
    }

    @Test
    void rejectsAnUploadAboveTenMebibytesWithAProblem() throws Exception {
        byte[] tooLarge = new byte[(int) DocumentContentPolicy.MAX_CONTENT_BYTES + 1];

        mockMvc.perform(put("/api/v1/documents/{documentId}/content", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-File-Name", "huge.pdf")
                        .contentType(MediaType.APPLICATION_PDF)
                        .content(tooLarge))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.title").value("Payload Too Large"))
                .andExpect(jsonPath("$.code").value(DocumentContentPolicy.FILE_TOO_LARGE));

        verifyNoInteractions(client);
    }

    @Test
    void acceptsAnUploadAtExactlyTenMebibytes() throws Exception {
        byte[] atLimit = new byte[(int) DocumentContentPolicy.MAX_CONTENT_BYTES];
        when(client.uploadContent(eq(TENANT), eq(DOCUMENT_ID), eq("limit.pdf"),
                startsWith(MediaType.APPLICATION_OCTET_STREAM_VALUE), any(byte[].class)))
                .thenReturn(new DocumentContentMetadata(DOCUMENT_ID, "limit.pdf",
                        MediaType.APPLICATION_OCTET_STREAM_VALUE, atLimit.length, SHA256, UPLOADED_AT));

        mockMvc.perform(put("/api/v1/documents/{documentId}/content", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-File-Name", "limit.pdf")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(atLimit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sizeBytes").value(atLimit.length));

        verify(client).uploadContent(eq(TENANT), eq(DOCUMENT_ID), eq("limit.pdf"),
                startsWith(MediaType.APPLICATION_OCTET_STREAM_VALUE), any(byte[].class));
    }

    @Test
    void forwardsAnEmptyBodySoTheExpertOwnsTheEmptyContentRule() throws Exception {
        when(client.uploadContent(eq(TENANT), eq(DOCUMENT_ID), eq("empty.pdf"),
                startsWith(MediaType.APPLICATION_PDF_VALUE), any(byte[].class)))
                .thenReturn(new DocumentContentMetadata(DOCUMENT_ID, "empty.pdf",
                        MediaType.APPLICATION_PDF_VALUE, 0, SHA256, UPLOADED_AT));

        mockMvc.perform(put("/api/v1/documents/{documentId}/content", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-File-Name", "empty.pdf")
                        .contentType(MediaType.APPLICATION_PDF)
                        .content(new byte[0]))
                .andExpect(status().isOk());

        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(client).uploadContent(eq(TENANT), eq(DOCUMENT_ID), eq("empty.pdf"),
                startsWith(MediaType.APPLICATION_PDF_VALUE), body.capture());
        assertArrayEquals(new byte[0], body.getValue());
    }

    @Test
    void defaultsTheMediaTypeToOctetStreamWhenTheClientOmitsIt() throws Exception {
        when(client.uploadContent(eq(TENANT), eq(DOCUMENT_ID), eq("raw.bin"),
                startsWith(MediaType.APPLICATION_OCTET_STREAM_VALUE), any(byte[].class)))
                .thenReturn(new DocumentContentMetadata(DOCUMENT_ID, "raw.bin",
                        MediaType.APPLICATION_OCTET_STREAM_VALUE, CONTENT.length, SHA256, UPLOADED_AT));

        mockMvc.perform(put("/api/v1/documents/{documentId}/content", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-File-Name", "raw.bin")
                        .content(CONTENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentType").value(
                        MediaType.APPLICATION_OCTET_STREAM_VALUE));

        verify(client).uploadContent(eq(TENANT), eq(DOCUMENT_ID), eq("raw.bin"),
                startsWith(MediaType.APPLICATION_OCTET_STREAM_VALUE), any(byte[].class));
    }

    @Test
    void forwardsTheHeaderTenantAndIgnoresAQueryTenantOnUpload() throws Exception {
        UUID otherTenant = UUID.fromString("99999999-9999-7999-8999-999999999999");
        when(client.uploadContent(eq(TENANT), eq(DOCUMENT_ID), eq(FILE_NAME),
                startsWith(MediaType.APPLICATION_PDF_VALUE), any(byte[].class)))
                .thenReturn(metadata());

        mockMvc.perform(put("/api/v1/documents/{documentId}/content", DOCUMENT_ID)
                        .param("tenantId", otherTenant.toString())
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-File-Name", FILE_NAME)
                        .contentType(MediaType.APPLICATION_PDF)
                        .content(CONTENT))
                .andExpect(status().isOk());

        verify(client).uploadContent(eq(TENANT), eq(DOCUMENT_ID), eq(FILE_NAME),
                startsWith(MediaType.APPLICATION_PDF_VALUE), any(byte[].class));
    }

    @Test
    void preservesAnExpertNotFoundProblemOnDownload() throws Exception {
        when(client.downloadContent(TENANT, DOCUMENT_ID))
                .thenThrow(problem(404, "EIA-DOC-4041", "Document not found",
                        "Document was not found"));

        mockMvc.perform(get("/api/v1/documents/{documentId}/content", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Document was not found"))
                .andExpect(jsonPath("$.code").value("EIA-DOC-4041"));
    }

    @Test
    void preservesAnExpertBadRequestProblemOnUpload() throws Exception {
        when(client.uploadContent(eq(TENANT), eq(DOCUMENT_ID), eq(FILE_NAME),
                startsWith(MediaType.APPLICATION_PDF_VALUE), any(byte[].class)))
                .thenThrow(problem(400, "EIA-DOC-4001", "Validation failed",
                        "contentType is required"));

        mockMvc.perform(put("/api/v1/documents/{documentId}/content", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-File-Name", FILE_NAME)
                        .contentType(MediaType.APPLICATION_PDF)
                        .content(CONTENT))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("EIA-DOC-4001"));
    }

    @Test
    void preservesAnExpertPayloadTooLargeProblemOnUpload() throws Exception {
        when(client.uploadContent(eq(TENANT), eq(DOCUMENT_ID), eq(FILE_NAME),
                startsWith(MediaType.APPLICATION_PDF_VALUE), any(byte[].class)))
                .thenThrow(problem(413, DocumentContentPolicy.FILE_TOO_LARGE,
                        "Payload Too Large", "The expert rejected the body size"));

        mockMvc.perform(put("/api/v1/documents/{documentId}/content", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-File-Name", FILE_NAME)
                        .contentType(MediaType.APPLICATION_PDF)
                        .content(CONTENT))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.title").value("Payload Too Large"))
                .andExpect(jsonPath("$.code").value(DocumentContentPolicy.FILE_TOO_LARGE));
    }

    @Test
    void turnsTheExpertsApiErrorEnvelopeIntoAProblemJson() throws Exception {
        when(client.uploadContent(eq(TENANT), eq(DOCUMENT_ID), eq(FILE_NAME),
                startsWith(MediaType.APPLICATION_PDF_VALUE), any(byte[].class)))
                .thenThrow(apiError(413, "FILE_TOO_LARGE",
                        "Uploaded content is 10485761 bytes; the maximum is 10485760 bytes (10 MiB)"));

        mockMvc.perform(put("/api/v1/documents/{documentId}/content", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-File-Name", FILE_NAME)
                        .contentType(MediaType.APPLICATION_PDF)
                        .content(CONTENT))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.title").value("Payload Too Large"))
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"))
                .andExpect(jsonPath("$.detail").value(
                        "Uploaded content is 10485761 bytes; the maximum is 10485760 bytes (10 MiB)"));
    }

    private static DocumentContentMetadata metadata() {
        return new DocumentContentMetadata(DOCUMENT_ID, FILE_NAME,
                MediaType.APPLICATION_PDF_VALUE, CONTENT.length, SHA256, UPLOADED_AT);
    }

    private static ExpertProblemException apiError(int status, String code, String message) {
        try {
            return new ExpertProblemException(status, MAPPER.readTree("""
                    {"code":"%s","message":"%s"}
                    """.formatted(code, message)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static ExpertProblemException problem(int status, String code, String title,
                                                  String detail) {
        try {
            return new ExpertProblemException(status, MAPPER.readTree("""
                    {"type":"https://errors.eia.example/%s","title":"%s","status":%d,
                     "detail":"%s","code":"%s","category":"%s"}
                    """.formatted(code.toLowerCase(), title, status, detail, code,
                    status == 404 ? "NOT_FOUND" : status == 413 ? "LIMIT" : "VALIDATION")));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
