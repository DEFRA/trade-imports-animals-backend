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
            address.getCity(),
            null, // postcodeCode - not yet in domain model
            address.getCountry(),
            null, // countryName - not yet in domain model
            null); // countrySubDivisionName - not yet in domain model
    }
}
