package com.eia.document.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Public command that associates a document with another record. The tenant is
 * never part of the body; it comes from {@code X-EIA-Tenant}. The expert owns
 * the closed link-type set and target existence checks.
 */
public record CreateDocumentLinkCommand(
        @NotBlank(message = "linkedType is required")
        String linkedType,

        @NotNull(message = "linkedId is required")
        UUID linkedId,

        String linkedReference
) {}
