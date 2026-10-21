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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Wire-level tests for the content-version proxy: exact expert URLs, tenant
 * forwarding, history decoding, representation headers on a version download,
 * and preservation of the expert's 400/404 problems.
 */
class DocumentContentVersionClientTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID DOCUMENT_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final String EXPERT = "http://expert.test";
    private static final String VERSIONS_URL = EXPERT + "/api/v1/documents/"
            + DOCUMENT_ID + "/content/versions?tenantId=" + TENANT;
    private static final String VERSION_URL = EXPERT + "/api/v1/documents/"
            + DOCUMENT_ID + "/content/versions/2?tenantId=" + TENANT;
    private static final String FILE_NAME = "policy schedule.pdf";
    private static final String DISPOSITION = "attachment; filename=\"policy schedule.pdf\"";
    private static final String SHA256 = "9f2c5d0f8f0d1c9a5f0f0f3a6b8c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f607182";
    private static final byte[] CONTENT = "%PDF-1.7 document bytes".getBytes(StandardCharsets.UTF_8);
    private static final String VERSIONS_JSON = """
            [
              {
                "documentId": "22222222-2222-7222-8222-222222222222",
                "version": 2,
                "fileName": "policy schedule.pdf",
                "contentType": "application/pdf",
                "sizeBytes": 23,
                "sha256": "9f2c5d0f8f0d1c9a5f0f0f3a6b8c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f607182",
                "uploadedAt": "2026-10-20T10:00:00Z"
              },
              {
                "documentId": "22222222-2222-7222-8222-222222222222",
                "version": 1,
                "fileName": "policy schedule v1.pdf",
                "contentType": "application/pdf",
                "sizeBytes": 19,
                "sha256": "0a1b2c3d4e5f6071829f2c5d0f8f0d1c9a5f0f0f3a6b8c1d2e3f4a5b6c7d8e9f",
                "uploadedAt": "2026-10-19T08:00:00Z"
              }
            ]
            """;
    private static final String NOT_FOUND_PROBLEM = """
            {"type": "https://errors.eia.example/eia-doc-4041",
             "title": "Document not found", "status": 404,
             "detail": "Document 22222222-2222-7222-8222-222222222222 was not found",
             "code": "EIA-DOC-4041", "category": "NOT_FOUND"}
            """;
    private static final String BAD_REQUEST_PROBLEM = """
            {"type": "https://errors.eia.example/eia-doc-4042",
             "title": "Version not found", "status": 400,
             "detail": "version must be a positive revision number",
             "code": "EIA-DOC-4042", "category": "VALIDATION"}
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
    void listsVersionsWithTheTenantForwardedAndDecodesTheHistory() {
        server.expect(requestTo(VERSIONS_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(DocumentExpertClient.TENANT_HEADER, TENANT.toString()))
                .andRespond(withSuccess(VERSIONS_JSON, MediaType.APPLICATION_JSON));

        List<DocumentContentVersion> versions = client.contentVersions(TENANT, DOCUMENT_ID);

        assertEquals(2, versions.size());
        DocumentContentVersion newest = versions.getFirst();
        assertEquals(DOCUMENT_ID, newest.documentId());
        assertEquals(2L, newest.version());
        assertEquals(FILE_NAME, newest.fileName());
        assertEquals(MediaType.APPLICATION_PDF_VALUE, newest.contentType());
        assertEquals(CONTENT.length, newest.sizeBytes());
        assertEquals(SHA256, newest.sha256());
        assertEquals(Instant.parse("2026-10-20T10:00:00Z"), newest.uploadedAt());
        assertEquals(1L, versions.get(1).version());
        assertEquals("policy schedule v1.pdf", versions.get(1).fileName());
        server.verify();
    }

    @Test
    void anEmptyVersionsBodyIsAnEmptyHistory() {
        server.expect(requestTo(VERSIONS_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.OK));

        assertTrue(client.contentVersions(TENANT, DOCUMENT_ID).isEmpty());
        server.verify();
    }

    @Test
    void downloadsAVersionWithTheTenantForwardedAndRepresentationHeaders() {
        server.expect(requestTo(VERSION_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(DocumentExpertClient.TENANT_HEADER, TENANT.toString()))
                .andRespond(withSuccess(CONTENT, MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, DISPOSITION)
                        .header(DocumentContentPolicy.CONTENT_SHA_HEADER, SHA256));

        DocumentContentDownload download =
                client.downloadContentVersion(TENANT, DOCUMENT_ID, 2L);

        assertArrayEquals(CONTENT, download.bytes());
        assertEquals(MediaType.APPLICATION_PDF, download.contentType());
        assertEquals(DISPOSITION, download.contentDisposition());
        assertEquals(SHA256, download.sha256());
        server.verify();
    }

    @Test
    void versionDownloadToleratesAMissingContentLengthBody() {
        server.expect(requestTo(VERSION_URL))
                .andRespond(withStatus(HttpStatus.OK));

        DocumentContentDownload download =
                client.downloadContentVersion(TENANT, DOCUMENT_ID, 2L);

        assertEquals(0, download.bytes().length);
        assertNull(download.contentType());
        server.verify();
    }

    @Test
    void preservesAnExpertNotFoundProblemOnTheVersionList() {
        server.expect(requestTo(VERSIONS_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body(NOT_FOUND_PROBLEM));

        ExpertProblemException exception = assertThrows(ExpertProblemException.class,
                () -> client.contentVersions(TENANT, DOCUMENT_ID));

        assertEquals(404, exception.status());
        assertEquals("EIA-DOC-4041", exception.body().get("code").asText());
        server.verify();
    }

    @Test
    void preservesAnExpertNotFoundProblemOnTheVersionDownload() {
        server.expect(requestTo(VERSION_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body(NOT_FOUND_PROBLEM));

        ExpertProblemException exception = assertThrows(ExpertProblemException.class,
                () -> client.downloadContentVersion(TENANT, DOCUMENT_ID, 2L));

        assertEquals(404, exception.status());
        assertEquals("EIA-DOC-4041", exception.body().get("code").asText());
        server.verify();
    }

    @Test
    void preservesAnExpertBadRequestProblemOnTheVersionDownload() {
        server.expect(requestTo(VERSION_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body(BAD_REQUEST_PROBLEM));

        ExpertProblemException exception = assertThrows(ExpertProblemException.class,
                () -> client.downloadContentVersion(TENANT, DOCUMENT_ID, 2L));

        assertEquals(400, exception.status());
        assertEquals("EIA-DOC-4042", exception.body().get("code").asText());
        assertEquals("version must be a positive revision number", exception.getMessage());
        server.verify();
    }

    @Test
    void mapsAnExpertServerErrorOnTheVersionListToUnavailable() {
        server.expect(requestTo(VERSIONS_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThrows(ExpertUnavailableException.class,
                () -> client.contentVersions(TENANT, DOCUMENT_ID));

        server.verify();
    }
}
