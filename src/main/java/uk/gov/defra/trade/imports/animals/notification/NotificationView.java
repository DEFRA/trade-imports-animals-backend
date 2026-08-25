package uk.gov.defra.trade.imports.animals.notification;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;

/**
 * Interface projection backing {@code GET /notifications?…}. SpEL accessors unwrap
 * {@code notification.*} content fields to preserve the pre-refactor flat wire shape. Forces
 * open-projection (full aggregate load) — acceptable for the dashboard read.
 *
 * <p>{@link Data} is the concrete carrier Jackson deserializes into on the client side; Spring
 * Data returns proxy instances on the server side.
 */
@JsonDeserialize(as = NotificationView.Data.class)
public interface NotificationView {

    String getReferenceNumber();

    Long getConcurrencyToken();

    NotificationStatus getStatus();

    LocalDateTime getCreated();

    @Value("#{target.notification?.origin}")
    Origin getOrigin();

    @Value("#{target.notification?.commodity}")
    Commodity getCommodity();

    @Value("#{target.notification?.consignor}")
    ConsignmentParty getConsignor();

    @Value("#{target.notification?.consignee}")
    ConsignmentParty getConsignee();

    @Value("#{target.notification?.transport}")
    Transport getTransport();

    /** Jackson deserialization target — flat, matches the on-wire JSON produced by the projection. */
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    class Data implements NotificationView {
        private String referenceNumber;
        private Long concurrencyToken;
        private NotificationStatus status;
        private LocalDateTime created;
        private Origin origin;
        private Commodity commodity;
        private ConsignmentParty consignor;
        private ConsignmentParty consignee;
        private Transport transport;
    }
}
