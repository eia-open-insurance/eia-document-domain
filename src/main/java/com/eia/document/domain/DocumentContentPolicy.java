package com.eia.document.domain;

import org.springframework.http.MediaType;

/**
 * The public limits and header rules of the document content proxy.
 *
 * <p>The upload body is limited to 10 MiB. The limit is enforced before the
 * expert is called so an oversized upload never reaches object storage.</p>
 */
public final class DocumentContentPolicy {
    /** 10 MiB, the maximum accepted upload body. */
    public static final long MAX_CONTENT_BYTES = 10L * 1024 * 1024;

    public static final String MAX_CONTENT_LABEL = "10 MiB";
    public static final String FILE_NAME_HEADER = "X-File-Name";
    public static final String CONTENT_SHA_HEADER = "X-Content-SHA256";
    public static final String FILE_NAME_REQUIRED = "FILE_NAME_REQUIRED";
    public static final String FILE_TOO_LARGE = "FILE_TOO_LARGE";

    private DocumentContentPolicy() {}

    /**
     * The supplied file name is a display name, never a path. A client path is
     * reduced to its final segment so the expert cannot be asked to write
     * outside its own storage namespace.
     */
    public static String requireFileName(String raw) {
        String name = raw == null ? "" : raw.trim();
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (separator >= 0) {
            name = name.substring(separator + 1).trim();
        }
        if (name.isEmpty()) {
            throw new DocumentRuleException(FILE_NAME_REQUIRED,
                    "'" + FILE_NAME_HEADER + "' is required for a document content upload");
        }
        return name;
    }

    public static String contentTypeOrOctetStream(String raw) {
        return raw == null || raw.isBlank() ? MediaType.APPLICATION_OCTET_STREAM_VALUE : raw.trim();
    }

    public static String tooLargeDetail() {
        return "Document content exceeds the " + MAX_CONTENT_LABEL
                + " (" + MAX_CONTENT_BYTES + " bytes) limit";
    }
}
