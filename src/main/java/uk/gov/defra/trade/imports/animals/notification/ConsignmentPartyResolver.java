package uk.gov.defra.trade.imports.animals.notification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import uk.gov.defra.trade.imports.animals.addressbook.AddressBookClient;
import uk.gov.defra.trade.imports.animals.addressbook.AddressBookRecord;
import uk.gov.defra.trade.imports.animals.exceptions.BadRequestException;

/**
 * Fills in the details of parties held as address-book references, on the one path that has to
 * have them: GBNAG transmission. Nothing is written back to storage, so an address edited in the
 * address book shows through on the next send without the notification being touched.
 *
 * <p>A party held inline carries no {@code addressId} and passes through unchanged. Two roles are
 * always inline and so are never resolved here: {@code placeOfOrigin} and {@code consignment},
 * the consignment contact, which is per-notification and reset on copy.
 *
 * <p>Reads do not resolve — the frontend fills in party names for display, so the backend hands
 * out what it stores.
 *
 * <p>A miss depends on the event: {@link #resolveForSubmission} fails, because a GBNAG document
 * must not carry a nameless party; {@link #resolveForDraft} leaves the role blank, so a deleted
 * address does not block a draft save.
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
        List<String> addressIds = referencedAddressIds(notification);
        if (addressIds.isEmpty()) {
            return notification;
        }
        if (failOnMiss && (organisationId == null || organisationId.isBlank())) {
            throw new BadRequestException(
                "Cannot resolve address-book parties for outbox transmission: organisation id is required");
        }
        Map<String, Optional<ConsignmentParty>> lookups = lookUpAll(addressIds, organisationId);
        // Assigned in a fixed role order, so the reference a failed submit names is the same one
        // every time regardless of which lookup finished first.
        notification.setConsignor(
            resolveIfReference(notification.getConsignor(), failOnMiss, lookups));
        notification.setConsignee(
            resolveIfReference(notification.getConsignee(), failOnMiss, lookups));
        notification.setImporter(
            resolveIfReference(notification.getImporter(), failOnMiss, lookups));
        notification.setDestination(
            resolveIfReference(notification.getDestination(), failOnMiss, lookups));
        return notification;
    }

    private Map<String, Optional<ConsignmentParty>> lookUpAll(
        List<String> addressIds, String organisationId) {
        Map<String, String> callerContext = MDC.getCopyOfContextMap();
        Map<String, CompletableFuture<Optional<ConsignmentParty>>> inFlight =
            new LinkedHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String addressId : addressIds) {
                inFlight.put(addressId, CompletableFuture.supplyAsync(
                    withContext(callerContext, () -> resolve(addressId, organisationId)), executor));
            }
        }
        Map<String, Optional<ConsignmentParty>> lookups = new LinkedHashMap<>();
        inFlight.forEach((addressId, lookup) -> lookups.put(addressId, joined(lookup)));
        return lookups;
    }

    private static <T> Supplier<T> withContext(Map<String, String> callerContext, Supplier<T> work) {
        return () -> {
            if (callerContext == null) {
                return work.get();
            }
            MDC.setContextMap(callerContext);
            try {
                return work.get();
            } finally {
                MDC.clear();
            }
        };
    }

    private static <T> T joined(CompletableFuture<T> lookup) {
        try {
            return lookup.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    private ConsignmentParty resolveIfReference(
        ConsignmentParty party,
        boolean failOnMiss,
        Map<String, Optional<ConsignmentParty>> lookups) {
        if (party == null || party.getAddressId() == null) {
            return party;
        }
        String addressId = party.getAddressId();
        Optional<ConsignmentParty> resolved =
            lookups.getOrDefault(addressId, Optional.empty());
        if (resolved.isEmpty() && failOnMiss) {
            log.error(
                "Address-book party could not be resolved for outbox (addressId={})", addressId);
            throw new BadRequestException(
                "Cannot submit notification: address-book party could not be resolved (addressId="
                    + addressId + ")");
        }
        return resolved.orElse(null);
    }

    /**
     * Each distinct address referenced by a party, in role order. Empty when none is.
     *
     * <p>The four roles that can hold a reference. {@code placeOfOrigin} and {@code consignment}
     * are always inline, so they are not read here even if one arrives carrying an
     * {@code addressId}.
     */
    private static List<String> referencedAddressIds(Notification notification) {
        return Stream.of(
                notification.getConsignor(),
                notification.getConsignee(),
                notification.getImporter(),
                notification.getDestination())
            .filter(Objects::nonNull)
            .map(ConsignmentParty::getAddressId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    }

    /**
     * One address, as the party it stands for. Empty when the record has been deleted, or when the
     * submitter's organisation cannot see it — the address book scopes its lookups on the
     * organisation, so a reference belonging to another one finds nothing. Both read as "no such
     * address", which {@link #resolveIfReference} turns into a rejected submit or a blank role
     * depending on the event.
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
