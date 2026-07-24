package uk.gov.defra.trade.imports.animals.fulfilment;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.Document;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import uk.gov.defra.trade.imports.animals.ownership.Owner;

@org.springframework.data.mongodb.core.mapping.Document(collection = "fulfilment")
@CompoundIndexes({
    @CompoundIndex(
        name = "owner_created_at",
        def = "{'owner.sub': 1, 'owner.organisation': 1, 'createdAt': -1}"),
    @CompoundIndex(
        name = "owner_submitted_at",
        def = "{'owner.sub': 1, 'owner.organisation': 1, 'submittedAt': -1}")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Fulfilment {

    @Id
    private String id;

    private Owner owner;

    private List<Document> fulfilment;

    private FulfilmentStatus status;

    private LocalDateTime createdAt;

    private LocalDateTime submittedAt;
}
