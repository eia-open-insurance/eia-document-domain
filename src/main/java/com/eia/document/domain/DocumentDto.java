package com.eia.document.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Document projection returned by the public API. Mirrors the expert aggregate
 * shape so the domain can apply its rules without a second read.
 */
public record DocumentDto(
        UUID id,
        UUID tenantId,
        String documentNumber,
        String title,
        String documentType,
        String classification,
        String status,
        LocalDate retentionUntil,
        int version,
        Instant createdAt,
        Instant updatedAt
) {}
