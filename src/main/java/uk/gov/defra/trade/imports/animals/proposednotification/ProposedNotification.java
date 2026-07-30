package uk.gov.defra.trade.imports.animals.proposednotification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.Document;
import org.springframework.data.annotation.Id;

@org.springframework.data.mongodb.core.mapping.Document(collection = "proposedNotification")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProposedNotification {

    @Id
    private String id;

    private Document body;
}
