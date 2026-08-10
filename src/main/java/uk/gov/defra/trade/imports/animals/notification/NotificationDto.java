package uk.gov.defra.trade.imports.animals.notification;

import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.bson.Document;

@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class NotificationDto extends NotificationBase {

    /** Opaque obligation-fulfilment payload — persisted byte-faithfully on the merged aggregate; nullable during the transition while some callers still POST without it. */
    private List<Document> fulfilments;
}
