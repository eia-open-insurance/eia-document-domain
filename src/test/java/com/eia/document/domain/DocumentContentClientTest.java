package com.eia.document.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Wire-level tests for the document content proxy. They prove the exact expert
 * URL, tenant forwarding, raw bytes, representation headers, metadata decoding,
 * and problem preservation.
 */
class DocumentContentClientTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID DOCUMENT_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final String EXPERT = "http://expert.test";
    private static final String CONTENT_URL = EXPERT + "/api/v1/documents/"
            + DOCUMENT_ID + "/content?tenantId=" + TENANT;
    private static final String METADATA_URL = EXPERT + "/api/v1/documents/"
            + DOCUMENT_ID + "/content/metadata?tenantId=" + TENANT;
    private static final String FILE_NAME = "policy schedule.pdf";
    private static final String DISPOSITION = "attachment; filename=\"policy schedule.pdf\"";
    private static final String SHA256 = "9f2c5d0f8f0d1c9a5f0f0f3a6b8c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f607182";
    private static final byte[] CONTENT = "%PDF-1.7 document bytes".getBytes(StandardCharsets.UTF_8);
    private static final String METADATA_JSON = """
            {
              "documentId": "22222222-2222-7222-8222-222222222222",
              "fileName": "policy schedule.pdf",
              "contentType": "application/pdf",
              "sizeBytes": 23,
              "sha256": "9f2c5d0f8f0d1c9a5f0f0f3a6b8c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f607182",
              "uploadedAt": "2026-10-20T09:30:00Z",
              "currentVersion": 3
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
    void uploadForwardsBytesHeadersAndTenantAndDecodesMetadata() {
        server.expect(requestTo(CONTENT_URL))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header(DocumentExpertClient.TENANT_HEADER, TENANT.toString()))
                .andExpect(header(DocumentContentPolicy.FILE_NAME_HEADER, FILE_NAME))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE))
                .andExpect(content().bytes(CONTENT))
                .andRespond(withSuccess(METADATA_JSON, MediaType.APPLICATION_JSON));

        DocumentContentMetadata metadata = client.uploadContent(TENANT, DOCUMENT_ID, FILE_NAME,
                MediaType.APPLICATION_PDF_VALUE, CONTENT);

        assertEquals(DOCUMENT_ID, metadata.documentId());
        assertEquals(FILE_NAME, metadata.fileName());
        assertEquals(MediaType.APPLICATION_PDF_VALUE, metadata.contentType());
        assertEquals(CONTENT.length, metadata.sizeBytes());
        assertEquals(SHA256, metadata.sha256());
        assertEquals(Instant.parse("2026-10-20T09:30:00Z"), metadata.uploadedAt());
        assertEquals(3L, metadata.currentVersion());
        server.verify();
    }

    @Test
    void forwardsAUrlEncodedFileNameWithoutDoubleEncodingIt() {
        String encoded = "Q3%20report%20%E2%82%AC.pdf";
        server.expect(requestTo(CONTENT_URL))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header(DocumentContentPolicy.FILE_NAME_HEADER, encoded))
                .andRespond(withSuccess(METADATA_JSON, MediaType.APPLICATION_JSON));

        client.uploadContent(TENANT, DOCUMENT_ID, encoded,
                MediaType.APPLICATION_PDF_VALUE, CONTENT);

        server.verify();
    }

    @Test
    void downloadForwardsTenantAndReturnsBytesWithRepresentationHeaders() {
        server.expect(requestTo(CONTENT_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(DocumentExpertClient.TENANT_HEADER, TENANT.toString()))
                .andRespond(withSuccess(CONTENT, MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, DISPOSITION)
                        .header(DocumentContentPolicy.CONTENT_SHA_HEADER, SHA256));

        DocumentContentDownload download = client.downloadContent(TENANT, DOCUMENT_ID);

        assertArrayEquals(CONTENT, download.bytes());
        assertEquals(MediaType.APPLICATION_PDF, download.contentType());
        assertEquals(DISPOSITION, download.contentDisposition());
        assertEquals(SHA256, download.sha256());
        server.verify();
    }

    @Test
    void metadataForwardsTenantAndDecodesTheProjection() {
        server.expect(requestTo(METADATA_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(DocumentExpertClient.TENANT_HEADER, TENANT.toString()))
                .andRespond(withSuccess(METADATA_JSON, MediaType.APPLICATION_JSON));

        DocumentContentMetadata metadata = client.contentMetadata(TENANT, DOCUMENT_ID);

        assertEquals(DOCUMENT_ID, metadata.documentId());
        assertEquals(FILE_NAME, metadata.fileName());
        assertEquals(CONTENT.length, metadata.sizeBytes());
        assertEquals(SHA256, metadata.sha256());
        server.verify();
    }

    @Test
    void preservesAnExpertBadRequestProblemOnUpload() {
        server.expect(requestTo(CONTENT_URL))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("""
                                {"type": "https://errors.eia.example/eia-doc-4001",
                                 "title": "Validation failed", "status": 400,
                                 "detail": "X-File-Name is required",
                                 "code": "EIA-DOC-4001", "category": "VALIDATION"}
                                """));

        ExpertProblemException exception = assertThrows(ExpertProblemException.class,
                () -> client.uploadContent(TENANT, DOCUMENT_ID, FILE_NAME,
                        MediaType.APPLICATION_PDF_VALUE, CONTENT));

        assertEquals(400, exception.status());
        assertEquals("EIA-DOC-4001", exception.body().get("code").asText());
        assertEquals("X-File-Name is required", exception.getMessage());
        server.verify();
    }

    @Test
    void preservesAnExpertNotFoundProblemOnDownload() {
        server.expect(requestTo(CONTENT_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("""
                                {"type": "https://errors.eia.example/eia-doc-4041",
                                 "title": "Document not found", "status": 404,
                                 "detail": "Document 22222222-2222-7222-8222-222222222222 was not found",
                                 "code": "EIA-DOC-4041", "category": "NOT_FOUND"}
                                """));

        ExpertProblemException exception = assertThrows(ExpertProblemException.class,
                () -> client.downloadContent(TENANT, DOCUMENT_ID));

        assertEquals(404, exception.status());
        assertEquals("EIA-DOC-4041", exception.body().get("code").asText());
        server.verify();
    }

    @Test
    void preservesAnExpertPayloadTooLargeProblemOnUpload() {
        server.expect(requestTo(CONTENT_URL))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.PAYLOAD_TOO_LARGE)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("""
                                {"type": "about:blank", "title": "Payload Too Large",
                                 "status": 413,
                                 "detail": "Document content exceeds the 10 MiB limit",
                                 "code": "FILE_TOO_LARGE"}
                                """));

        ExpertProblemException exception = assertThrows(ExpertProblemException.class,
                () -> client.uploadContent(TENANT, DOCUMENT_ID, FILE_NAME,
                        MediaType.APPLICATION_PDF_VALUE, CONTENT));

        assertEquals(413, exception.status());
        assertEquals("FILE_TOO_LARGE", exception.body().get("code").asText());
        server.verify();
    }

    @Test
    void mapsAnExpertServerErrorToUnavailable() {
        server.expect(requestTo(METADATA_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThrows(ExpertUnavailableException.class,
                () -> client.contentMetadata(TENANT, DOCUMENT_ID));

        server.verify();
    }

    @Test
    void downloadToleratesAMissingContentLengthBody() {
        server.expect(requestTo(CONTENT_URL))
                .andRespond(withStatus(HttpStatus.OK));

        DocumentContentDownload download = client.downloadContent(TENANT, DOCUMENT_ID);

        assertTrue(download.bytes().length == 0);
        assertNull(download.contentType());
        server.verify();
    }
}
