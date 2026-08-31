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
        ensureCollectionsAndIndexes();
    }

    /**
     * Creates any missing collection and index. Idempotent, so it is safe to call again — the
     * integration tests do exactly that after dropping the database.
     */
    public void ensureCollectionsAndIndexes() {
        List<MongoPersistentEntity<?>> entities = mappedEntities();
        int created = 0;
        for (MongoPersistentEntity<?> entity : entities) {
            if (createCollection(entity.getCollection())) {
                created++;
            }
        }
        int indexes = entities.stream().mapToInt(this::createIndexes).sum();
        log.info("Mongo bootstrap complete: {} of {} collections created, {} indexes ensured, "
                + "collections={}",
            created, entities.size(), indexes, collectionNames(entities));
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
     * <p>An index failure is logged and skipped rather than fatal. A unique index can legitimately
     * fail to build against data that accumulated while the index was missing, and refusing to
     * start would leave the service undeployable. A logged failure here means the data needs
     * cleaning, not that the code needs changing.
     */
    private int createIndexes(MongoPersistentEntity<?> entity) {
        int ensured = 0;
        for (IndexDefinition definition : indexResolver.resolveIndexFor(entity.getTypeInformation())) {
            String collection = collectionFor(entity, definition);
            try {
                mongoTemplate.indexOps(collection).createIndex(definition);
                ensured++;
            } catch (RuntimeException e) {
                log.error("Could not create index {} on collection {}", indexName(definition),
                    collection, e);
            }
        }
        return ensured;
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
