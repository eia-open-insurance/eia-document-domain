package com.eia.document.domain;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Applies domain rules and orchestrates the expert. The domain decides; the
 * expert persists.
 */
@Service
public class DocumentService {
    private final DocumentExpertClient client;

    public DocumentService(DocumentExpertClient client) {
        this.client = client;
    }

    public List<DocumentDto> list(UUID tenantId) {
        return client.findAll(tenantId);
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
}
