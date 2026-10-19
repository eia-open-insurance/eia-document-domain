package com.eia.document.domain;

import jakarta.validation.constraints.NotBlank;

/**
 * Public command to advance a document along its lifecycle.
 */
public record ChangeStatusCommand(@NotBlank String status) {}
