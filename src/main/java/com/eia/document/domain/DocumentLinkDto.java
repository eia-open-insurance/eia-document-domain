package com.eia.document.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Link between a document and another record (a policy, claim, party, payment,
 * contract, or another document). The document vertical owns the association,
 * so the public projection carries both the typed target and the cached label
 * used to render the chip without a cross-vertical call.
 */
public record DocumentLinkDto(
        UUID id,
        UUID documentId,
        String linkedType,
        UUID linkedId,
        String linkedReference,
        String linkedLabel,
        Instant createdAt
) {}
