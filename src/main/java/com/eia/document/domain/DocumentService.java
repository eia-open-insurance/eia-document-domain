package com.eia.document.domain;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
     * Common global search. The tenant-scoped expert list is the only read path
     * available to the domain, so matching, ordering, and limiting happen here
     * and produce the shared search shape used by the shell.
     */
    public DocumentSearchResponse search(UUID tenantId, String q, int limit) {
        String query = q == null ? "" : q.trim();
        int effectiveLimit = Math.min(Math.max(limit, 1), MAX_SEARCH_LIMIT);
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
}
