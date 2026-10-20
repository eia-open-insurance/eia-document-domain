package com.eia.document.domain;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Public Document API. Every route requires {@code X-EIA-Tenant}; the tenant is
 * never read from the request body.
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {
    private static final String TENANT_HEADER = "X-EIA-Tenant";

    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    @GetMapping
    public List<DocumentDto> list(@RequestHeader(TENANT_HEADER) UUID tenantId,
                                  @RequestParam(name = "linkedType", required = false) String linkedType,
                                  @RequestParam(name = "linkedId", required = false) String linkedId) {
        return service.list(tenantId, linkedType, linkedId);
    }

    @GetMapping("/search")
    public DocumentSearchResponse search(@RequestHeader(TENANT_HEADER) UUID tenantId,
                                         @RequestParam(name = "q", required = false) String q,
                                         @RequestParam(name = "limit", defaultValue = "10") int limit) {
        return service.search(tenantId, q, limit);
    }

    @GetMapping("/{id}")
    public DocumentDto get(@RequestHeader(TENANT_HEADER) UUID tenantId, @PathVariable UUID id) {
        return service.get(tenantId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentDto create(@RequestHeader(TENANT_HEADER) UUID tenantId,
                              @Valid @RequestBody CreateDocumentCommand command) {
        return service.create(tenantId, command);
    }

    @PostMapping("/{id}/status")
    public DocumentDto changeStatus(@RequestHeader(TENANT_HEADER) UUID tenantId,
                                    @PathVariable UUID id,
                                    @Valid @RequestBody ChangeStatusCommand command) {
        return service.changeStatus(tenantId, id, command);
    }

    @GetMapping("/{documentId}/links")
    public List<DocumentLinkDto> links(@RequestHeader(TENANT_HEADER) UUID tenantId,
                                       @PathVariable UUID documentId) {
        return service.links(tenantId, documentId);
    }

    @PostMapping("/{documentId}/links")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentLinkDto addLink(@RequestHeader(TENANT_HEADER) UUID tenantId,
                                   @PathVariable UUID documentId,
                                   @Valid @RequestBody CreateDocumentLinkCommand command) {
        return service.addLink(tenantId, documentId, command);
    }

    @DeleteMapping("/{documentId}/links/{linkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeLink(@RequestHeader(TENANT_HEADER) UUID tenantId,
                           @PathVariable UUID documentId,
                           @PathVariable UUID linkId) {
        service.removeLink(tenantId, documentId, linkId);
    }
}
