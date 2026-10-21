package com.eia.document.domain;

import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    /**
     * Places a legal hold on a document. The reason is recorded and disposition
     * is blocked while the hold is active; the expert owns the state conflict
     * and its 409 problem.
     */
    @PostMapping("/{documentId}/hold")
    public DocumentDto hold(@RequestHeader(TENANT_HEADER) UUID tenantId,
                            @PathVariable UUID documentId,
                            @Valid @RequestBody HoldDocumentCommand command) {
        return service.hold(tenantId, documentId, command);
    }

    /**
     * Releases the active legal hold and restores the status captured when the
     * hold was placed.
     */
    @PostMapping("/{documentId}/hold/release")
    public DocumentDto releaseHold(@RequestHeader(TENANT_HEADER) UUID tenantId,
                                   @PathVariable UUID documentId) {
        return service.releaseHold(tenantId, documentId);
    }

    /**
     * Tombstones a document as {@code DISPOSED}. A document under legal hold is
     * not disposable; the expert answers that conflict with 409.
     */
    @PostMapping("/{documentId}/dispose")
    public DocumentDto dispose(@RequestHeader(TENANT_HEADER) UUID tenantId,
                               @PathVariable UUID documentId) {
        return service.dispose(tenantId, documentId);
    }

    /**
     * Raw content upload. The tenant is forwarded as a header, the file name
     * travels on {@code X-File-Name}, and the media type on {@code Content-Type}.
     * Bodies above 10 MiB are rejected with a 413 problem before this method.
     */
    @PutMapping(path = "/{documentId}/content", consumes = MediaType.ALL_VALUE)
    public DocumentContentMetadata uploadContent(
            @RequestHeader(TENANT_HEADER) UUID tenantId,
            @PathVariable UUID documentId,
            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
            @RequestHeader(value = DocumentContentPolicy.FILE_NAME_HEADER, required = false)
            String fileName,
            @RequestBody(required = false) byte[] content) {
        return service.uploadContent(tenantId, documentId, fileName, contentType, content);
    }

    /**
     * Authorized byte download. The representation headers are passed through
     * from the expert so the caller sees the stored media type, the original
     * file name, and the integrity hash.
     */
    @GetMapping("/{documentId}/content")
    public ResponseEntity<byte[]> downloadContent(@RequestHeader(TENANT_HEADER) UUID tenantId,
                                                  @PathVariable UUID documentId) {
        return downloadResponse(service.downloadContent(tenantId, documentId));
    }

    @GetMapping("/{documentId}/content/metadata")
    public DocumentContentMetadata contentMetadata(@RequestHeader(TENANT_HEADER) UUID tenantId,
                                                   @PathVariable UUID documentId) {
        return service.contentMetadata(tenantId, documentId);
    }

    /**
     * Retained content revisions. The expert owns the history and its order;
     * the domain tenant-scopes the read and passes the projection through.
     */
    @GetMapping("/{documentId}/content/versions")
    public List<DocumentContentVersion> contentVersions(
            @RequestHeader(TENANT_HEADER) UUID tenantId,
            @PathVariable UUID documentId) {
        return service.contentVersions(tenantId, documentId);
    }

    /**
     * Bytes of one retained revision. The media type, original file name, and
     * integrity hash are forwarded exactly as on the current-content download.
     */
    @GetMapping("/{documentId}/content/versions/{version}")
    public ResponseEntity<byte[]> downloadContentVersion(
            @RequestHeader(TENANT_HEADER) UUID tenantId,
            @PathVariable UUID documentId,
            @PathVariable long version) {
        return downloadResponse(service.downloadContentVersion(tenantId, documentId, version));
    }

    private static ResponseEntity<byte[]> downloadResponse(DocumentContentDownload content) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (content.contentType() != null) {
            response.contentType(content.contentType());
        }
        response.header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(content));
        if (content.sha256() != null && !content.sha256().isBlank()) {
            response.header(DocumentContentPolicy.CONTENT_SHA_HEADER, content.sha256());
        }
        return response.body(content.bytes());
    }

    private static String contentDisposition(DocumentContentDownload content) {
        String disposition = content.contentDisposition();
        return disposition == null || disposition.isBlank() ? "attachment" : disposition;
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
