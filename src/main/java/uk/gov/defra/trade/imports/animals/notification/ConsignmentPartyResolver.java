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
import uk.gov.defra.trade.imports.animals.exceptions.UnresolvableConsignmentPartyException;

/**
 * Validates that parties held as address-book references still resolve at submit.
 * The frontend inflates inline details onto the notification before submit; this
 * resolver is a belt-and-braces guard that every referenced party still exists.
 *
 * <p>Reads do not resolve — the frontend fills in party names for display, so the backend hands
 * out what it stores.
 *
 * <p>A miss fails the submit rather than transmitting a broken party. A failing submit reports
 * every role that could not be resolved, not just the first, so the caller can correct them in
 * one pass.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConsignmentPartyResolver {

    // Each role that can hold a reference, named as the notification exposes it, so a rejected
    // submit points at the field the caller sent.
    private static final String PLACE_OF_ORIGIN = "placeOfOrigin";
    private static final String CONSIGNOR = "consignor";
    private static final String CONSIGNEE = "consignee";
    private static final String IMPORTER = "importer";
    private static final String DESTINATION = "destination";
    private static final String CONSIGNMENT = "consignment";

    private final AddressBookClient addressBookClient;

    /**
     * Validates address-book references for a <b>submission</b>. Every reference must resolve:
     * a miss fails the submit, and {@code organisationId} is required. The failure names every
     * unresolved role, in role order. Does not mutate the notification — inline details are
     * already on the aggregate from the frontend.
     */
    public void validatePartiesAtSubmit(NotificationAggregate notificationAggregate, String organisationId) {
        validateParties(notificationAggregate, organisationId, true);
    }

    private void validateParties(
        NotificationAggregate notificationAggregate, String organisationId, boolean failOnMiss) {
        Notification notification = notificationAggregate.requireNotification();
        List<String> addressIds = referencedAddressIds(notification);
        if (addressIds.isEmpty()) {
            return;
        }
        if (failOnMiss && (organisationId == null || organisationId.isBlank())) {
            throw new BadRequestException(
                "Cannot resolve address-book parties for outbox transmission: organisation id is required");
        }
        Map<String, Optional<ConsignmentParty>> lookups = lookUpAll(addressIds, organisationId);
        // Assigned in a fixed role order, so a failed submit names the same roles in the same
        // order every time regardless of which lookup finished first.
        Map<String, String> unresolved = new LinkedHashMap<>();
        checkReference(PLACE_OF_ORIGIN, notification.getPlaceOfOrigin(), lookups, unresolved);
        checkReference(CONSIGNOR, notification.getConsignor(), lookups, unresolved);
        checkReference(CONSIGNEE, notification.getConsignee(), lookups, unresolved);
        checkReference(IMPORTER, notification.getImporter(), lookups, unresolved);
        checkReference(DESTINATION, notification.getDestination(), lookups, unresolved);
        checkReference(CONSIGNMENT, notification.getConsignment(), lookups, unresolved);
        if (failOnMiss && !unresolved.isEmpty()) {
            // Not logged here: the exception handler logs the rejected submit at WARN.
            throw new UnresolvableConsignmentPartyException(unresolved);
        }
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

    private void checkReference(
        String role,
        ConsignmentParty party,
        Map<String, Optional<ConsignmentParty>> lookups,
        Map<String, String> unresolved) {
        if (party == null || party.getAddressId() == null) {
            return;
        }
        String addressId = party.getAddressId();
        Optional<ConsignmentParty> resolved =
            lookups.getOrDefault(addressId, Optional.empty());
        if (resolved.isEmpty()) {
            unresolved.put(role, addressId);
        }
    }

    /**
     * Each distinct address referenced by a party, in role order. Empty when none is.
     *
     * <p>{@code null} parties are expected — an unfilled role on a draft is simply absent. They
     * are skipped rather than treated as an error because this method serves submit validation
     * (where missing required roles surface later as unresolved references or upstream
     * validation).
     *
     * <p>{@code null} {@code addressId} on a non-null party means an inline party ({@link
     * ConsignmentParty}); those are also skipped because there is nothing to look up. A submit
     * that must not go out with a nameless referenced role is rejected via {@link
     * UnresolvableConsignmentPartyException}, not here.
     */
    private static List<String> referencedAddressIds(Notification notification) {
        return Stream.of(
                notification.getPlaceOfOrigin(),
                notification.getConsignor(),
                notification.getConsignee(),
                notification.getImporter(),
                notification.getDestination(),
                notification.getConsignment())
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
     * address", which {@link #checkReference} turns into a rejected submit.
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
