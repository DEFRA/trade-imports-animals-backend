package uk.gov.defra.trade.imports.animals.fulfilment;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.Document;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FulfilmentDto {

    private String id;

    @NotNull
    private List<Document> fulfilment;
}
