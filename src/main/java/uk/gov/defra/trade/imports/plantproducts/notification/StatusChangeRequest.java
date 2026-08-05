package uk.gov.defra.trade.imports.plantproducts.notification;

import jakarta.validation.constraints.NotNull;
import java.util.Objects;

public record StatusChangeRequest(
    @NotNull PlantProductsNotificationStatus status,
    Boolean discardChanges) {

    public StatusChangeRequest {
        Objects.requireNonNull(status, "status");
    }
}
