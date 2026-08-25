package uk.gov.defra.trade.imports.animals.notification;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/** Notification content sub-object of {@link NotificationAggregate}. Embedded, not a Mongo collection. */
@Data
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class Notification extends NotificationBase {
}
