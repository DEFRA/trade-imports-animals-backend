package uk.gov.defra.trade.imports.animals.notification;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "notification")
@Data
@NoArgsConstructor
public class Notification {
    @Id
    private Long id;
    
    @Indexed(unique = true, sparse = true)
    private String referenceNumber;
    
    private Origin origin;

}
