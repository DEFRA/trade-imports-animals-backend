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
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "plant_products_notification")
@CompoundIndex(name = "org_status_dashboard", def = "{'ownership.assignedOrganisationId': 1, 'status': 1}")
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class PlantProductsNotification extends PlantProductsNotificationBase {

    @Id
    private String id;

    @JsonIgnore
    private PlantProductsNotificationContentSnapshot submittedBaseline;

    @JsonIgnore
    @Indexed
    private LocalDateTime expireAt;
}
