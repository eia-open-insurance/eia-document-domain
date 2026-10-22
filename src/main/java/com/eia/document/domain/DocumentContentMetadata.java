package com.eia.document.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata of the bytes stored for one document. The domain does not keep the
 * content itself; the expert owns object storage and returns this projection
 * from the upload, the metadata read, and the content tombstone.
 *
 * @param currentVersion the revision number of the uploaded bytes, so a caller
 *                       can request that revision through
 *                       {@code GET .../content/versions/{version}}
 * @param deletedAt      the tombstone instant, or {@code null} while the bytes
 *                       are downloadable; a tombstone keeps the filename, MIME
 *                       type, size, digest, and revision number but clears the
 *                       bytes
 * @param deleted        the derived boolean form of {@code deletedAt}, so a
 *                       caller can test one field without a null check
 */
public record DocumentContentMetadata(
        UUID documentId,
        String fileName,
        String contentType,
        long sizeBytes,
        String sha256,
        Instant uploadedAt,
        long currentVersion,
        Instant deletedAt,
        boolean deleted
) {
    public DocumentContentMetadata {
        deleted = deletedAt != null;
    }
}
