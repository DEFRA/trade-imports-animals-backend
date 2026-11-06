package uk.gov.defra.trade.imports.animals.notification;

import com.mongodb.MongoCommandException;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.configuration.NotificationTtlConfig;

/**
 * Once the application is ready, ensures the Mongo TTL indexes on {@code expireAt} exist, replacing
 * any plain index on {@code expireAt} on the notification collection. Exists only when
 * {@code notification.ttl.expiry.enabled=true}, and touches no index in prod.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "notification.ttl.expiry", name = "enabled", havingValue = "true")
public class NotificationExpiryIndex {

    private static final String EXPIRE_AT = "expireAt";

    private final MongoTemplate mongoTemplate;
    private final NotificationTtlConfig ttlConfig;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureTtlIndexes() {
        if (ttlConfig.isProd()) {
            log.warn("Notification expiry is enabled in prod; refusing to create the TTL indexes");
            return;
        }
        createTtlIndex(AccompanyingDocument.class, "accompanying_document_expire_at_ttl");
        replacePlainExpireAtIndexWithTtlIndex(
            NotificationAggregate.class, "notification_expire_at_ttl");
    }

    private void replacePlainExpireAtIndexWithTtlIndex(Class<?> entityClass, String indexName) {
        dropPlainExpireAtIndexes(entityClass);
        createTtlIndex(entityClass, indexName);
    }

    private void createTtlIndex(Class<?> entityClass, String indexName) {
        mongoTemplate.indexOps(entityClass).createIndex(
            new Index().on(EXPIRE_AT, Sort.Direction.ASC).expire(Duration.ZERO).named(indexName));
        log.info("Expiry TTL index {} is in place", indexName);
    }

    private void dropPlainExpireAtIndexes(Class<?> entityClass) {
        IndexOperations indexOps = mongoTemplate.indexOps(entityClass);
        String collectionName = mongoTemplate.getCollectionName(entityClass);
        indexOps.getIndexInfo().stream()
            .filter(NotificationExpiryIndex::isPlainExpireAtIndex)
            .map(IndexInfo::getName)
            .forEach(indexName -> dropPlainIndex(indexOps, collectionName, indexName));
    }

    private static void dropPlainIndex(
        IndexOperations indexOps, String collectionName, String indexName) {
        try {
            indexOps.dropIndex(indexName);
            log.info("Dropped plain index {} on expireAt from {}", indexName, collectionName);
        } catch (DataAccessException e) {
            if (!isIndexNotFound(e)) {
                throw e;
            }
            log.info(
                "Plain index {} on expireAt was already dropped from {}", indexName, collectionName, e);
        }
    }

    private static boolean isIndexNotFound(DataAccessException e) {
        return e.getCause() instanceof MongoCommandException command
            && "IndexNotFound".equals(command.getErrorCodeName());
    }

    private static boolean isPlainExpireAtIndex(IndexInfo index) {
        List<String> keys = index.getIndexFields().stream().map(IndexField::getKey).toList();
        return index.getExpireAfter().isEmpty() && keys.equals(List.of(EXPIRE_AT));
    }
}
