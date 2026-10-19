package com.eia.document.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Public command to register a document. The tenant is taken from the
 * {@code X-EIA-Tenant} header, never from the body.
 */
public record CreateDocumentCommand(
        @NotBlank @Size(max = 300) String title,
        @NotBlank String documentType,
        @NotBlank String classification,
        LocalDate retentionUntil
) {}
