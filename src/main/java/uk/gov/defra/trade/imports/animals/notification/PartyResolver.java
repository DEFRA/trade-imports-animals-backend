package uk.gov.defra.trade.imports.animals.notification;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gov.defra.trade.imports.animals.addressbook.AddressBookClient;
import uk.gov.defra.trade.imports.animals.addressbook.AddressBookRecord;

/**
 * Fills in the details of parties held as address-book references, at the moment a notification is
 * read. Nothing is written back: the reference is the stored truth, so an address edited in the
 * address book shows through on the next read without the notification being touched (AC4).
 *
 * <p>A party held inline passes through unchanged (AC5), and so does a notification created before
 * the address book existed — those carry no {@code addressId}, so there is nothing to resolve.
 *
 * <p>Resolution always uses the organisation the reader is signed in to, forwarded from their
 * session. It is never taken from the notification, which has no owner. A reference to an address
 * in another organisation therefore resolves to nothing and renders as "not provided" — a blank
 * rather than a leak, because the address book scopes its lookups on the organisation.
 */
@Component
@RequiredArgsConstructor
public class PartyResolver {

    private final AddressBookClient addressBookClient;

    /**
     * A resolver for one read. Addresses are looked up once each and reused across the parties and
     * — for a page of notifications — across the rows, so a dashboard listing 25 notifications that
     * share a handful of addresses makes a handful of calls rather than one per party per row.
     *
     * <p>Deliberately per-read and not a shared cache: a resolved address must be as fresh as the
     * read that returned it, which is the whole point of storing a reference.
     *
     * @param organisationId the reader's organisation, from their authenticated session
     * @return a function that resolves one party
     */
    public UnaryOperator<ConsignmentParty> forOrganisation(String organisationId) {
        Map<String, Optional<AddressBookRecord>> lookups = new HashMap<>();
        Function<String, Optional<AddressBookRecord>> lookup = addressId ->
            lookups.computeIfAbsent(addressId, id -> addressBookClient.findById(organisationId, id));
        return party -> resolve(party, lookup);
    }

    /**
     * Applies a resolver across every party on a notification, returning a copy. The stored record
     * is left alone — resolved details belong to the response only and must never be persisted back
     * onto the notification, or the reference would grow a stale copy beside it.
     */
    public NotificationResponse resolveAll(
        NotificationResponse response, UnaryOperator<ConsignmentParty> resolver) {
        return response.toBuilder()
            .placeOfOrigin(resolver.apply(response.placeOfOrigin()))
            .consignor(resolver.apply(response.consignor()))
            .consignee(resolver.apply(response.consignee()))
            .importer(resolver.apply(response.importer()))
            .destination(resolver.apply(response.destination()))
            .consignment(resolver.apply(response.consignment()))
            .build();
    }

    /**
     * A referenced party becomes the record's current details. A record that has been deleted — or
     * that the reader's organisation cannot see — resolves to nothing, so the role reads as if it
     * were never entered, which is the agreed handling of a deleted address.
     *
     * <p>An address book that is down does not take that path: the client throws and the throw
     * propagates, because an outage must not look identical to a deletion.
     */
    private ConsignmentParty resolve(
        ConsignmentParty party, Function<String, Optional<AddressBookRecord>> lookup) {
        if (party == null || party.getAddressId() == null) {
            return party;
        }
        return lookup.apply(party.getAddressId())
            .filter(record -> !record.deleted())
            .map(record -> toParty(party.getAddressId(), record))
            .orElse(null);
    }

    private ConsignmentParty toParty(String addressId, AddressBookRecord record) {
        return ConsignmentParty.builder()
            .addressId(addressId)
            .name(record.name())
            .email(record.email())
            .phone(record.phone())
            .address(Address.builder()
                .addressLine1(record.addressLine1())
                .addressLine2(record.addressLine2())
                .townOrCity(record.townOrCity())
                .county(record.county())
                .postcode(record.postcode())
                .countryCode(record.countryCode())
                .build())
            .build();
    }
}
