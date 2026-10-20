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
    public List<DocumentDto> list(@RequestHeader(TENANT_HEADER) UUID tenantId) {
        return service.list(tenantId);
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
}
