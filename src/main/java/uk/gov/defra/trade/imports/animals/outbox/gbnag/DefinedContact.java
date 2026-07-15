package uk.gov.defra.trade.imports.animals.outbox.gbnag;

public record DefinedContact(
    String personName,
    String emailURIUniversalCommunication,
    String telephoneUniversalCommunication
) {}
