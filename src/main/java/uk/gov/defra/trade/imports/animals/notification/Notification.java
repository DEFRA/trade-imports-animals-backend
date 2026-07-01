package uk.gov.defra.trade.imports.animals.notification;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "notification")
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class Notification extends NotificationBase {

    @Id
    private String id;

    /** Submitted notification content captured when an amendment begins. */
    @JsonIgnore
    private NotificationContentSnapshot submittedBaseline;
}
