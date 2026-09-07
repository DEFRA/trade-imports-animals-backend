package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
    void shouldValidateEveryReferenceableRoleWithoutMutatingTheNotification() {
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

        assertThatCode(() -> resolver.validatePartiesAtSubmit(notificationAggregate, ORG))
            .doesNotThrowAnyException();
        assertThat(notificationAggregate.getNotification().getConsignor().getName()).isNull();
    }

    @Test
    void shouldValidatePlaceOfOriginAndTheConsignmentContact() {
        stub("origin", "Origin Farm");
        stub("contact", "Contact Ltd");
        NotificationAggregate notificationAggregate = aggregateOf(Notification.builder()
            .placeOfOrigin(ConsignmentParty.reference("origin"))
            .consignment(ConsignmentParty.reference("contact"))
            .build());

        resolver.validatePartiesAtSubmit(notificationAggregate, ORG);

        verify(addressBookClient).findById(ORG, "origin");
        verify(addressBookClient).findById(ORG, "contact");
    }

    @Test
    void shouldPassACopyWithNoAddressIdThroughUnchanged() {
        ConsignmentParty origin = ConsignmentParty.builder().name("Origin Farm").build();
        NotificationAggregate notificationAggregate = aggregateOf(Notification.builder()
            .placeOfOrigin(origin)
            .build());

        resolver.validatePartiesAtSubmit(notificationAggregate, ORG);

        assertThat(notificationAggregate.getNotification().getPlaceOfOrigin()).isSameAs(origin);
        verify(addressBookClient, never()).findById(any(), any());
    }

    @Test
    void shouldFetchOnceWhenTwoRolesShareAnAddress() {
        stub("shared", "Both Ends Ltd");
        NotificationAggregate notificationAggregate = aggregateOf(Notification.builder()
            .consignor(ConsignmentParty.reference("shared"))
            .consignee(ConsignmentParty.reference("shared"))
            .build());

        resolver.validatePartiesAtSubmit(notificationAggregate, ORG);

        verify(addressBookClient, times(1)).findById(ORG, "shared");
    }

    @Test
    void shouldNotTouchTheAddressBookWhenNoRoleIsAReference() {
        NotificationAggregate notificationAggregate = aggregateOf(Notification.builder()
            .consignor(ConsignmentParty.builder().name("Inline Ltd").build())
            .build());

        resolver.validatePartiesAtSubmit(notificationAggregate, ORG);

        verify(addressBookClient, never()).findById(any(), any());
    }

    @Test
    void shouldFailSubmissionNamingEveryUnresolvableRoleInRoleOrder() {
        when(addressBookClient.findById(ORG, "missing-consignor")).thenReturn(Optional.empty());
        when(addressBookClient.findById(ORG, "missing-importer")).thenReturn(Optional.empty());
        NotificationAggregate notificationAggregate = aggregateOf(Notification.builder()
            .consignor(ConsignmentParty.reference("missing-consignor"))
            .importer(ConsignmentParty.reference("missing-importer"))
            .build());

        assertThatExceptionOfType(UnresolvableConsignmentPartyException.class)
            .isThrownBy(() -> resolver.validatePartiesAtSubmit(notificationAggregate, ORG))
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
            .isThrownBy(() -> resolver.validatePartiesAtSubmit(notificationAggregate, ORG))
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
            .isThrownBy(() -> resolver.validatePartiesAtSubmit(notificationAggregate, ORG))
            .satisfies(thrown -> assertThat(thrown.addressIdByRole())
                .containsExactly(entry("destination", "gone")));
    }

    @Test
    void shouldPropagateAnAddressBookOutageAsItsOwnException() {
        when(addressBookClient.findById(ORG, "a")).thenThrow(
            new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));
        NotificationAggregate notificationAggregate = aggregateOf(Notification.builder()
            .consignor(ConsignmentParty.reference("a"))
            .build());

        assertThatExceptionOfType(HttpServerErrorException.class)
            .isThrownBy(() -> resolver.validatePartiesAtSubmit(notificationAggregate, ORG));
    }

    @Test
    void shouldCarryTheCallersLoggingContextIntoEachLookup() {
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
            resolver.validatePartiesAtSubmit(notificationAggregate, ORG);
        } finally {
            MDC.clear();
        }

        assertThat(seen).hasSize(2).containsOnly("trace-abc");
    }

    @Test
    void shouldRequireAnOrganisationToValidateAtSubmit() {
        NotificationAggregate notificationAggregate = aggregateOf(Notification.builder()
            .consignor(ConsignmentParty.reference("a"))
            .build());

        assertThatExceptionOfType(BadRequestException.class)
            .isThrownBy(() -> resolver.validatePartiesAtSubmit(notificationAggregate, null))
            .withMessageContaining("organisation id is required");
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
