package uk.gov.defra.trade.imports.animals.notification;

import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Flat implementation of {@link NotificationView}, with the same fields as its JSON form.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationViewData implements NotificationView {
    private String referenceNumber;
    private Long concurrencyToken;
    private NotificationStatus status;
    private Instant created;
    private Origin origin;
    private Commodity commodity;
    private List<CommodityComplement> commodities;
    private ConsignmentParty consignor;
    private ConsignmentParty consignee;
    private Transport transport;
}
