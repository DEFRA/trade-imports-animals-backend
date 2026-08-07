package uk.gov.defra.trade.imports.animals.addressbook;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the address-book service, the system of record for saved addresses.
 *
 * @param baseUrl the base URL of the address-book API
 */
@Validated
@ConfigurationProperties(prefix = "address-book")
public record AddressBookConfig(@NotBlank String baseUrl) {}
