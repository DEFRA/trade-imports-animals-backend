package uk.gov.defra.trade.imports.animals.outbox.gbnag;

public record ReferencedDocument(
    String typeCode,
    String relationshipTypeCode,
    String identifier,
    String issueDateTime
) {}
