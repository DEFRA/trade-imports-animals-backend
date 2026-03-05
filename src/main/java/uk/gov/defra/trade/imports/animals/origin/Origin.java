package uk.gov.defra.trade.imports.animals.origin;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "origin")
@Data
@NoArgsConstructor
public class Origin {

    private String countryOfOrigin;
}
