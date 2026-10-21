package com.eia.document.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class DocumentRulesTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

    @Test
    void definesTheSpecificationEnumerations() {
        assertThat(DocumentType.values()).hasSize(60);
        assertThat(DocumentStatus.values()).hasSize(12);
        assertThat(Confidentiality.values()).hasSize(6);
        assertThat(DocumentType.find("B_READ")).contains(DocumentType.B_READ);
        assertThat(Confidentiality.find("PRIVILEGED")).contains(Confidentiality.PRIVILEGED);
    }

    @Test
    void acceptsAValidRegistration() {
        DocumentRules.validateCreate(new CreateDocumentCommand(
                "Policy schedule", "POLICY", "CONFIDENTIAL", LocalDate.of(2036, 10, 19)), NOW);
    }

    @Test
    void rejectsRetentionBeforeCreation() {
        DocumentRuleException ex = catchThrowableOfType(() -> DocumentRules.validateCreate(
                        new CreateDocumentCommand("Old", "POLICY", "INTERNAL", LocalDate.of(2026, 10, 18)), NOW),
                DocumentRuleException.class);

        assertThat(ex.code()).isEqualTo(DocumentRules.RETENTION_BEFORE_CREATION);
    }

    @Test
    void rejectsUnknownTypeAndClassification() {
        DocumentRuleException type = catchThrowableOfType(() -> DocumentRules.validateCreate(
                        new CreateDocumentCommand("X", "NOT_A_TYPE", "INTERNAL", null), NOW),
                DocumentRuleException.class);
        assertThat(type.code()).isEqualTo(DocumentRules.INVALID_DOCUMENT_TYPE);

        DocumentRuleException classification = catchThrowableOfType(() -> DocumentRules.validateCreate(
                        new CreateDocumentCommand("X", "POLICY", "TOP_SECRET", null), NOW),
                DocumentRuleException.class);
        assertThat(classification.code()).isEqualTo(DocumentRules.INVALID_CLASSIFICATION);
    }

    @Test
    void followsTheSpecificationLifecycleEdges() {
        assertThat(DocumentStatus.PENDING_UPLOAD.allowedNext()).containsExactly(DocumentStatus.RECEIVED);
        assertThat(DocumentStatus.RECEIVED.allowedNext()).containsExactly(DocumentStatus.SCANNING);
        assertThat(DocumentStatus.SCANNING.allowedNext())
                .containsExactlyInAnyOrder(DocumentStatus.PROCESSING, DocumentStatus.REJECTED);
        assertThat(DocumentStatus.PROCESSING.allowedNext())
                .containsExactlyInAnyOrder(DocumentStatus.CLASSIFIED, DocumentStatus.FAILED);
        assertThat(DocumentStatus.FAILED.allowedNext()).containsExactly(DocumentStatus.PROCESSING);
        assertThat(DocumentStatus.CLASSIFIED.allowedNext()).containsExactly(DocumentStatus.ACTIVE);
        assertThat(DocumentStatus.ACTIVE.allowedNext()).containsExactlyInAnyOrder(
                DocumentStatus.SUPERSEDED, DocumentStatus.ON_HOLD, DocumentStatus.ARCHIVED, DocumentStatus.DISPOSED);
        assertThat(DocumentStatus.ON_HOLD.allowedNext()).containsExactly(DocumentStatus.ACTIVE);
        assertThat(DocumentStatus.ARCHIVED.allowedNext())
                .containsExactlyInAnyOrder(DocumentStatus.DISPOSED, DocumentStatus.ON_HOLD);
        assertThat(DocumentStatus.DISPOSED.isTerminal()).isTrue();
        assertThat(DocumentStatus.REJECTED.isTerminal()).isTrue();
    }

    @Test
    void returnsTargetStatusForAValidTransition() {
        DocumentDto current = document("CLASSIFIED", LocalDate.of(2036, 10, 19));

        DocumentStatus target = DocumentRules.validateTransition(current, "ACTIVE");

        assertThat(target).isEqualTo(DocumentStatus.ACTIVE);
    }

    @Test
    void rejectsIllegalTransition() {
        DocumentRuleException ex = catchThrowableOfType(
                () -> DocumentRules.validateTransition(document("RECEIVED", LocalDate.of(2036, 10, 19)), "ACTIVE"),
                DocumentRuleException.class);

        assertThat(ex.code()).isEqualTo(DocumentRules.INVALID_STATUS_TRANSITION);
    }

    @Test
    void requiresRetentionBeforeActive() {
        DocumentRuleException ex = catchThrowableOfType(
                () -> DocumentRules.validateTransition(document("CLASSIFIED", null), "ACTIVE"),
                DocumentRuleException.class);

        assertThat(ex.code()).isEqualTo(DocumentRules.RETENTION_REQUIRED_FOR_ACTIVE);
    }

    @Test
    void treatsTerminalStatesAsFinal() {
        DocumentRuleException ex = catchThrowableOfType(
                () -> DocumentRules.validateTransition(document("DISPOSED", LocalDate.of(2036, 10, 19)), "ACTIVE"),
                DocumentRuleException.class);

        assertThat(ex.code()).isEqualTo(DocumentRules.INVALID_STATUS_TRANSITION);
    }

    private static DocumentDto document(String status, LocalDate retentionUntil) {
        return new DocumentDto(UUID.randomUUID(), TENANT, "DOC-2026-0000001", "Title",
                "POLICY", "CONFIDENTIAL", status, retentionUntil, null, null, null, null,
                1, NOW, NOW);
    }
}
