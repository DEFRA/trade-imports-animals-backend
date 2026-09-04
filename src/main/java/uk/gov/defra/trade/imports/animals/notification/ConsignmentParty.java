package uk.gov.defra.trade.imports.animals.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A party on a notification — consignor, consignee, importer, place of origin, destination or the
 * consignment contact.
 *
 * <p>A party is held one of two ways:
 *
 * <ul>
 *   <li><b>A reference</b> — {@code addressId} points at an address-book record and the remaining
 *       fields are empty. Whoever needs the details fills them in: the frontend for display,
 *       {@link ConsignmentPartyResolver} for GBNAG. An edit in the address book therefore shows
 *       through without the notification being rewritten.
 *   <li><b>Inline</b> — {@code addressId} is null and the details are held on the notification.
 * </ul>
 */
@Data
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class ConsignmentParty {

    /** Address-book record this party refers to; null when the address is held inline. */
    private String addressId;

    private String name;
    private String email;
    private String phone;
    private Address address;

    /**
     * A party held as an address-book reference — details are empty until resolved for
     * transmission.
     */
    public static ConsignmentParty reference(String addressId) {
        return ConsignmentParty.builder().addressId(addressId).build();
    }

    /**
     * Normalises a party for persistence: when {@code addressId} is set, drop inline details so a
     * resolved (or stale) copy is never stored beside the reference. Inline parties pass through.
     */
    public static ConsignmentParty forStorage(ConsignmentParty party) {
        if (party == null || party.getAddressId() == null) {
            return party;
        }
        return reference(party.getAddressId());
    }

    /**
     * Drops {@code addressId} from a frozen party so a dashboard row is read as details, not as a
     * live address-book reference. Persistence uses {@link #forStorage} instead.
     */
    public static ConsignmentParty inlineOnly(ConsignmentParty party) {
        if (party == null || party.getAddressId() == null) {
            return party;
        }
        return party.toBuilder().addressId(null).build();
    }
}
