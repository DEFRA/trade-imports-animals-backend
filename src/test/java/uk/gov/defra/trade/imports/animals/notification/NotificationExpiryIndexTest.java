package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import uk.gov.defra.trade.imports.animals.configuration.NotificationTtlConfig;

class NotificationExpiryIndexTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TtlProperties.class, NotificationExpiryIndex.class)
        .withBean(MongoTemplate.class, () -> mock(MongoTemplate.class))
        .withPropertyValues("notification.ttl.environment=dev");

    @Configuration
    @EnableConfigurationProperties(NotificationTtlConfig.class)
    static class TtlProperties {}

    @Test
    void bean_isAbsentWhenExpiryIsNotConfigured() {
        contextRunner.run(
            context -> assertThat(context).doesNotHaveBean(NotificationExpiryIndex.class));
    }

    @Test
    void bean_isAbsentWhenExpiryIsDisabled() {
        contextRunner
            .withPropertyValues("notification.ttl.expiry.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(NotificationExpiryIndex.class));
    }

    @Test
    void bean_existsWhenExpiryIsEnabled() {
        contextRunner
            .withPropertyValues("notification.ttl.expiry.enabled=true")
            .run(context -> assertThat(context).hasSingleBean(NotificationExpiryIndex.class));
    }

    @Test
    void createIndexes_inProdTouchesNoIndexEvenWhenExpiryIsEnabled() {
        // Given
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        NotificationExpiryIndex expiryIndex =
            new NotificationExpiryIndex(mongoTemplate, new NotificationTtlConfig(7, "prod"));

        // When
        expiryIndex.createIndexes();

        // Then
        verifyNoInteractions(mongoTemplate);
    }
}
