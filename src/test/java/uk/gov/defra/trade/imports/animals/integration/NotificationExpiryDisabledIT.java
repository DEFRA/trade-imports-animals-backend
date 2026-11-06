package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.defra.trade.imports.animals.integration.ExpireAtIndexes.expireAtIndexes;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.TestPropertySource;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.notification.NotificationAggregate;

@TestPropertySource(
    properties = {
        "notification.ttl.expiry.enabled=false",
        "spring.data.mongodb.database=notification-expiry-disabled-it"
    })
class NotificationExpiryDisabledIT extends IntegrationBase {

    @Autowired
    private MongoTemplate mongoTemplate;

    @ParameterizedTest
    @ValueSource(classes = {NotificationAggregate.class, AccompanyingDocument.class})
    void startup_shouldLeaveNoIndexOnExpireAt_whenExpiryIsDisabled(Class<?> entityClass) {
        assertThat(expireAtIndexes(mongoTemplate, entityClass)).isEmpty();
    }
}
