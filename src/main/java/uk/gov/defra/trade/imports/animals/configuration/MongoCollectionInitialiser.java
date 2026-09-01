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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Creates every mapped collection and its annotation-declared indexes before the service accepts
 * traffic.
 *
 * <p>MongoDB takes an exclusive lock to create a collection, so the first write to a missing
 * collection cannot be made inside a multi-document transaction without risking a
 * {@code WriteConflict} against any concurrent transaction doing the same. Creating the
 * collections up front removes that class of conflict entirely.
 *
 * <p>This replaces {@code spring.data.mongodb.auto-index-creation}, which achieved the same thing
 * as an undocumented side effect of constructing {@link MongoTemplate}. The index definitions
 * still live on the entity annotations — this uses the very same {@link IndexResolver} Spring Data
 * uses internally — but the trigger is now a named bean with a log line, an explicit failure
 * policy and an integration test over its result.
 *
 * <p>Runs as {@link InitializingBean} rather than an {@code ApplicationRunner} or a
 * {@code ApplicationReadyEvent} listener: those all fire after the web server has opened its port,
 * which would leave a window in which a request could arrive first.
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
     * Rebuilds anything that has gone missing since startup.
     *
     * <p>The bootstrap runs once, so a database dropped or restored underneath a running service
     * leaves it with no indexes and with collections that get created lazily on first write inside
     * a transaction — the WriteConflict this class exists to prevent. An E2E reseed does exactly
     * that. Recovering is the service's own job: nothing outside it can rebuild its schema without
     * restarting it.
     *
     * <p>Failures are swallowed deliberately. A wipe is not the only reason this can fail, and a
     * scheduled recovery that killed the service would be worse than the state it is recovering
     * from. Startup still treats the same failures as fatal.
     */
    @Scheduled(fixedDelayString = "${mongo.schema.recheck-interval-ms:60000}")
    public void recheckCollectionsAndIndexes() {
        try {
            ensureCollectionsAndIndexes();
        } catch (RuntimeException e) {
            log.error("Mongo schema recheck failed; retrying on the next interval", e);
        }
    }

    /**
     * Creates any missing collection and index. Idempotent, so it is safe to call again — the
     * integration tests do exactly that after dropping the database.
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
     * The collections this service owns.
     *
     * <p>The mapping context also holds every nested type reachable from a root — {@code Address},
     * {@code Commodity} and friends — which would otherwise be given a collection named after the
     * class. Only types carrying {@link Document} are top-level collections.
     */
    private List<MongoPersistentEntity<?>> mappedEntities() {
        return mappingContext.getPersistentEntities().stream()
            .filter(entity -> entity.isAnnotationPresent(Document.class))
            .<MongoPersistentEntity<?>>map(entity -> entity)
            .toList();
    }

    /**
     * Creates one collection, tolerating the case where it already exists.
     *
     * <p>Deliberately no {@code collectionExists} pre-check: going straight to create and
     * swallowing {@code NamespaceExists} is race-free when several instances start together, and
     * it needs no {@code listCollections} privilege.
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
     * Creates the annotation-declared indexes for one entity.
     *
     * <p>A unique index that will not build is fatal, because the application relies on those
     * indexes for correctness rather than for speed: {@code notification.referenceNumber} is what
     * makes {@code createNotification}'s collision retry work, and {@code outbox}'s
     * {@code aggregate_version_uq} is what makes {@code OutboxService.appendEvent}'s duplicate
     * guard work. Both are written as "insert and catch the duplicate key", so a missing index
     * does not fail loudly — it silently persists the duplicate. Starting without one would trade
     * a refused deployment for corrupt data, so the service refuses to start instead. A failure
     * here means the collection holds duplicates that need clearing before the index can build.
     *
     * <p>A non-unique index enforces nothing, so a failure to build one costs query performance
     * and no more. Those are logged and skipped, and the service starts.
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
