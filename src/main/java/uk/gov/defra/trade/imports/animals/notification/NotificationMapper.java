package uk.gov.defra.trade.imports.animals.notification;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * Maps a {@link NotificationView} projection to a {@link NotificationResponse}.
 *
 * <p>Sourced from the notification-shape projection (not the full {@link Notification} aggregate)
 * so the opaque {@code fulfilments} payload is never loaded from Mongo for the read endpoints —
 * this is the AC-mandated separation ("each read API endpoint uses its corresponding projection;
 * there is no shared read method returning the full merged aggregate").
 *
 * <p>Accompanying documents are intentionally excluded ({@code ignore = true}) because they live
 * in a separate collection and are fetched and assembled by the service layer after mapping.
 *
 * <p>{@code unmappedTargetPolicy = ERROR} ensures a compile-time failure if a field is added to
 * {@link NotificationResponse} without a corresponding mapping being wired up here.
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR, unmappedSourcePolicy = ReportingPolicy.ERROR)
public interface NotificationMapper {

    @Mapping(target = "accompanyingDocuments", ignore = true)
    NotificationResponse toResponse(NotificationView notification);
}
