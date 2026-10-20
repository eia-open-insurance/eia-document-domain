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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Wire-level tests for the Document expert boundary. They prove the exact
 * tenant forwarding, request payload, list decoding, and problem preservation
 * without starting the service.
 */
class DocumentLinkClientTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID DOCUMENT_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID LINK_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID LINKED_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final String EXPERT = "http://expert.test";
    private static final String LINK_BASE = EXPERT + "/internal/v1/documents/" + DOCUMENT_ID + "/links";
    private static final String LINKS_URL = LINK_BASE + "?tenantId=" + TENANT;
    private static final String LINK_URL = LINK_BASE + "/" + LINK_ID + "?tenantId=" + TENANT;
    private static final String LINKED_REFERENCE = "POL-2026-0041827";

    private static final String LINK_JSON = """
            {
              "id": "33333333-3333-7333-8333-333333333333",
              "documentId": "22222222-2222-7222-8222-222222222222",
              "linkedType": "POLICY",
              "linkedId": "44444444-4444-7444-8444-444444444444",
              "linkedReference": "POL-2026-0041827",
              "linkedLabel": "Acme CGL policy 2026",
              "createdAt": "2026-10-20T09:30:00Z"
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
    void findLinksForwardsTenantHeaderAndQueryAndDecodesTheList() {
        server.expect(requestTo(LINKS_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(DocumentExpertClient.TENANT_HEADER, TENANT.toString()))
                .andRespond(withSuccess("[" + LINK_JSON + "]", MediaType.APPLICATION_JSON));

        List<DocumentLinkDto> links = client.findLinks(TENANT, DOCUMENT_ID);

        assertEquals(1, links.size());
        DocumentLinkDto link = links.getFirst();
        assertEquals(LINK_ID, link.id());
        assertEquals(DOCUMENT_ID, link.documentId());
        assertEquals("POLICY", link.linkedType());
        assertEquals(LINKED_ID, link.linkedId());
        assertEquals(LINKED_REFERENCE, link.linkedReference());
        assertEquals("Acme CGL policy 2026", link.linkedLabel());
        assertEquals(Instant.parse("2026-10-20T09:30:00Z"), link.createdAt());
        server.verify();
    }

    @Test
    void createLinkPostsTheTenantAndPublicFieldsWithHeaders() {
        server.expect(requestTo(LINKS_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(DocumentExpertClient.TENANT_HEADER, TENANT.toString()))
                .andExpect(content().json("""
                        {
                          "linkedType": "POLICY",
                          "linkedId": "44444444-4444-7444-8444-444444444444",
                          "linkedReference": "POL-2026-0041827"
                        }
                        """, JsonCompareMode.STRICT))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(LINK_JSON));

        DocumentLinkDto link = client.createLink(TENANT, DOCUMENT_ID,
                new CreateDocumentLinkCommand("POLICY", LINKED_ID, LINKED_REFERENCE));

        assertEquals(LINK_ID, link.id());
        assertEquals("POLICY", link.linkedType());
        assertEquals(LINKED_ID, link.linkedId());
        assertEquals(LINKED_REFERENCE, link.linkedReference());
        server.verify();
    }

    @Test
    void deleteLinkForwardsTenantAndAcceptsNoContent() {
        server.expect(requestTo(LINK_URL))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header(DocumentExpertClient.TENANT_HEADER, TENANT.toString()))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.deleteLink(TENANT, DOCUMENT_ID, LINK_ID);

        server.verify();
    }

    @Test
    void filtersDocumentsByLinkOnTheExpert() {
        String url = EXPERT + "/internal/v1/documents?tenantId=" + TENANT
                + "&linkedType=POLICY&linkedId=" + LINKED_ID;
        server.expect(requestTo(url))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(DocumentExpertClient.TENANT_HEADER, TENANT.toString()))
                .andRespond(withSuccess("[" + DOCUMENT_JSON + "]", MediaType.APPLICATION_JSON));

        List<DocumentDto> documents = client.findAll(TENANT, "POLICY", LINKED_ID.toString());

        assertEquals(1, documents.size());
        assertEquals(DOCUMENT_ID, documents.getFirst().id());
        assertEquals("DOC-2026-0000001", documents.getFirst().documentNumber());
        assertEquals(TENANT, documents.getFirst().tenantId());
        server.verify();
    }

    @Test
    void searchForwardsTenantAndTermAndDecodesTheEnvelope() {
        String url = EXPERT + "/internal/v1/documents/search?tenantId=" + TENANT
                + "&limit=10&q=POL-2026-0041827";
        server.expect(requestTo(url))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(DocumentExpertClient.TENANT_HEADER, TENANT.toString()))
                .andRespond(withSuccess("""
                        {"query":"POL-2026-0041827","limit":10,"items":[
                          {"id":"22222222-2222-7222-8222-222222222222","type":"DOCUMENT",
                           "label":"DOC-2026-0000001","secondary":"Policy schedule · ACTIVE",
                           "status":"ACTIVE","href":"/document?record=22222222-2222-7222-8222-222222222222"}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        DocumentSearchResponse response = client.search(TENANT, "POL-2026-0041827", 10).orElseThrow();

        assertEquals("POL-2026-0041827", response.query());
        assertEquals(10, response.limit());
        assertEquals(DOCUMENT_ID, response.items().getFirst().id());
        assertEquals("DOCUMENT", response.items().getFirst().type());
        server.verify();
    }

    @Test
    void returnsEmptyWhenTheExpertSearchRouteIsAbsent() {
        server.expect(requestTo(EXPERT + "/internal/v1/documents/search?tenantId=" + TENANT + "&limit=10"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertTrue(client.search(TENANT, null, 10).isEmpty());

        server.verify();
    }

    @Test
    void preservesAnExpertBadRequestProblem() {
        server.expect(requestTo(LINKS_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("""
                                {"type": "https://errors.eia.example/eia-doc-4001",
                                 "title": "Validation failed", "status": 400,
                                 "detail": "linkedType is required",
                                 "code": "EIA-DOC-4001", "category": "VALIDATION"}
                                """));

        ExpertProblemException exception = assertThrows(ExpertProblemException.class,
                () -> client.createLink(TENANT, DOCUMENT_ID,
                        new CreateDocumentLinkCommand("POLICY", LINKED_ID, null)));

        assertEquals(400, exception.status());
        assertEquals("EIA-DOC-4001", exception.body().get("code").asText());
        assertEquals("linkedType is required", exception.getMessage());
        server.verify();
    }

    @Test
    void preservesAnExpertNotFoundProblem() {
        server.expect(requestTo(LINK_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("""
                                {"type": "https://errors.eia.example/eia-doc-4041",
                                 "title": "Link not found", "status": 404,
                                 "detail": "Document link 33333333-3333-7333-8333-333333333333 was not found",
                                 "code": "EIA-DOC-4041", "category": "NOT_FOUND"}
                                """));

        ExpertProblemException exception = assertThrows(ExpertProblemException.class,
                () -> client.deleteLink(TENANT, DOCUMENT_ID, LINK_ID));

        assertEquals(404, exception.status());
        assertEquals("EIA-DOC-4041", exception.body().get("code").asText());
        server.verify();
    }

    @Test
    void preservesAnExpertConflictProblem() {
        server.expect(requestTo(LINKS_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("""
                                {"type": "https://errors.eia.example/eia-doc-4091",
                                 "title": "Link already exists", "status": 409,
                                 "detail": "The document is already linked to this policy",
                                 "code": "EIA-DOC-4091", "category": "CONFLICT"}
                                """));

        ExpertProblemException exception = assertThrows(ExpertProblemException.class,
                () -> client.createLink(TENANT, DOCUMENT_ID,
                        new CreateDocumentLinkCommand("POLICY", LINKED_ID, LINKED_REFERENCE)));

        assertEquals(409, exception.status());
        assertEquals("EIA-DOC-4091", exception.body().get("code").asText());
        assertEquals("The document is already linked to this policy", exception.getMessage());
        server.verify();
    }

    @Test
    void mapsAnExpertServerErrorToUnavailable() {
        server.expect(requestTo(LINKS_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThrows(ExpertUnavailableException.class,
                () -> client.findLinks(TENANT, DOCUMENT_ID));

        server.verify();
    }

    private static final String DOCUMENT_JSON = """
            {
              "id": "22222222-2222-7222-8222-222222222222",
              "tenantId": "11111111-1111-7111-8111-111111111111",
              "documentNumber": "DOC-2026-0000001",
              "title": "Policy schedule",
              "documentType": "POLICY",
              "classification": "CONFIDENTIAL",
              "status": "ACTIVE",
              "retentionUntil": "2036-10-19",
              "version": 1,
              "createdAt": "2026-10-19T12:00:00Z",
              "updatedAt": "2026-10-19T12:00:00Z"
            }
            """;
}
