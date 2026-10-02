package uk.gov.defra.trade.imports.animals.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A party on a notification — consignor, consignee, importer, place of origin, destination or the
 * consignment contact.
 *
 * <p>Held as a literal copy of the address the trader picked: later edits or deletions in the
 * address book never reach the notification.
 */
@Data
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class ConsignmentParty {

    private String name;
    private String email;
    private String phone;
    private Address address;
}
