package uk.gov.defra.trade.imports.animals.proposednotification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.Document;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import uk.gov.defra.trade.imports.animals.ownership.Owner;

@org.springframework.data.mongodb.core.mapping.Document(collection = "proposedNotification")
@CompoundIndex(
    name = "owner",
    def = "{'owner.sub': 1, 'owner.organisation': 1}")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProposedNotification {

    @Id
    private String id;

    private Owner owner;

    private Document body;
}
