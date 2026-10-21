package com.eia.document.domain;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies domain rules and orchestrates the expert. The domain decides; the
 * expert persists.
 */
@Service
public class DocumentService {
    private static final int MAX_SEARCH_LIMIT = 25;
    private static final String SEARCH_TYPE = "DOCUMENT";
    private static final String FIELD_SEPARATOR = " · ";

    private final DocumentExpertClient client;

    public DocumentService(DocumentExpertClient client) {
        this.client = client;
    }

    public List<DocumentDto> list(UUID tenantId) {
        return client.findAll(tenantId);
    }

    /**
     * Tenant-scoped list with the optional document-link filter. The expert
     * owns the pair rule and the closed link-type set, so an incomplete or
     * unknown filter keeps the expert's 400 problem instead of being rewritten
     * in the domain.
     */
    public List<DocumentDto> list(UUID tenantId, String linkedType, String linkedId) {
        String type = trimToNull(linkedType);
        String id = trimToNull(linkedId);
        if (type == null && id == null) {
            return client.findAll(tenantId);
        }
        return client.findAll(tenantId, type, id);
    }

    public List<DocumentLinkDto> links(UUID tenantId, UUID documentId) {
        return client.findLinks(tenantId, documentId);
    }

    public DocumentLinkDto addLink(UUID tenantId, UUID documentId,
                                   CreateDocumentLinkCommand command) {
        return client.createLink(tenantId, documentId, command);
    }

    public void removeLink(UUID tenantId, UUID documentId, UUID linkId) {
        client.deleteLink(tenantId, documentId, linkId);
    }

    /**
     * Common global search. The expert search projection is preferred because
     * it can match a link reference; if that route is not available this falls
     * back to the tenant list and local metadata matching, which is the
     * pass-through behaviour for an older expert.
     */
    public DocumentSearchResponse search(UUID tenantId, String q, int limit) {
        String query = q == null ? "" : q.trim();
        int effectiveLimit = Math.min(Math.max(limit, 1), MAX_SEARCH_LIMIT);
        Optional<DocumentSearchResponse> expert = client.search(tenantId, query, effectiveLimit);
        if (expert != null && expert.isPresent()) {
            return expert.get();
        }
        List<DocumentDto> documents = client.findAll(tenantId);
        if (documents == null) {
            documents = List.of();
        }
        List<DocumentSearchItem> items = documents.stream()
                .filter(document -> document.tenantId() == null || tenantId.equals(document.tenantId()))
                .filter(document -> matches(document, query))
                .sorted(Comparator.comparing(DocumentDto::updatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(effectiveLimit)
                .map(DocumentService::toSearchItem)
                .toList();
        return new DocumentSearchResponse(query, effectiveLimit, items);
    }

    public DocumentDto get(UUID tenantId, UUID id) {
        return client.findById(tenantId, id);
    }

    public DocumentDto create(UUID tenantId, CreateDocumentCommand command) {
        DocumentRules.validateCreate(command, Instant.now());
        return client.create(tenantId, command);
    }

    public DocumentDto changeStatus(UUID tenantId, UUID id, ChangeStatusCommand command) {
        DocumentDto current = client.findById(tenantId, id);
        DocumentStatus target = DocumentRules.validateTransition(current, command.status());
        return client.changeStatus(tenantId, id, target.name(), current.version());
    }

    /**
     * Places a legal hold. The expert owns the stored snapshot and the state
     * conflict (a document already held, or one whose status cannot be held), so
     * its 400/404/409 problem is preserved for the caller.
     */
    public DocumentDto hold(UUID tenantId, UUID documentId, HoldDocumentCommand command) {
        return client.hold(tenantId, documentId, trimToNull(command.reason()));
    }

    /**
     * Releases the active legal hold and restores the status stored when the
     * hold was placed.
     */
    public DocumentDto releaseHold(UUID tenantId, UUID documentId) {
        return client.releaseHold(tenantId, documentId);
    }

    /**
     * Disposes a document. The expert rejects a held, unretained, or otherwise
     * non-disposable document with its 409 problem.
     */
    public DocumentDto dispose(UUID tenantId, UUID documentId) {
        return client.dispose(tenantId, documentId);
    }

    /**
     * Proxies a raw content upload. The 10 MiB limit is a public API rule, so
     * it is enforced here before the expert is called; the file name is reduced
     * to a display name and the media type defaults to
     * {@code application/octet-stream}.
     */
    public DocumentContentMetadata uploadContent(UUID tenantId, UUID documentId, String fileName,
                                                 String contentType, byte[] content) {
        byte[] bytes = content == null ? new byte[0] : content;
        if (bytes.length > DocumentContentPolicy.MAX_CONTENT_BYTES) {
            throw new DocumentContentTooLargeException(bytes.length);
        }
        return client.uploadContent(tenantId, documentId,
                DocumentContentPolicy.requireFileName(fileName),
                DocumentContentPolicy.contentTypeOrOctetStream(contentType),
                bytes);
    }

    public DocumentContentDownload downloadContent(UUID tenantId, UUID documentId) {
        return client.downloadContent(tenantId, documentId);
    }

    /**
     * Version history is a read-through projection: the expert owns both the
     * retained revisions and their order.
     */
    public List<DocumentContentVersion> contentVersions(UUID tenantId, UUID documentId) {
        return client.contentVersions(tenantId, documentId);
    }

    public DocumentContentDownload downloadContentVersion(UUID tenantId, UUID documentId,
                                                          long version) {
        return client.downloadContentVersion(tenantId, documentId, version);
    }

    public DocumentContentMetadata contentMetadata(UUID tenantId, UUID documentId) {
        return client.contentMetadata(tenantId, documentId);
    }

    private static boolean matches(DocumentDto document, String query) {
        if (query.isEmpty()) {
            return true;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        return contains(document.documentNumber(), needle)
                || contains(document.title(), needle)
                || contains(document.documentType(), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static DocumentSearchItem toSearchItem(DocumentDto document) {
        String primary = isBlank(document.title()) ? document.documentType() : document.title();
        return new DocumentSearchItem(
                document.id(),
                SEARCH_TYPE,
                document.documentNumber(),
                secondary(primary, document.status()),
                document.status(),
                "/document?record=" + document.id());
    }

    private static String secondary(String primary, String status) {
        if (isBlank(primary)) {
            return status;
        }
        if (isBlank(status)) {
            return primary;
        }
        return primary + FIELD_SEPARATOR + status;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }
}
