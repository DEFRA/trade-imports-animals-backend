package uk.gov.defra.trade.imports.plantproducts.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class Ownership {

    private String assignedOrganisationId;
    private String assignedOrganisationName;
}
