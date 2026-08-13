package uk.gov.defra.trade.imports.animals.notification;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gov.defra.trade.imports.animals.addressbook.AddressBookClient;
import uk.gov.defra.trade.imports.animals.addressbook.AddressBookRecord;
import uk.gov.defra.trade.imports.animals.exceptions.BadRequestException;

/**
 * Fills in the details of parties held as address-book references, on the one path that has to have
 * them: GBNAG transmission. Nothing is written back to storage — the reference is the stored truth,
 * so an address edited in the address book shows through on the next send without the notification
 * being touched (AC4).
 *
 * <p>A party held inline passes through unchanged (AC5), and so does a notification created before
 * the address book existed — those carry no {@code addressId}, so there is nothing to resolve.
 *
 * <p>Reads do not resolve. The dashboard is the only reader that renders party names and it fills
 * them in itself, alongside the country and commodity codes it already resolves, so the backend
 * hands out what it stores and keeps one address-book caller rather than three.
 *
 * <p>How hard a miss is depends on the event: {@link #resolveForSubmission} fails, because a GBNAG
 * document must not carry a nameless party, while {@link #resolveForDraft} leaves the role blank,
 * because a deleted address must not block a trader from saving the rest of their draft.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConsignmentPartyResolver {

    private final AddressBookClient addressBookClient;

    /**
     * Resolves address-book references for a <b>submission</b> — the event that puts the party on a
     * GBNAG document. Every reference must resolve: a miss fails the submit rather than transmitting
     * {@code name=null} / {@code postalAddress=null}, and {@code organisationId} is required.
     *
     * <p>Mutates the given instance, so pass a copy — what is stored must keep the reference alone.
     */
    public Notification resolveForSubmission(Notification notification, String organisationId) {
        return resolveParties(notification, organisationId, true);
    }

    /**
     * Resolves address-book references for a <b>draft edit</b>, best-effort. A reference that no
     * longer resolves leaves the role blank instead of failing the write: UCD's ruling is that a
     * deleted address behaves as if it were never selected, so deleting an address must not block
     * the trader from saving the rest of their draft. The submit is where it has to be complete.
     *
     * <p>Mutates the given instance, so pass a copy — what is stored must keep the reference alone.
     */
    public Notification resolveForDraft(Notification notification, String organisationId) {
        if (organisationId == null || organisationId.isBlank()) {
            // No organisation to look in. The draft still saves; the names simply stay unresolved.
            return notification;
        }
        return resolveParties(notification, organisationId, false);
    }

    private Notification resolveParties(
        Notification notification, String organisationId, boolean failOnMiss) {
        if (!hasGbnAgAddressBookReference(notification)) {
            return notification;
        }
        if (failOnMiss && (organisationId == null || organisationId.isBlank())) {
            throw new BadRequestException(
                "Cannot resolve address-book parties for outbox transmission: organisation id is required");
        }
        // One lookup per distinct address: a trader who is both consignor and consignee should not
        // be fetched twice, and this runs against a bounded timeout budget.
        Map<String, Optional<ConsignmentParty>> lookups = new HashMap<>();
        UnaryOperator<ConsignmentParty> resolver =
            party -> resolveParty(party, organisationId, failOnMiss, lookups);
        notification.setConsignor(resolver.apply(notification.getConsignor()));
        notification.setConsignee(resolver.apply(notification.getConsignee()));
        notification.setImporter(resolver.apply(notification.getImporter()));
        notification.setDestination(resolver.apply(notification.getDestination()));
        notification.setPlaceOfOrigin(resolver.apply(notification.getPlaceOfOrigin()));
        return notification;
    }

    /**
     * The one place a role is inspected before it becomes an address id. A party with no
     * {@code addressId} is held inline and passes through untouched (AC5) — there is nothing to
     * look up — so everything below this deals in address ids alone.
     */
    private ConsignmentParty resolveParty(
        ConsignmentParty party,
        String organisationId,
        boolean failOnMiss,
        Map<String, Optional<ConsignmentParty>> lookups) {
        if (party == null || party.getAddressId() == null) {
            return party;
        }
        String addressId = party.getAddressId();
        Optional<ConsignmentParty> resolved =
            lookups.computeIfAbsent(addressId, id -> resolve(id, organisationId));
        if (resolved.isEmpty() && failOnMiss) {
            log.error(
                "Address-book party could not be resolved for outbox (addressId={})", addressId);
            throw new BadRequestException(
                "Cannot submit notification: address-book party could not be resolved (addressId="
                    + addressId + ")");
        }
        return resolved.orElse(null);
    }

    private static boolean hasGbnAgAddressBookReference(Notification notification) {
        return Stream.of(
                notification.getConsignor(),
                notification.getConsignee(),
                notification.getImporter(),
                notification.getDestination(),
                notification.getPlaceOfOrigin())
            .anyMatch(party -> party != null && party.getAddressId() != null);
    }

    /**
     * One address, as the party it stands for. Empty when the record has been deleted, or when the
     * submitter's organisation cannot see it — the address book scopes its lookups on the
     * organisation, so a reference belonging to another one finds nothing. Both read as "no such
     * address", which {@link #resolveParty} turns into a rejected submit or a blank role depending
     * on the event.
     *
     * <p>An address book that is down does not take that path: the client throws and the throw
     * propagates, because an outage must not look identical to a deletion.
     */
    private Optional<ConsignmentParty> resolve(String addressId, String organisationId) {
        Optional<AddressBookRecord> found = addressBookClient.findById(organisationId, addressId);
        if (found.isEmpty()) {
            log.info(
                "Address-book party not found (organisationId={}, addressId={})",
                organisationId, addressId);
            return Optional.empty();
        }
        AddressBookRecord addressBookRecord = found.get();
        if (addressBookRecord.deleted()) {
            log.info(
                "Address-book party is soft-deleted (organisationId={}, addressId={})",
                organisationId, addressId);
            return Optional.empty();
        }
        return Optional.of(toConsignmentParty(addressId, addressBookRecord));
    }

    private ConsignmentParty toConsignmentParty(
        String addressId, AddressBookRecord addressBookRecord) {
        return ConsignmentParty.builder()
            .addressId(addressId)
            .name(addressBookRecord.name())
            .email(addressBookRecord.email())
            .phone(addressBookRecord.phone())
            .address(Address.builder()
                .addressLine1(addressBookRecord.addressLine1())
                .addressLine2(addressBookRecord.addressLine2())
                .townOrCity(addressBookRecord.townOrCity())
                .county(addressBookRecord.county())
                .postcode(addressBookRecord.postcode())
                .countryCode(addressBookRecord.countryCode())
                .build())
            .build();
    }
}
