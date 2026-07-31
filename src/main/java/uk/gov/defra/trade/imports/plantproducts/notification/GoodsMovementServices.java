package uk.gov.defra.trade.imports.plantproducts.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GoodsMovementServices {

    private CommonTransitConvention commonTransitConvention;
    private String movementReferenceNumber;
    private Boolean usingGvms;
}
