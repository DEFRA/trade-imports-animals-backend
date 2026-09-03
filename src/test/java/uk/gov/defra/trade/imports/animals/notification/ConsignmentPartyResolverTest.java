package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.http.HttpStatus;
import uk.gov.defra.trade.imports.animals.addressbook.AddressBookClient;
import uk.gov.defra.trade.imports.animals.addressbook.AddressBookRecord;
import uk.gov.defra.trade.imports.animals.exceptions.BadRequestException;
import uk.gov.defra.trade.imports.animals.exceptions.UnresolvableConsignmentPartyException;

@ExtendWith(MockitoExtension.class)
class ConsignmentPartyResolverTest {

    private static final String ORG = "5900001";

    @Mock
    private AddressBookClient addressBookClient;

    private ConsignmentPartyResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ConsignmentPartyResolver(addressBookClient);
    }

    @Test
    void shouldResolveEveryReferenceableRole() {
        stub("a", "Consignor Ltd");
        stub("b", "Consignee Ltd");
        stub("c", "Importer Ltd");
        stub("d", "Destination Ltd");
        NotificationAggregate notificationAggregate = aggregateOf(Notification.builder()
            .consignor(ConsignmentParty.reference("a"))
            .consignee(ConsignmentParty.reference("b"))
            .importer(ConsignmentParty.reference("c"))
            .destination(ConsignmentParty.reference("d"))
            .build());

        NotificationAggregate resolved = resolver.resolveForSubmission(notificationAggregate, ORG);

        assertThat(resolved.getNotification().getConsignor().getName()).isEqualTo("Consignor Ltd");
        assertThat(resolved.getNotification().getConsignee().getName()).isEqualTo("Consignee Ltd");
        assertThat(resolved.getNotification().getImporter().getName()).isEqualTo("Importer Ltd");
        assertThat(resolved.getNotification().getDestination().getName()).isEqualTo("Destination Ltd");
    }

    @Test
    void shouldNeverResolvePlaceOfOriginOrTheConsignmentContact() {
        // D24 and D26: both are held as copies. Storage strips any addressId before it gets here
        // (ConsignmentParty.inlineOnly), but the resolver does not read them either way.
        ConsignmentParty origin = ConsignmentParty.builder().name("Origin Farm").build();
        ConsignmentParty contact = ConsignmentParty.builder().name("Contact Ltd").build();
        NotificationAggregate notificationAggregate = aggregateOf(Notification.builder()
            .placeOfOrigin(origin)
            .consignment(contact)
            .build());

        NotificationAggregate resolved = resolver.resolveForSubmission(notificationAggregate, ORG);

        assertThat(resolved.getNotification().getPlaceOfOrigin()).isSameAs(origin);
        assertThat(resolved.getNotification().getConsignment()).isSameAs(contact);
        verify(addressBookClient, never()).findById(any(), any());
    }

    @Test
    void shouldFetchOnceWhenTwoRolesShareAnAddress() {
        stub("shared", "Both Ends Ltd");
        NotificationAggregate notificationAggregate = aggregateOf(Notification.builder()
            .consignor(ConsignmentParty.reference("shared"))
            .consignee(ConsignmentParty.reference("shared"))
            .build());

        resolver.resolveForSubmission(notificationAggregate, ORG);

        verify(addressBookClient, times(1)).findById(ORG, "shared");
    }

    @Test
    void shouldNotTouchTheAddressBookWhenNoRoleIsAReference() {
        NotificationAggregate notificationAggregate = aggregateOf(Notification.builder()
            .consignor(ConsignmentParty.builder().name("Inline Ltd").build())
            .build());

        resolver.resolveForSubmission(notificationAggregate, ORG);

        verify(addressBookClient, never()).findById(any(), any());
    }

    @Test
    void shouldPassInlinePartiesThroughUnchanged() {
        stub("a", "Consignor Ltd");
        ConsignmentParty inline = ConsignmentParty.builder().name("Inline Ltd").build();
        NotificationAggregate notificationAggregate = aggregateOf(Notification.builder()
            .consignor(ConsignmentParty.reference("a"))
            .consignee(inline)
            .build());

        NotificationAggregate resolved = resolver.resolveForSubmission(notificationAggregate, ORG);

        assertThat(resolved.getNotification().getConsignee()).isSameAs(inline);
    }

    @Test
    void shouldFailSubmissionNamingEveryUnresolvableRoleInRoleOrder() {
        // Both miss, and both are reported — one pass tells the submitter about all of them.
        // The roles are walked in a fixed order, so the failure reads the same way whichever of
        // the concurrent lookups finished first.
        when(addressBookClient.findById(ORG, "missing-consignor")).thenReturn(Optional.empty());
        when(addressBookClient.findById(ORG, "missing-importer")).thenReturn(Optional.empty());
        NotificationAggregate notificationAggregate = aggregateOf(Notification.builder()
            .consignor(ConsignmentParty.reference("missing-consignor"))
            .importer(ConsignmentParty.reference("missing-importer"))
            .build());

        assertThatExceptionOfType(UnresolvableConsignmentPartyException.class)
            .isThrownBy(() -> resolver.resolveForSubmission(notificationAggregate, ORG))
            .satisfies(thrown -> assertThat(thrown.addressIdByRole()).containsExactly(
                entry("consignor", "missing-consignor"),
                entry("importer", "missing-importer")));
    }

    @Test
    void shouldNameOnlyTheRolesThatMissed() {
        stub("resolves", "Consignee Ltd");
        when(addressBookClient.findById(ORG, "gone")).thenReturn(Optional.empty());
        NotificationAggregate notificationAggregate = aggregateOf(Notification.builder()
            .consignor(ConsignmentParty.reference("gone"))
            .consignee(ConsignmentParty.reference("resolves"))
            .build());

        assertThatExceptionOfType(UnresolvableConsignmentPartyException.class)
            .isThrownBy(() -> resolver.resolveForSubmission(notificationAggregate, ORG))
            .satisfies(thrown -> assertThat(thrown.addressIdByRole())
                .containsExactly(entry("consignor", "gone")));
    }

    @Test
    void shouldReportASoftDeletedAddressAsUnresolvableOnSubmission() {
        when(addressBookClient.findById(ORG, "gone"))
            .thenReturn(Optional.of(deletedAddressRecord("gone", "Gone Ltd")));
        NotificationAggregate notificationAggregate = aggregateOf(Notification.builder()
            .destination(ConsignmentParty.reference("gone"))
            .build());

        assertThatExceptionOfType(UnresolvableConsignmentPartyException.class)
            .isThrownBy(() -> resolver.resolveForSubmission(notificationAggregate, ORG))
            .satisfies(thrown -> assertThat(thrown.addressIdByRole())
                .containsExactly(entry("destination", "gone")));
    }

    @Test
    void shouldTreatASoftDeletedAddressAsUnresolvable() {
        when(addressBookClient.findById(ORG, "gone"))
            .thenReturn(Optional.of(deletedAddressRecord("gone", "Gone Ltd")));
        NotificationAggregate notificationAggregate = aggregateOf(Notification.builder()
            .consignor(ConsignmentParty.reference("gone"))
            .build());

        NotificationAggregate resolved = resolver.resolveForDraft(notificationAggregate, ORG);

        assertThat(resolved.getNotification().getConsignor()).isNull();
    }

    @Test
    void shouldLeaveTheRoleBlankOnADraftRatherThanFailing() {
        when(addressBookClient.findById(ORG, "missing")).thenReturn(Optional.empty());
        NotificationAggregate notificationAggregate = aggregateOf(Notification.builder()
            .consignor(ConsignmentParty.reference("missing"))
            .build());

        NotificationAggregate resolved = resolver.resolveForDraft(notificationAggregate, ORG);

        assertThat(resolved.getNotification().getConsignor()).isNull();
    }

    @Test
    void shouldPropagateAnAddressBookOutageAsItsOwnException() {
        // The lookups run on their own threads, where a failure comes back wrapped in a
        // CompletionException. It must surface as the exception the client actually threw, or the
        // handler that maps it never sees it.
        when(addressBookClient.findById(ORG, "a")).thenThrow(
            new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));
        NotificationAggregate notificationAggregate = aggregateOf(Notification.builder()
            .consignor(ConsignmentParty.reference("a"))
            .build());

        assertThatExceptionOfType(HttpServerErrorException.class)
            .isThrownBy(() -> resolver.resolveForSubmission(notificationAggregate, ORG));
    }

    @Test
    void shouldCarryTheCallersLoggingContextIntoEachLookup() {
        // TraceIdPropagationInterceptor reads the outbound trace header off the MDC, which a fresh
        // thread does not inherit.
        ConcurrentLinkedQueue<String> seen = new ConcurrentLinkedQueue<>();
        when(addressBookClient.findById(eq(ORG), any())).thenAnswer(invocation -> {
            seen.add(String.valueOf(MDC.get("trace.id")));
            return Optional.of(addressRecord(invocation.getArgument(1), "Anything Ltd"));
        });
        NotificationAggregate notificationAggregate = aggregateOf(Notification.builder()
            .consignor(ConsignmentParty.reference("a"))
            .consignee(ConsignmentParty.reference("b"))
            .build());

        MDC.put("trace.id", "trace-abc");
        try {
            resolver.resolveForSubmission(notificationAggregate, ORG);
        } finally {
            MDC.clear();
        }

        assertThat(seen).hasSize(2).containsOnly("trace-abc");
    }

    @Test
    void shouldRequireAnOrganisationToResolveForSubmission() {
        NotificationAggregate notificationAggregate = aggregateOf(Notification.builder()
            .consignor(ConsignmentParty.reference("a"))
            .build());

        assertThatExceptionOfType(BadRequestException.class)
            .isThrownBy(() -> resolver.resolveForSubmission(notificationAggregate, null))
            .withMessageContaining("organisation id is required");
    }

    @Test
    void shouldSaveADraftUnresolvedWhenThereIsNoOrganisation() {
        NotificationAggregate notificationAggregate = aggregateOf(Notification.builder()
            .consignor(ConsignmentParty.reference("a"))
            .build());

        NotificationAggregate resolved = resolver.resolveForDraft(notificationAggregate, null);

        assertThat(resolved.getNotification().getConsignor().getAddressId()).isEqualTo("a");
        verify(addressBookClient, never()).findById(any(), any());
    }

    private void stub(String addressId, String name) {
        when(addressBookClient.findById(ORG, addressId))
            .thenReturn(Optional.of(addressRecord(addressId, name)));
    }

    private static AddressBookRecord addressRecord(String addressId, String name) {
        return new AddressBookRecord(addressId, name, "1 Test Street", null, "London", null,
            "SW1A 1AA", "GB", "01632 960000", "test@example.com", false);
    }

    private static AddressBookRecord deletedAddressRecord(String addressId, String name) {
        return new AddressBookRecord(addressId, name, null, null, null, null, null, null, null,
            null, true);
    }

    private static NotificationAggregate aggregateOf(Notification notification) {
        return NotificationAggregate.builder().notification(notification).build();
    }
}
