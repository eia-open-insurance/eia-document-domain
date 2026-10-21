package com.eia.document.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One retained revision of a document's content. The history is owned by the
 * expert; the domain only projects it so a caller can discover which versions
 * exist before requesting the bytes of one.
 *
 * @param version the revision number, used in
 *                {@code GET /api/v1/documents/{documentId}/content/versions/{version}}
 */
public record DocumentContentVersion(
        UUID documentId,
        long version,
        String fileName,
        String contentType,
        long sizeBytes,
        String sha256,
        Instant uploadedAt
) {}
