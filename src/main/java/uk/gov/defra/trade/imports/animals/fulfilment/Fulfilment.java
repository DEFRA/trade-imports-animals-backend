package uk.gov.defra.trade.imports.animals.fulfilment;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.Document;
import org.springframework.data.annotation.Id;

@org.springframework.data.mongodb.core.mapping.Document(collection = "fulfilment")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Fulfilment {

    @Id
    private String id;

    private List<Document> fulfilment;

    private FulfilmentStatus status;

    private LocalDateTime createdAt;

    private LocalDateTime submittedAt;
}
