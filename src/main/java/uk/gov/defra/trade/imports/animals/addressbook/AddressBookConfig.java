package uk.gov.defra.trade.imports.animals.addressbook;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the address-book service, the system of record for saved addresses.
 *
 * @param baseUrl the base URL of the address-book API
 * @param connectTimeout connect timeout for address-book HTTP calls (short — inside the outbox lock)
 * @param readTimeout read timeout for address-book HTTP calls (short — inside the outbox lock)
 * @param addressByIdPath path template for a single-address GET (org- and id-scoped)
 */
@Validated
@ConfigurationProperties(prefix = "address-book")
public record AddressBookConfig(
    @NotBlank String baseUrl,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("2s") Duration readTimeout,
    @DefaultValue("/organisation/{orgId}/addresses/{addressId}") String addressByIdPath) {}
