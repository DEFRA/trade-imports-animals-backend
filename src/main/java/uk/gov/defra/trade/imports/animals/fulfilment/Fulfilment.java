package uk.gov.defra.trade.imports.animals.fulfilment;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

@org.springframework.data.mongodb.core.mapping.Document(collection = "fulfilment")
@CompoundIndexes({
    @CompoundIndex(
        name = "created_at",
        def = "{'createdAt': -1}"),
    @CompoundIndex(
        name = "submitted_at",
        def = "{'submittedAt': -1}"),
    @CompoundIndex(
        name = "copy_idempotency_key",
        def = "{'copyIdempotencyKey': 1}",
        unique = true,
        partialFilter = "{'copyIdempotencyKey': {'$type': 'string'}}")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Fulfilment {

    @Id
    private String id;

    private List<Document> fulfilment;

    private List<Document> submittedFulfilment;

    private FulfilmentStatus status;

    private LocalDateTime createdAt;

    private LocalDateTime submittedAt;

    private String copyIdempotencyKey;

    @JsonIgnore
    private String copySourceReference;
}
