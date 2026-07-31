package uk.gov.defra.trade.imports.plantproducts.accompanyingdocument;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "plant_products_accompanying_documents")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PlantProductsAccompanyingDocument {

    @Id
    private String id;

    @Indexed
    private String notificationReferenceNumber;

    private String documentType;

    private String documentReference;

    private LocalDate issueDate;

    private List<DocumentFile> files;

    private LocalDateTime created;

    private LocalDateTime updated;
}
