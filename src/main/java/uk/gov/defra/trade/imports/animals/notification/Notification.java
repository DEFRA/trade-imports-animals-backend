package uk.gov.defra.trade.imports.animals.notification;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import uk.gov.defra.trade.imports.animals.ownership.Owner;

@Document(collection = "notification")
@CompoundIndexes({
    @CompoundIndex(
        name = "owner_status_arrival_date",
        def = "{'owner.sub': 1, 'owner.organisation': 1, 'status': 1, "
            + "'transport.arrivalDate': -1}"),
    @CompoundIndex(
        name = "owner_status_created",
        def = "{'owner.sub': 1, 'owner.organisation': 1, 'status': 1, 'created': -1}")
})
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class Notification extends NotificationBase {

    @Id
    private String id;

    private Owner owner;

    /** Submitted notification content captured when an amendment begins. */
    @JsonIgnore
    private NotificationContentSnapshot submittedBaseline;
}
