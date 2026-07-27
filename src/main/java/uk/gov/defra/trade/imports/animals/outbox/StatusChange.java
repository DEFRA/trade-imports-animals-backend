package uk.gov.defra.trade.imports.animals.outbox;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;

@Value
@Builder
public class StatusChange {

    NotificationStatus status;
    Instant dateChanged;
    Actor actor;
}
