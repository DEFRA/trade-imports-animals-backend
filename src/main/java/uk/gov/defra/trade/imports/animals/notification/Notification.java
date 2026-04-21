package uk.gov.defra.trade.imports.animals.notification;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "notification")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Notification {
    @Id
    private String id;
    
    @Indexed(unique = true, sparse = true)
    private String referenceNumber;
    
    private Origin origin;
    
    private Commodity commodity;
    
    private String reasonForImport;
    
    private AdditionalDetails additionalDetails;
    
    private Consignor consignor;
    
    private String cphNumber;

    private LocalDateTime created;

    private LocalDateTime updated;

}
