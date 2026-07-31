package uk.gov.defra.trade.imports.plantproducts.notification;

import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PlantProductsTransport {

    private String borderControlPost;
    private String inspectionPremises;
    private PlantProductsMeansOfTransport meansOfTransport;
    private String transportIdentification;
    private String transportDocumentReference;
    private LocalDate arrivalDate;
    private String arrivalTime;
    private Boolean usesContainers;
    private List<TransportContainer> containers;
}
