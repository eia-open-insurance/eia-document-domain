package com.eia.document.domain;

import org.springframework.http.MediaType;

/**
 * One content stream as returned by the expert, together with the
 * representation headers the public API must expose unchanged.
 */
public record DocumentContentDownload(
        byte[] bytes,
        MediaType contentType,
        String contentDisposition,
        String sha256
) {}
