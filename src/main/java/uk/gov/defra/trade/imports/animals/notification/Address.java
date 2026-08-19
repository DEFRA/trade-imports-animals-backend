package uk.gov.defra.trade.imports.animals.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A Standard Address Block.
 *
 * <p>Field names follow the address book (the system of record for saved addresses), so a record
 * resolved from it maps across without translation. This replaced an earlier shape carrying
 * {@code addressLine3}, {@code city} and a free-text {@code country}; {@code countryCode} is an
 * ISO 3166-1 alpha-2 code (cv-011), not a display name.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Address {

    private String addressLine1;
    private String addressLine2;
    private String townOrCity;
    private String county;
    private String postcode;
    private String countryCode;
}
