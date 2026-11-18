package uk.gov.defra.trade.imports.animals.integration;

import static org.junit.jupiter.params.provider.Arguments.arguments;
import static uk.gov.defra.trade.imports.animals.integration.ExpireAtIndexes.assertSingleTtlIndexNamed;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.TestPropertySource;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.notification.NotificationAggregate;

@TestPropertySource(
    properties = {
        "notification.ttl.expiry.enabled=true",
        "spring.data.mongodb.database=notification-expiry-startup-it"
    })
class NotificationExpiryStartupIT extends IntegrationBase {

    @Autowired
    private MongoTemplate mongoTemplate;

    private static Stream<Arguments> entitiesAndTheirTtlIndexNames() {
        return Stream.of(
            arguments(NotificationAggregate.class, "notification_expire_at_ttl"),
            arguments(AccompanyingDocument.class, "accompanying_document_expire_at_ttl"));
    }

    @ParameterizedTest
    @MethodSource("entitiesAndTheirTtlIndexNames")
    void startup_shouldCreateTheNamedTtlIndex_whenExpiryIsEnabled(
        Class<?> entityClass, String indexName) {
        assertSingleTtlIndexNamed(mongoTemplate, entityClass, indexName);
    }
}
