package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import uk.gov.defra.trade.imports.animals.notification.NotificationAggregate;

class NotificationAggregateIndexesIT extends IntegrationBase {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Test
    void created_shouldCarryCompoundIndexesForOrganisationScopedDashboardSorts() {
        assertThat(mongoTemplate.indexOps(NotificationAggregate.class).getIndexInfo())
            .anySatisfy(index -> {
                assertThat(index.getName()).isEqualTo("organisationId_status_created");
                assertThat(index.getIndexFields()).hasSize(3);
                assertThat(index.getIndexFields().get(0).getKey()).isEqualTo("organisationId");
                assertThat(index.getIndexFields().get(1).getKey()).isEqualTo("status");
                assertThat(index.getIndexFields().get(2).getKey()).isEqualTo("created");
            })
            .anySatisfy(index -> {
                assertThat(index.getName()).isEqualTo("organisationId_status_arrivalDate");
                assertThat(index.getIndexFields()).hasSize(3);
                assertThat(index.getIndexFields().get(0).getKey()).isEqualTo("organisationId");
                assertThat(index.getIndexFields().get(1).getKey()).isEqualTo("status");
                assertThat(index.getIndexFields().get(2).getKey())
                    .isEqualTo("notification.transport.arrivalDate");
            });
    }
}
