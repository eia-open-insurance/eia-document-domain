package com.eia.document.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata of the bytes stored for one document. The domain does not keep the
 * content itself; the expert owns object storage and returns this projection
 * from both the upload and the metadata read.
 */
public record DocumentContentMetadata(
        UUID documentId,
        String fileName,
        String contentType,
        long sizeBytes,
        String sha256,
        Instant uploadedAt
) {}
