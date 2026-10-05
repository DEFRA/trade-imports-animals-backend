package uk.gov.defra.trade.imports.animals.notification;

import java.time.LocalDate;
import java.util.List;
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
    /** The chosen arrival day, as a calendar date. See {@link NotificationBase#exitDate}. */
    private LocalDate arrivalDate;
    private MeansOfTransport meansOfTransport;
    private String transportIdentification;
    private String transportDocumentReference;
    private List<String> transitedCountries;
    private Transporter transporter;

}
