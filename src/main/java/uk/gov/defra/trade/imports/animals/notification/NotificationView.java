package uk.gov.defra.trade.imports.animals.notification;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;

/**
 * Interface projection backing {@code GET /notifications?…}. SpEL accessors unwrap
 * {@code notification.*} content fields to preserve the pre-refactor flat wire shape.
 *
 * <p>The {@code @Value} accessors force Spring Data into an open projection, so the full
 * aggregate document (including the opaque {@code fulfilments} payload) is loaded per row.
 * Accepted trade-off: this endpoint is being replaced by an event-populated dashboard service
 * and there are no live users to notice the cost.
 *
 * <p>{@link NotificationViewData} is the concrete carrier Jackson deserializes into on the client
 * side; Spring Data returns proxy instances on the server side.
 */
@JsonDeserialize(as = NotificationViewData.class)
public interface NotificationView {

    String getReferenceNumber();

    Long getConcurrencyToken();

    NotificationStatus getStatus();

    Instant getCreated();

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
}
