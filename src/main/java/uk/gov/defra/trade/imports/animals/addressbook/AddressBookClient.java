package uk.gov.defra.trade.imports.animals.addressbook;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Reads single addresses from the address book so referenced parties can be resolved on read.
 *
 * <p>The address book runs no authentication of its own — it trusts
 * {@code Trade-Imports-Organisation-Id} and requires it to match the organisation in the path
 * (cv-010, see its {@code IdentityHeaderFilter}). The value must therefore always originate from
 * the reader's authenticated session and be passed straight through; this client never chooses or
 * defaults one.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AddressBookClient {

    private static final String ORGANISATION_ID_HEADER = "Trade-Imports-Organisation-Id";

    /** Resolved by bean name to the address-book-scoped client, not the shared default. */
    private final RestClient addressBookRestClient;

    private final AddressBookConfig addressBookConfig;

    /**
     * Fetches one address by id, within the given organisation.
     *
     * <p>Returns empty for a record that does not exist, and for one that exists in another
     * organisation — the address book scopes its lookups on the organisation, so a mismatched read
     * finds nothing rather than another organisation's data. A soft-deleted record comes back as a
     * tombstone with {@code deleted} set, which the caller decides what to do with.
     *
     * <p>Anything else — a 5xx, a timeout, a connection failure — propagates. An unavailable
     * address book must never be indistinguishable from an address that was deleted.
     *
     * @param organisationId the reader's organisation, taken from their authenticated session
     * @param addressId      the address-book record to read
     * @return the record, or empty when there is none to read
     */
    public Optional<AddressBookRecord> findById(String organisationId, String addressId) {
        try {
            return Optional.ofNullable(addressBookRestClient.get()
                .uri(addressBookConfig.addressByIdPath(), organisationId, addressId)
                .header(ORGANISATION_ID_HEADER, organisationId)
                .retrieve()
                .body(AddressBookRecord.class));
        } catch (HttpClientErrorException.NotFound _) {
            log.debug("Address {} not found in organisation {}", addressId, organisationId);
            return Optional.empty();
        }
    }
}
