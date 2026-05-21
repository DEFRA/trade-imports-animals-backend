package uk.gov.defra.trade.imports.animals.notification;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * Maps a {@link Notification} entity to a {@link NotificationResponse}.
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
    NotificationResponse toResponse(Notification notification);
}
