package uk.gov.defra.trade.imports.animals.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

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
