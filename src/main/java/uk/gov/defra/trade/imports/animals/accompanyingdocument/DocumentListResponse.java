package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import java.util.List;

/**
 * Wrapper response for a list of accompanying document DTOs.
 */
public record DocumentListResponse(List<AccompanyingDocumentDto> items) {}
