package com.eia.document.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Business rules the domain enforces before delegating to the expert.
 *
 * <ul>
 *   <li>Document type and classification MUST come from the SPEC 11.6.5
 *       enumerations.</li>
 *   <li>A retention date MUST NOT precede the document creation date.</li>
 *   <li>A document MUST carry a retention date before it becomes ACTIVE
 *       (SPEC DOC-05).</li>
 *   <li>Status changes MUST follow the SPEC 11.6.6 lifecycle, and terminal
 *       states are final.</li>
 * </ul>
 */
public final class DocumentRules {
    public static final String INVALID_DOCUMENT_TYPE = "INVALID_DOCUMENT_TYPE";
    public static final String INVALID_CLASSIFICATION = "INVALID_CLASSIFICATION";
    public static final String INVALID_STATUS = "INVALID_STATUS";
    public static final String INVALID_STATUS_TRANSITION = "INVALID_STATUS_TRANSITION";
    public static final String RETENTION_BEFORE_CREATION = "RETENTION_BEFORE_CREATION";
    public static final String RETENTION_REQUIRED_FOR_ACTIVE = "RETENTION_REQUIRED_FOR_ACTIVE";

    private DocumentRules() {}

    public static void validateCreate(CreateDocumentCommand command, Instant now) {
        if (DocumentType.find(command.documentType()).isEmpty()) {
            throw new DocumentRuleException(INVALID_DOCUMENT_TYPE,
                    "documentType '" + command.documentType()
                            + "' is not defined by SPEC 11.6.5; expected one of: " + DocumentType.names());
        }
        if (Confidentiality.find(command.classification()).isEmpty()) {
            throw new DocumentRuleException(INVALID_CLASSIFICATION,
                    "classification '" + command.classification()
                            + "' is not defined by SPEC 11.6.5; expected one of: " + Confidentiality.names());
        }
        if (command.retentionUntil() != null) {
            LocalDate createdDate = LocalDate.ofInstant(now, ZoneOffset.UTC);
            if (command.retentionUntil().isBefore(createdDate)) {
                throw new DocumentRuleException(RETENTION_BEFORE_CREATION,
                        "retentionUntil " + command.retentionUntil()
                                + " is before the document creation date " + createdDate);
            }
        }
    }

    public static DocumentStatus validateTransition(DocumentDto current, String targetRaw) {
        DocumentStatus from = DocumentStatus.of(current.status());
        DocumentStatus target = DocumentStatus.find(targetRaw)
                .orElseThrow(() -> new DocumentRuleException(INVALID_STATUS,
                        "status '" + targetRaw + "' is not defined by SPEC 11.6.5; expected one of: "
                                + DocumentStatus.names()));
        if (!from.canTransitionTo(target)) {
            throw new DocumentRuleException(INVALID_STATUS_TRANSITION,
                    from + " cannot transition to " + target
                            + "; allowed next status: " + from.allowedNext());
        }
        if (target == DocumentStatus.ACTIVE && current.retentionUntil() == null) {
            throw new DocumentRuleException(RETENTION_REQUIRED_FOR_ACTIVE,
                    "document " + current.id()
                            + " requires a retention date before it becomes ACTIVE (SPEC DOC-05)");
        }
        return target;
    }
}
