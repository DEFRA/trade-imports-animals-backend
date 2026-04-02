package uk.gov.defra.trade.imports.animals.audit;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "audit")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Audit {
    @Id
    private String id;
    
    private Action action;
    
    private Result result;
    
    private LocalDateTime timestamp;
    
    private Integer numberOfNotifications;
    
    private List<String> notificationReferenceNumbers;
    
    private String traceId;
    
    private String userId;
    
}
