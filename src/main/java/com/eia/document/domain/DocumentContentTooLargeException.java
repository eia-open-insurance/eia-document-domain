package com.eia.document.domain;

/**
 * An upload body above {@link DocumentContentPolicy#MAX_CONTENT_BYTES}. Surfaced
 * as an {@code application/problem+json} 413, matching the expert's own
 * payload-too-large contract.
 */
public class DocumentContentTooLargeException extends RuntimeException {
    private final long sizeBytes;

    public DocumentContentTooLargeException() {
        this(-1);
    }

    public DocumentContentTooLargeException(long sizeBytes) {
        super(DocumentContentPolicy.tooLargeDetail());
        this.sizeBytes = sizeBytes;
    }

    /**
     * @return the rejected body size in bytes, or {@code -1} when it was not
     *         known (for example a declared Content-Length above the limit)
     */
    public long sizeBytes() {
        return sizeBytes;
    }
}
