package uk.gov.defra.trade.imports.animals.notification;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Concrete carrier for {@link NotificationView} — flat, matching the on-wire JSON the projection
 * produces. Jackson deserializes into this on the client side; Spring Data returns proxy
 * instances of the interface on the server side.
 *
 * <p>Lived inside {@code NotificationView} as a nested {@code Data} class until EUDPA-565, which
 * moved it out so the interface declares only behaviour.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationViewData implements NotificationView {
    private String referenceNumber;
    private Long concurrencyToken;
    private NotificationStatus status;
    private Instant created;
    private Origin origin;
    private Commodity commodity;
    private ConsignmentParty consignor;
    private ConsignmentParty consignee;
    private Transport transport;
}
