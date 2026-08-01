package uk.gov.defra.trade.imports.plantproducts.notification;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "plant_products_notification")
@CompoundIndexes({
    @CompoundIndex(
        name = "org_status_dashboard",
        def = "{'ownership.assignedOrganisationId': 1, 'status': 1}"),
    @CompoundIndex(
        name = "copy_idempotency_key",
        def = "{'copyIdempotencyKey': 1}",
        unique = true,
        partialFilter = "{'copyIdempotencyKey': {'$type': 'string'}}")
})
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class PlantProductsNotification extends PlantProductsNotificationBase {

    @Id
    private String id;

    private String copyIdempotencyKey;

    @JsonIgnore
    private PlantProductsNotificationContentSnapshot submittedBaseline;

    @JsonIgnore
    @Indexed
    private LocalDateTime expireAt;
}
