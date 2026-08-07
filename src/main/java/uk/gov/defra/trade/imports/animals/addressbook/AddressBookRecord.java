package uk.gov.defra.trade.imports.animals.addressbook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One address as the address book serves it — the fields this service reads, not the whole wire
 * contract. Unknown properties are ignored so the address book can add fields without breaking
 * resolution here.
 *
 * <p>{@code deleted} is the soft-delete tombstone: the by-id endpoint answers 200 with
 * {@code deleted: true} rather than 404, so a caller can tell a deletion from a record that never
 * existed (cv-016).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AddressBookRecord(
    String id,
    String name,
    String addressLine1,
    String addressLine2,
    String townOrCity,
    String county,
    String postcode,
    String countryCode,
    String phone,
    String email,
    boolean deleted) {}
