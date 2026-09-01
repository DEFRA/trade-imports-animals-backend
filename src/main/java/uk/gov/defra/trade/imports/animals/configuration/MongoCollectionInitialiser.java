package uk.gov.defra.trade.imports.animals.configuration;

import com.mongodb.MongoCommandException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexResolver;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver.IndexDefinitionHolder;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.stereotype.Component;

/**
 * Creates every mapped collection and its annotation-declared indexes before the service accepts
 * traffic, replacing {@code spring.data.mongodb.auto-index-creation}.
 *
 * <p>Creating a collection takes an exclusive lock, so a first write inside a transaction can lose
 * a {@code WriteConflict} to a concurrent one doing the same. Creating them up front avoids it.
 *
 * <p>{@link InitializingBean} rather than {@code ApplicationReadyEvent}: that fires after the port
 * opens, leaving a window for a request to arrive first.
 */
@Component
@Slf4j
public class MongoCollectionInitialiser implements InitializingBean {

    /** MongoDB's {@code NamespaceExists} server error — the collection was created concurrently. */
    private static final int NAMESPACE_EXISTS = 48;

    private final MongoTemplate mongoTemplate;
    private final MongoMappingContext mappingContext;
    private final IndexResolver indexResolver;

    public MongoCollectionInitialiser(MongoTemplate mongoTemplate,
        MongoMappingContext mappingContext) {
        this.mongoTemplate = mongoTemplate;
        this.mappingContext = mappingContext;
        this.indexResolver = IndexResolver.create(mappingContext);
    }

    @Override
    public void afterPropertiesSet() {
        int created = ensureCollectionsAndIndexes();
        log.info("Mongo bootstrap complete at startup: {} collection(s) created", created);
    }

    /**
     * Creates any missing collection and index. Idempotent.
     *
     * @return the number of collections this call created.
     */
    public int ensureCollectionsAndIndexes() {
        List<MongoPersistentEntity<?>> entities = mappedEntities();
        int created = 0;
        for (MongoPersistentEntity<?> entity : entities) {
            if (createCollection(entity.getCollection())) {
                created++;
            }
        }
        int indexes = entities.stream().mapToInt(this::createIndexes).sum();
        // The recheck runs on a timer, so only say something when there was something to do.
        if (created > 0) {
            log.info("Mongo bootstrap: {} of {} collections created, {} indexes ensured, "
                    + "collections={}",
                created, entities.size(), indexes, collectionNames(entities));
        } else {
            log.debug("Mongo bootstrap: nothing missing, {} indexes ensured, collections={}",
                indexes, collectionNames(entities));
        }
        return created;
    }

    /**
     * The mapping context also holds nested types reachable from a root, which would each get a
     * collection named after the class. Only {@link Document} types are top-level collections.
     */
    private List<MongoPersistentEntity<?>> mappedEntities() {
        return mappingContext.getPersistentEntities().stream()
            .filter(entity -> entity.isAnnotationPresent(Document.class))
            .<MongoPersistentEntity<?>>map(entity -> entity)
            .toList();
    }

    /**
     * No {@code collectionExists} pre-check: creating and swallowing {@code NamespaceExists} is
     * race-free across instances and needs no {@code listCollections} privilege.
     *
     * @return true if this call created the collection.
     */
    private boolean createCollection(String collection) {
        try {
            mongoTemplate.getDb().createCollection(collection);
            return true;
        } catch (MongoCommandException e) {
            if (e.getErrorCode() == NAMESPACE_EXISTS) {
                return false;
            }
            throw e;
        }
    }

    /**
     * A unique index that will not build is fatal. Callers write "insert and catch the duplicate
     * key", so a missing unique index silently persists duplicates rather than failing loudly. A
     * failure here means the collection already holds duplicates that need clearing.
     *
     * <p>A non-unique index enforces nothing, so failing to build one costs only query
     * performance. Logged and skipped.
     */
    private int createIndexes(MongoPersistentEntity<?> entity) {
        int ensured = 0;
        for (IndexDefinition definition : indexResolver.resolveIndexFor(entity.getTypeInformation())) {
            String collection = collectionFor(entity, definition);
            try {
                mongoTemplate.indexOps(collection).createIndex(definition);
                ensured++;
            } catch (RuntimeException e) {
                if (isUnique(definition)) {
                    throw new IllegalStateException(
                        "Could not create unique index %s on collection %s; the service will not "
                            .formatted(indexName(definition), collection)
                            + "start without it because application code relies on it to reject "
                            + "duplicates", e);
                }
                log.error("Could not create index {} on collection {}", indexName(definition),
                    collection, e);
            }
        }
        return ensured;
    }

    private static boolean isUnique(IndexDefinition definition) {
        return definition.getIndexOptions().getBoolean("unique", false);
    }

    private static String collectionFor(MongoPersistentEntity<?> entity,
        IndexDefinition definition) {
        return definition instanceof IndexDefinitionHolder holder
            ? holder.getCollection()
            : entity.getCollection();
    }

    private static String indexName(IndexDefinition definition) {
        var name = definition.getIndexOptions().get("name");
        return name != null ? name.toString() : definition.getIndexKeys().toJson();
    }

    private static List<String> collectionNames(List<MongoPersistentEntity<?>> entities) {
        return entities.stream().map(MongoPersistentEntity::getCollection).sorted().toList();
    }
}
