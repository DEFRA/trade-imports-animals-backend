package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import uk.gov.defra.trade.imports.animals.notification.Address;

public record TradeAddress(
    String lineOne,
    String lineTwo,
    String cityName,
    String postcodeCode,
    String countryId,
    String countryName,
    String countrySubDivisionName
) {

    static TradeAddress from(Address address) {
        if (address == null) {
            return null;
        }
        return new TradeAddress(
            address.getAddressLine1(),
            address.getAddressLine2(),
            address.getTownOrCity(),
            address.getPostcode(),
            address.getCountryCode(),
            null, // countryName - the domain holds the ISO code only; GBNAG derives the name from countryId
            address.getCounty());
    }
}
