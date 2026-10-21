package com.eia.document.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata of the bytes stored for one document. The domain does not keep the
 * content itself; the expert owns object storage and returns this projection
 * from both the upload and the metadata read.
 *
 * @param currentVersion the revision number of the uploaded bytes, so a caller
 *                       can request that revision through
 *                       {@code GET .../content/versions/{version}}
 */
public record DocumentContentMetadata(
        UUID documentId,
        String fileName,
        String contentType,
        long sizeBytes,
        String sha256,
        Instant uploadedAt,
        long currentVersion
) {}
