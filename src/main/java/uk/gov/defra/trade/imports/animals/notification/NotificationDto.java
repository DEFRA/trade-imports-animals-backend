package uk.gov.defra.trade.imports.animals.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class NotificationDto {
    
    private String referenceNumber;
    
    private Origin origin;
    
    private Commodity commodity;

    private String reasonForImport;

    private AdditionalDetails additionalDetails;

}
