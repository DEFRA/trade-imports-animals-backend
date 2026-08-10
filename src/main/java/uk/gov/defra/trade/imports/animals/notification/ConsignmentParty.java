package uk.gov.defra.trade.imports.animals.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A party on a notification — consignor, consignee, importer, place of origin, destination or the
 * consignment contact. Replaced {@code Operator}, a name retired by D13.
 *
 * <p>A party is held one of two ways, and both are valid (AC5):
 *
 * <ul>
 *   <li><b>A reference</b> — {@code addressId} points at an address-book record and the remaining
 *       fields are empty on the stored document. They are filled in on read, so an edit made in the
 *       address book shows through without the notification being rewritten (AC4).
 *   <li><b>Inline</b> — {@code addressId} is null and the details are held on the notification
 *       itself. Notifications predating the address book are all of this kind and keep working
 *       untouched.
 * </ul>
 *
 * <p>The two are deliberately <em>not</em> mutually exclusive: EUDPA-295 freezes a copy of the
 * address at submit, which will sit beside a still-populated {@code addressId}.
 */
@Data
@Builder
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
     * A party held as an address-book reference — details are empty until resolved on read.
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
}
