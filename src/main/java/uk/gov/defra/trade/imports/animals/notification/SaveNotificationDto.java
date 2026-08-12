package uk.gov.defra.trade.imports.animals.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Request body for {@code POST /notifications}: the notification to save plus an optional actor.
 *
 * <p>The actor carries {@code organisationId} used to resolve address-book party references when
 * emitting the {@code NotificationEdited} outbox event on update.
 */
@Value
@Builder
@Jacksonized
public class SaveNotificationDto {

    @Valid
    @NotNull
    NotificationDto notification;

    ActorRequest actor;

    public static SaveNotificationDto of(NotificationDto notification) {
        return SaveNotificationDto.builder().notification(notification).build();
    }
}
