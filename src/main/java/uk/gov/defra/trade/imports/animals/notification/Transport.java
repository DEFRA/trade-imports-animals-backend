package uk.gov.defra.trade.imports.animals.notification;

import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Transport {
    
    private String portOfEntry;
    private LocalDate arrivalDate;

}
