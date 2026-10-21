package com.eia.document.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Public command that places a legal hold on a document. The tenant is never
 * part of the body; it comes from {@code X-EIA-Tenant}. The reason is recorded
 * on the document and blocks disposition until the hold is released.
 */
public record HoldDocumentCommand(
        @NotBlank(message = "reason is required")
        @Size(max = 500, message = "reason must be at most 500 characters")
        String reason
) {}
