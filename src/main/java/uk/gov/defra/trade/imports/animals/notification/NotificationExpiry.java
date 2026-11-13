package uk.gov.defra.trade.imports.animals.notification;

import java.time.Instant;

public interface NotificationExpiry {
    Instant getExpireAt();
}
